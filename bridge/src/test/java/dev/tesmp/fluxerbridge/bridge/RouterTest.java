package dev.tesmp.fluxerbridge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tesmp.fluxerbridge.protocol.EventKind;
import dev.tesmp.fluxerbridge.protocol.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterTest {
	static final String CHANNEL = "1234567890";
	static final String BOT_ID = "999";

	record Post(String channelId, String username, String avatar, String content) {
	}

	final List<Post> posts = new ArrayList<>();
	final List<Message> toMinecraft = new ArrayList<>();
	boolean serverConnected = true;
	BridgeConfig config;
	Router router;

	@BeforeEach
	void setUp() {
		config = BridgeConfig.parse(BridgeConfigTest.VALID, BridgeConfigTest.ENV::get);
		router = newRouter(config);
	}

	Router newRouter(BridgeConfig cfg) {
		Router r = new Router(cfg, new Sinks.Fluxer() {
			@Override
			public void post(BridgeConfig.Link link, String content) {
				posts.add(new Post(link.channelId, null, null, content));
			}

			@Override
			public void postAs(BridgeConfig.Link link, String username, String avatarUrl, String content, String fallback) {
				posts.add(new Post(link.channelId, username, avatarUrl, content));
			}

			@Override
			public boolean isOwnWebhook(String webhookId) {
				return "777".equals(webhookId);
			}
		});
		r.setSelfUserId(BOT_ID);
		r.setMinecraft(new Sinks.Minecraft() {
			@Override
			public boolean isConnected(String serverId) {
				return serverConnected;
			}

			@Override
			public void send(String serverId, Message message) {
				toMinecraft.add(message);
			}
		});
		return r;
	}

	static JsonObject fluxerMessage(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	static JsonObject userMessage(String content) {
		JsonObject msg = fluxerMessage("""
				{"id":"m1","channel_id":"%s","type":0,
				 "author":{"id":"42","username":"vic","global_name":"Victoria"},
				 "member":{"nick":null},
				 "mentions":[{"id":"43","username":"pharaoh","global_name":"Pharaoh Nuff"}]}
				""".formatted(CHANNEL));
		msg.addProperty("content", content);
		return msg;
	}

	@Test
	void minecraftChatGoesToWebhookWithPlayerNameAndAvatar() {
		router.onMinecraftMessage("tesmp", new Message.Chat("1", "chat", "Steve", "uuid-1", "hi *all*", null));
		assertEquals(1, posts.size());
		Post p = posts.getFirst();
		assertEquals(CHANNEL, p.channelId());
		assertEquals("Steve", p.username());
		assertEquals("https://mc-heads.net/avatar/uuid-1/64", p.avatar());
		assertEquals("hi \\*all\\*", p.content());
	}

	@Test
	void botStyleUsesChatFormat() {
		config.links.getFirst().style = "bot";
		router.onMinecraftMessage("tesmp", new Message.Chat("1", "chat", "Steve", "u", "hello", null));
		assertEquals("**[TESMP] Steve:** hello", posts.getFirst().content());
		assertEquals(null, posts.getFirst().username());
	}

	@Test
	void eventsUseEventFormatAndTagFilter() {
		config.links.getFirst().tags = List.of("chat", "datapack");
		router.onMinecraftMessage("tesmp", new Message.ServerEvent("1", EventKind.DATAPACK, "datapack", null, null, "A villager died at 1 2 3"));
		router.onMinecraftMessage("tesmp", new Message.ServerEvent("2", EventKind.JOIN, "joins", "Steve", null, "Steve joined the game"));
		assertEquals(1, posts.size());
		assertEquals("*A villager died at 1 2 3*", posts.getFirst().content());
		assertEquals("TESMP", posts.getFirst().username());
	}

	@Test
	void unknownServerGoesNowhere() {
		router.onMinecraftMessage("other", new Message.Chat("1", "chat", "Steve", "u", "hello", null));
		assertTrue(posts.isEmpty());
	}

	@Test
	void fluxerMessageReachesMinecraftWithDisplayNameAndMentions() {
		router.onMessageCreate(userMessage("hey <@43>\ncome online"));
		assertEquals(1, toMinecraft.size());
		Message.Chat chat = assertInstanceOf(Message.Chat.class, toMinecraft.getFirst());
		assertEquals("Victoria", chat.author());
		assertEquals("hey @Pharaoh Nuff come online", chat.text());
		assertEquals("Fluxer", chat.origin());
	}

	@Test
	void nicknameWinsOverGlobalName() {
		JsonObject msg = userMessage("hi");
		msg.getAsJsonObject("member").addProperty("nick", "Vic");
		router.onMessageCreate(msg);
		assertEquals("Vic", ((Message.Chat) toMinecraft.getFirst()).author());
	}

	@Test
	void neverRelaysOwnMessagesWebhooksOrBots() {
		JsonObject own = userMessage("loop?");
		own.getAsJsonObject("author").addProperty("id", BOT_ID);
		router.onMessageCreate(own);

		JsonObject ownWebhook = userMessage("loop?");
		ownWebhook.addProperty("webhook_id", "777");
		router.onMessageCreate(ownWebhook);

		JsonObject otherWebhook = userMessage("other bridge");
		otherWebhook.addProperty("webhook_id", "888");
		router.onMessageCreate(otherWebhook);

		JsonObject bot = userMessage("beep");
		bot.getAsJsonObject("author").addProperty("bot", true);
		router.onMessageCreate(bot);

		assertTrue(toMinecraft.isEmpty(), toMinecraft.toString());
	}

	@Test
	void otherChannelsAndSystemMessagesAreIgnored() {
		JsonObject other = userMessage("hi");
		other.addProperty("channel_id", "555");
		router.onMessageCreate(other);

		JsonObject join = userMessage("");
		join.addProperty("type", 7);
		router.onMessageCreate(join);

		assertTrue(toMinecraft.isEmpty());
	}

	@Test
	void attachmentsBecomePlaceholders() {
		JsonObject msg = userMessage("look");
		msg.add("attachments", JsonParser.parseString("[{\"filename\":\"base.png\",\"content_type\":\"image/png\"}]"));
		router.onMessageCreate(msg);
		assertEquals("look [image]", ((Message.Chat) toMinecraft.getFirst()).text());
	}

	@Test
	void offlineNoticeIsRateLimited() {
		serverConnected = false;
		router.onMessageCreate(userMessage("anyone?"));
		router.onMessageCreate(userMessage("hello??"));
		assertTrue(toMinecraft.isEmpty());
		assertEquals(1, posts.size());
		assertTrue(posts.getFirst().content().contains("offline"));
	}

	@Test
	void playersCommandAsksServerAndPostsAnswer() {
		router.onMessageCreate(userMessage("!players"));
		Message.StatusRequest request = assertInstanceOf(Message.StatusRequest.class, toMinecraft.getFirst());
		router.onMinecraftMessage("tesmp", new Message.Status(request.requestId(), List.of("Steve", "Alex_"), 20, "26.3"));
		assertEquals(1, posts.size());
		assertEquals("**TESMP** — 2/20 online: Steve, Alex\\_", posts.getFirst().content());
		// Answer only once
		router.onMinecraftMessage("tesmp", new Message.Status(request.requestId(), List.of(), 20, "26.3"));
		assertEquals(1, posts.size());
	}

	@Test
	void unknownCommandIsRelayedAsChat() {
		router.onMessageCreate(userMessage("!hello"));
		assertEquals("!hello", ((Message.Chat) toMinecraft.getFirst()).text());
	}

	@Test
	void toMinecraftFalseBlocksIncoming() {
		config.links.getFirst().toMinecraft = false;
		router.onMessageCreate(userMessage("hi"));
		assertTrue(toMinecraft.isEmpty());
		assertTrue(posts.isEmpty(), "no offline notice either");
	}
}
