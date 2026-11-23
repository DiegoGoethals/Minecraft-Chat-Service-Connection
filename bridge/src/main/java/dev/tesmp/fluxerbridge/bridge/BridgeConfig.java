package dev.tesmp.fluxerbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bridge's configuration file (JSON). {@code ${NAME}} anywhere in the file
 * is replaced with the environment variable NAME, so secrets can live in a
 * {@code .env} file instead of the config. See {@code docs/bridge.example.json}.
 */
public final class BridgeConfig {
	public Fluxer fluxer = new Fluxer();
	public Listen listen = new Listen();
	public Map<String, Server> servers = new LinkedHashMap<>();
	public List<Link> links = new ArrayList<>();
	public Commands commands = new Commands();

	public static final class Fluxer {
		/** Any Fluxer instance origin; endpoints are discovered from it. */
		public String instance = "https://fluxer.app";
		public String token;
		/** Ignore messages written by other bots. */
		public boolean ignoreBots = true;
		/** Ignore messages posted by webhooks (ours are always ignored). */
		public boolean ignoreWebhooks = true;
	}

	public static final class Listen {
		public String host = "0.0.0.0";
		public int port = dev.tesmp.fluxerbridge.protocol.Protocol.DEFAULT_PORT;
	}

	public static final class Server {
		public String secret;
		/** Used in messages such as "TESMP is online". Defaults to the server id. */
		public String displayName;
	}

	/** Connects one Minecraft server to one Fluxer channel. */
	public static final class Link {
		public String server;
		public String channelId;
		public boolean toFluxer = true;
		public boolean toMinecraft = true;
		/** Routing tags sent to Fluxer; "*" means everything the server sends. */
		public List<String> tags = new ArrayList<>(List.of("*"));
		/** "webhook" (player name + skin avatar) or "bot" (plain bot messages). */
		public String style = "webhook";
		/**
		 * Name of the webhook to post through. An existing webhook with this name
		 * in the channel is reused; one is only created when none exists.
		 */
		public String webhookName = "Minecraft Bridge";
		/** Avatar for player messages in webhook style. {name} and {uuid} are filled in. */
		public String avatarUrl = "https://mc-heads.net/avatar/{uuid}/64";
		/** Bot style: format for player chat. Placeholders: {server} {author} {text}. */
		public String chatFormat = "**[{server}] {author}:** {text}";
		/** Format for server events (joins, deaths, datapack messages...). Placeholders: {server} {text}. */
		public String eventFormat = "*{text}*";
		/** Shown in Minecraft in front of messages from this channel, e.g. "[Fluxer]". */
		public String minecraftLabel = "Fluxer";
		/** Posted when someone chats while the server is offline. Empty disables it. */
		public String offlineNotice = "The Minecraft server is offline right now, so this message was not delivered.";
		/** Minimum minutes between two offline notices in the same channel. */
		public int offlineNoticeCooldownMinutes = 10;

		public boolean acceptsTag(String tag) {
			return tags == null || tags.contains("*") || tags.contains(tag);
		}

		public boolean useWebhook() {
			return "webhook".equalsIgnoreCase(style);
		}
	}

	public static final class Commands {
		public boolean enabled = true;
		public String prefix = "!";
		/** Names that trigger the player list, e.g. "!players". */
		public List<String> players = new ArrayList<>(List.of("players", "online", "list"));
		/** Reply when players are online. Placeholders: {server} {count} {max} {players}. */
		public String playersOnline = "**{server}** — {count}/{max} online: {players}";
		/** Reply when the server is up but empty. Placeholders: {server} {max}. */
		public String playersNone = "**{server}** is online, but nobody is playing right now.";
		/** Reply when the server is not connected to the bridge. Placeholder: {server}. */
		public String serverOffline = "**{server}** is offline.";
	}

	// ------------------------------------------------------------------ loading

	private static final Gson GSON = new GsonBuilder().create();
	private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

	public static BridgeConfig load(Path path) throws IOException {
		try {
			return parse(Files.readString(path), System::getenv);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(path.toAbsolutePath() + ": " + e.getMessage(), e);
		}
	}

