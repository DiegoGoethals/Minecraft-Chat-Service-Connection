package dev.tesmp.fluxerbridge.bridge;

import dev.tesmp.fluxerbridge.protocol.Message;
import dev.tesmp.fluxerbridge.protocol.client.BridgeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real {@link MinecraftHub} on localhost and connects the real
 * {@link BridgeClient} the Fabric mod uses. Only Fluxer is faked.
 */
class EndToEndTest {
	final BlockingQueue<String> fluxerPosts = new LinkedBlockingQueue<>();
	final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
	final List<AutoCloseable> cleanup = new CopyOnWriteArrayList<>();
	BridgeConfig config;
	Router router;
	MinecraftHub hub;
	int port;

	@AfterEach
	void tearDown() throws Exception {
		for (AutoCloseable c : cleanup) {
			c.close();
		}
	}

	void startHub() throws Exception {
		config = BridgeConfig.parse(BridgeConfigTest.VALID, BridgeConfigTest.ENV::get);
		config.links.getFirst().style = "bot";
		router = new Router(config, new Sinks.Fluxer() {
			@Override
			public void post(BridgeConfig.Link link, String content) {
				fluxerPosts.add(content);
			}

			@Override
			public void postAs(BridgeConfig.Link link, String username, String avatarUrl, String content, String fallback) {
				fluxerPosts.add(username + ": " + content);
			}

			@Override
			public boolean isOwnWebhook(String webhookId) {
				return false;
			}
		});
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		hub = new MinecraftHub(new InetSocketAddress("127.0.0.1", port), config, router);
		router.setMinecraft(hub);
		hub.start();
		cleanup.add(hub::shutdown);
		Thread.sleep(300);
	}

	BridgeClient client(String secret) {
		BridgeClient client = new BridgeClient(URI.create("ws://127.0.0.1:" + port), "tesmp", secret, "test", received::add);
		cleanup.addFirst(client);
		client.start();
		return client;
	}

	static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out");
			}
			Thread.sleep(20);
		}
	}

	@Test
	void chatFlowsBothWays() throws Exception {
		startHub();
		BridgeClient client = client(BridgeConfigTest.ENV.get("TESMP_SECRET"));
		waitFor(client::isConnected);
		waitFor(() -> hub.isConnected("tesmp"));

		// Minecraft -> Fluxer
		client.send(new Message.Chat("1", "chat", "Steve", "uuid", "hello from minecraft", null));
		assertEquals("**[TESMP] Steve:** hello from minecraft", fluxerPosts.poll(5, TimeUnit.SECONDS));

		// Fluxer -> Minecraft
		router.onMessageCreate(RouterTest.userMessage("hello from fluxer"));
		Message.Chat chat = assertInstanceOf(Message.Chat.class, received.poll(5, TimeUnit.SECONDS));
		assertEquals("Victoria", chat.author());
		assertEquals("hello from fluxer", chat.text());

		// !players round trip
		router.onMessageCreate(RouterTest.userMessage("!players"));
		Message.StatusRequest req = assertInstanceOf(Message.StatusRequest.class, received.poll(5, TimeUnit.SECONDS));
		client.send(new Message.Status(req.requestId(), List.of("Steve"), 10, "26.3"));
		assertEquals("**TESMP** — 1/10 online: Steve", fluxerPosts.poll(5, TimeUnit.SECONDS));
	}

	@Test
	void wrongSecretIsRefused() throws Exception {
		startHub();
		BridgeClient client = client("definitely-the-wrong-secret");
		Thread.sleep(1500);
		assertFalse(client.isConnected());
		assertFalse(hub.isConnected("tesmp"));
	}

	@Test
	void messagesSentWhileOfflineAreDeliveredAfterConnecting() throws Exception {
		startHub();
		// Stop the hub, queue a message, then start a new hub on the same port.
		hub.shutdown();
		BridgeClient client = client(BridgeConfigTest.ENV.get("TESMP_SECRET"));
		client.send(new Message.Chat("1", "chat", "Steve", "uuid", "queued while offline", null));
		Thread.sleep(500);
		assertFalse(client.isConnected());

		hub = new MinecraftHub(new InetSocketAddress("127.0.0.1", port), config, router);
		router.setMinecraft(hub);
		hub.start();
		cleanup.add(hub::shutdown);

		// The client retries with backoff (1s, 2s, 4s ...).
		String post = fluxerPosts.poll(10, TimeUnit.SECONDS);
		assertNotNull(post, "queued message was not delivered after reconnect");
		assertEquals("**[TESMP] Steve:** queued while offline", post);
		assertTrue(client.isConnected());
	}
}
