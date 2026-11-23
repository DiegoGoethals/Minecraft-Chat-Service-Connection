package dev.tesmp.fluxerbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tesmp.fluxerbridge.bridge.fluxer.FluxerGateway;
import dev.tesmp.fluxerbridge.protocol.EventKind;
import dev.tesmp.fluxerbridge.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides where every message goes. Thread-safe: called from the Minecraft
 * hub's threads and the Fluxer Gateway thread.
 */
public final class Router implements FluxerGateway.Listener {
	private static final Logger LOG = LoggerFactory.getLogger("Router");
	private static final long STATUS_TIMEOUT_MS = 30_000;

	private record PendingStatus(BridgeConfig.Link link, long createdAt) {
	}

	private final BridgeConfig config;
	private final Sinks.Fluxer fluxer;
	private volatile Sinks.Minecraft minecraft;
	private volatile String selfUserId;
	private final Map<String, PendingStatus> pendingStatus = new ConcurrentHashMap<>();
	private final Map<String, Long> offlineNoticeAt = new ConcurrentHashMap<>();

	public Router(BridgeConfig config, Sinks.Fluxer fluxer) {
		this.config = config;
		this.fluxer = fluxer;
	}

	public void setMinecraft(Sinks.Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	public void setSelfUserId(String id) {
		this.selfUserId = id;
	}

	// ------------------------------------------------------ Minecraft -> Fluxer

	public void onServerConnected(String serverId) {
		LOG.info("Minecraft server \"{}\" connected", serverId);
	}

	public void onServerDisconnected(String serverId) {
		LOG.info("Minecraft server \"{}\" disconnected", serverId);
	}

	public void onMinecraftMessage(String serverId, Message message) {
		switch (message) {
			case Message.Chat chat -> relayChat(serverId, chat);
			case Message.ServerEvent event -> relayEvent(serverId, event);
			case Message.Status status -> answerStatus(serverId, status);
			default -> {
				// handshake and keep-alive are handled by the hub
			}
		}
	}

	private void relayChat(String serverId, Message.Chat chat) {
		String tag = chat.tag() == null ? EventKind.TAG_CHAT : chat.tag();
		String author = clean(chat.author(), "Unknown");
		String text = clean(chat.text(), "");
		if (text.isEmpty()) {
			return;
		}
		String server = config.displayName(serverId);
		for (BridgeConfig.Link link : linksFor(serverId)) {
			if (!link.toFluxer || !link.acceptsTag(tag)) {
				continue;
			}
			String escapedText = TextFormat.escapeMarkdown(text);
			String botContent = TextFormat.fill(link.chatFormat, Map.of(
					"server", TextFormat.escapeMarkdown(server),
					"author", TextFormat.escapeMarkdown(author),
					"text", escapedText));
			if (link.useWebhook()) {
				String id = chat.authorId() != null ? chat.authorId() : author;
				String avatar = TextFormat.fill(link.avatarUrl, Map.of("name", author, "uuid", id));
				fluxer.postAs(link, TextFormat.truncate(author, 80), avatar, escapedText, botContent);
			} else {
				fluxer.post(link, botContent);
			}
		}
	}

	private void relayEvent(String serverId, Message.ServerEvent event) {
		String tag = event.tag() != null ? event.tag() : event.kind();
		String text = clean(event.text(), "");
		if (text.isEmpty() || tag == null) {
			return;
		}
		String server = config.displayName(serverId);
		Map<String, String> values = new HashMap<>();
		values.put("server", TextFormat.escapeMarkdown(server));
		values.put("text", TextFormat.escapeMarkdown(text));
		values.put("player", TextFormat.escapeMarkdown(clean(event.player(), "")));
		for (BridgeConfig.Link link : linksFor(serverId)) {
			if (!link.toFluxer || !link.acceptsTag(tag)) {
				continue;
			}
			String content = TextFormat.fill(link.eventFormat, values);
			if (link.useWebhook()) {
				fluxer.postAs(link, TextFormat.truncate(server, 80), null, content, content);
			} else {
				fluxer.post(link, content);
			}
		}
	}

	private void answerStatus(String serverId, Message.Status status) {
		PendingStatus pending = pendingStatus.remove(status.requestId());
		if (pending == null) {
			return;
		}
		List<String> players = status.players() == null ? List.of() : status.players();
		List<String> names = new ArrayList<>();
		for (String p : players) {
			names.add(TextFormat.escapeMarkdown(p));
		}
		Map<String, String> values = Map.of(
				"server", TextFormat.escapeMarkdown(config.displayName(serverId)),
				"count", String.valueOf(players.size()),
				"max", String.valueOf(status.maxPlayers()),
				"players", String.join(", ", names));
		String template = players.isEmpty() ? config.commands.playersNone : config.commands.playersOnline;
		if (template != null && !template.isBlank()) {
			fluxer.post(pending.link(), TextFormat.fill(template, values));
		}
	}

	// ------------------------------------------------------ Fluxer -> Minecraft

	@Override
	public void onReady(String selfUserId, String selfName) {
		setSelfUserId(selfUserId);
	}

	@Override
	public void onMessageCreate(JsonObject msg) {
		String channelId = str(msg, "channel_id");
		if (channelId == null) {
			return;
		}
		List<BridgeConfig.Link> links = new ArrayList<>();
		for (BridgeConfig.Link link : config.links) {
			if (channelId.equals(link.channelId)) {
				links.add(link);
			}
		}
		if (links.isEmpty()) {
			return;
		}

		JsonObject author = msg.has("author") && msg.get("author").isJsonObject() ? msg.getAsJsonObject("author") : null;
		if (author == null) {
			return;
		}
		String authorId = str(author, "id");
		if (authorId != null && authorId.equals(selfUserId)) {
			return; // our own bot messages
		}
		String webhookId = str(msg, "webhook_id");
		if (webhookId != null && (fluxer.isOwnWebhook(webhookId) || config.fluxer.ignoreWebhooks)) {
			return;
		}
		if (bool(author, "system") || (bool(author, "bot") && config.fluxer.ignoreBots && webhookId == null)) {
			return;
		}
		int type = msg.has("type") ? msg.get("type").getAsInt() : 0;
		if (type != 0 && type != 19) {
			return; // joins, pins, calls ...
		}

		String content = str(msg, "content");
		if (content == null) {
			content = "";
		}

		if (config.commands.enabled && handleCommand(content, links)) {
			return;
		}

		String name = displayName(msg, author);
		Map<String, String> mentionNames = mentionNames(msg);
		StringBuilder text = new StringBuilder(TextFormat.fluxerToMinecraft(content, mentionNames::get));
		appendAttachments(msg, text);
		String line = text.toString().trim();
		if (line.isEmpty()) {
			return;
		}

		Sinks.Minecraft mc = minecraft;
		for (BridgeConfig.Link link : links) {
			if (!link.toMinecraft) {
				continue;
			}
			if (mc != null && mc.isConnected(link.server)) {
				mc.send(link.server, new Message.Chat(str(msg, "id"), EventKind.TAG_CHAT, name, authorId, line, link.minecraftLabel));
			} else {
				maybeSendOfflineNotice(link);
			}
		}
	}

	/** @return true when the message was a bridge command and must not be relayed */
	private boolean handleCommand(String content, List<BridgeConfig.Link> links) {
		String prefix = config.commands.prefix == null ? "!" : config.commands.prefix;
		String trimmed = content.trim();
		if (prefix.isEmpty() || !trimmed.startsWith(prefix)) {
			return false;
		}
		String command = trimmed.substring(prefix.length()).trim();
		boolean known = false;
		for (String name : config.commands.players) {
			if (name != null && !name.isBlank() && command.equalsIgnoreCase(name.trim())) {
				known = true;
				break;
			}
		}
		if (!known) {
			return false;
		}
		long now = System.currentTimeMillis();
		pendingStatus.values().removeIf(p -> now - p.createdAt() > STATUS_TIMEOUT_MS);
		Sinks.Minecraft mc = minecraft;
		for (BridgeConfig.Link link : links) {
			if (mc != null && mc.isConnected(link.server)) {
				String requestId = UUID.randomUUID().toString();
				pendingStatus.put(requestId, new PendingStatus(link, now));
				mc.send(link.server, new Message.StatusRequest(requestId));
			} else {
				String template = config.commands.serverOffline;
				if (template != null && !template.isBlank()) {
					fluxer.post(link, TextFormat.fill(template,
							Map.of("server", TextFormat.escapeMarkdown(config.displayName(link.server)))));
				}
			}
		}
		return true;
	}

	private void maybeSendOfflineNotice(BridgeConfig.Link link) {
		if (link.offlineNotice == null || link.offlineNotice.isBlank()) {
			return;
		}
		String key = link.channelId + "/" + link.server;
		long now = System.currentTimeMillis();
		Long last = offlineNoticeAt.get(key);
		if (last != null && now - last < link.offlineNoticeCooldownMinutes * 60_000L) {
			return;
		}
		offlineNoticeAt.put(key, now);
		fluxer.post(link, link.offlineNotice);
	}

	// ----------------------------------------------------------------- helpers

	private List<BridgeConfig.Link> linksFor(String serverId) {
		List<BridgeConfig.Link> result = new ArrayList<>();
		for (BridgeConfig.Link link : config.links) {
			if (serverId.equals(link.server)) {
				result.add(link);
			}
		}
		return result;
	}

	private static String displayName(JsonObject msg, JsonObject author) {
		if (msg.has("member") && msg.get("member").isJsonObject()) {
			String nick = str(msg.getAsJsonObject("member"), "nick");
			if (nick != null && !nick.isBlank()) {
				return TextFormat.stripControl(nick);
			}
		}
		String global = str(author, "global_name");
		if (global != null && !global.isBlank()) {
			return TextFormat.stripControl(global);
		}
		String username = str(author, "username");
		return username == null ? "Unknown" : TextFormat.stripControl(username);
	}

	private static Map<String, String> mentionNames(JsonObject msg) {
		Map<String, String> names = new HashMap<>();
		JsonElement mentions = msg.get("mentions");
		if (mentions != null && mentions.isJsonArray()) {
			for (JsonElement el : mentions.getAsJsonArray()) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject user = el.getAsJsonObject();
				String id = str(user, "id");
				String global = str(user, "global_name");
				String name = global != null && !global.isBlank() ? global : str(user, "username");
				if (id != null && name != null) {
					names.put(id, name);
				}
			}
		}
		return names;
	}

	private static void appendAttachments(JsonObject msg, StringBuilder text) {
		JsonElement attachments = msg.get("attachments");
		if (attachments != null && attachments.isJsonArray()) {
			for (JsonElement el : attachments.getAsJsonArray()) {
				if (!el.isJsonObject()) {
					continue;
				}
				String type = str(el.getAsJsonObject(), "content_type");
				String file = str(el.getAsJsonObject(), "filename");
				if (type != null && type.startsWith("image/")) {
					text.append(" [image]");
				} else if (type != null && type.startsWith("video/")) {
					text.append(" [video]");
				} else {
					text.append(" [file").append(file != null ? ": " + TextFormat.stripControl(file) : "").append(']');
				}
			}
		}
		JsonElement stickers = msg.get("stickers");
		if (stickers instanceof JsonArray arr && !arr.isEmpty()) {
			text.append(" [sticker]");
		}
	}

	private static String clean(String value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String cleaned = TextFormat.stripControl(value).trim();
		return cleaned.isEmpty() ? fallback : TextFormat.truncate(cleaned, TextFormat.FLUXER_MAX_LENGTH);
	}

	static String str(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	private static boolean bool(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		return el != null && !el.isJsonNull() && el.getAsBoolean();
	}
}