	public static BridgeConfig parse(String json, UnaryOperator<String> env) {
		String substituted = substitute(json, env);
		BridgeConfig config;
		try {
			config = GSON.fromJson(substituted, BridgeConfig.class);
		} catch (JsonParseException e) {
			throw new IllegalArgumentException("Config is not valid JSON: " + e.getMessage(), e);
		}
		if (config == null) {
			throw new IllegalArgumentException("Config file is empty");
		}
		config.validate();
		return config;
	}

	static String substitute(String text, UnaryOperator<String> env) {
		Matcher m = ENV.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String value = env.apply(m.group(1));
			if (value == null) {
				throw new IllegalArgumentException("config uses ${" + m.group(1) + "} but that environment variable is not set. "
						+ "Set it (for example in .env next to docker-compose.yml) or write the value into the config directly");
			}
			// Values land inside JSON strings, so escape them.
			String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
			m.appendReplacement(out, Matcher.quoteReplacement(escaped));
		}
		m.appendTail(out);
		return out.toString();
	}

	void validate() {
		List<String> problems = new ArrayList<>();
		if (fluxer == null) {
			problems.add("\"fluxer\" section is missing");
		} else {
			if (isBlank(fluxer.token)) {
				problems.add("fluxer.token is empty. Put the bot token there, or \"${FLUXER_TOKEN}\" to read it from the environment");
			}
			if (isBlank(fluxer.instance)) {
				problems.add("fluxer.instance is empty. Use your Fluxer instance URL, e.g. \"https://fluxer.app\"");
			}
		}
		if (listen == null) {
			listen = new Listen();
		}
		if (isBlank(listen.host)) {
			problems.add("listen.host is empty. Use \"0.0.0.0\" to accept connections on every interface");
		}
		if (listen.port < 1 || listen.port > 65535) {
			problems.add("listen.port must be between 1 and 65535");
		}
		if (commands == null) {
			commands = new Commands();
		}
		if (commands.players == null) {
			commands.players = new ArrayList<>();
		}
		if (servers == null || servers.isEmpty()) {
			problems.add("\"servers\" is empty. Add one entry per Minecraft server, keyed by the serverId in its config/fluxerbridge.json");
		} else {
			servers.forEach((id, server) -> {
				if (server == null || isBlank(server.secret)) {
					problems.add("servers." + id + ".secret is empty. Copy \"secret\" from that server's config/fluxerbridge.json");
				} else if (server.secret.length() < 16) {
					problems.add("servers." + id + ".secret is too short (use at least 16 random characters, e.g. the one the mod generated)");
				}
			});
		}
		if (links == null || links.isEmpty()) {
			problems.add("\"links\" is empty. Add a link to connect a server to a Fluxer channel");
		} else {
			for (int i = 0; i < links.size(); i++) {
				Link link = links.get(i);
				if (link == null) {
					problems.add("links[" + i + "] is empty");
					continue;
				}
				if (isBlank(link.server) || servers == null || !servers.containsKey(link.server)) {
					problems.add("links[" + i + "].server \"" + link.server + "\" is not one of the configured servers "
							+ (servers == null ? "[]" : servers.keySet()));
				}
				if (isBlank(link.channelId) || !link.channelId.matches("\\d+")) {
					problems.add("links[" + i + "].channelId must be the numeric channel id, not the channel name");
				}
				if (!"webhook".equalsIgnoreCase(link.style) && !"bot".equalsIgnoreCase(link.style)) {
					problems.add("links[" + i + "].style must be \"webhook\" or \"bot\"");
				}
				if (link.useWebhook() && (isBlank(link.webhookName) || link.webhookName.strip().length() > 80)) {
					problems.add("links[" + i + "].webhookName must be 1-80 characters");
				}
				if (link.offlineNoticeCooldownMinutes < 0) {
					problems.add("links[" + i + "].offlineNoticeCooldownMinutes can't be negative");
				}
			}
		}
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException("invalid bridge config (docs/bridge.example.json has a working example):\n  - "
					+ String.join("\n  - ", problems));
		}
	}

	public String displayName(String serverId) {
		Server s = servers.get(serverId);
		return s == null || isBlank(s.displayName) ? serverId : s.displayName;
	}

	static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
