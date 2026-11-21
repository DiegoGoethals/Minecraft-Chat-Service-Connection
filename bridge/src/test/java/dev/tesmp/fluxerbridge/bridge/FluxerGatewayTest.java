package dev.tesmp.fluxerbridge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tesmp.fluxerbridge.bridge.fluxer.FluxerGateway;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs {@link FluxerGateway} against a fake Gateway that follows the documented opcodes. */
class FluxerGatewayTest {
	final BlockingQueue<JsonObject> fromClient = new LinkedBlockingQueue<>();
	final BlockingQueue<WebSocket> connections = new LinkedBlockingQueue<>();
	final BlockingQueue<String> ready = new LinkedBlockingQueue<>();
	final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
	WebSocketServer server;
	FluxerGateway gateway;

	@AfterEach
	void tearDown() throws Exception {
		if (gateway != null) {
			gateway.close();
		}
		if (server != null) {
			server.stop(500);
		}
	}

	JsonObject next() throws InterruptedException {
		JsonObject o = fromClient.poll(5, TimeUnit.SECONDS);
		assertNotNull(o, "no payload from client");
		return o;
	}

	/** Next payload that isn't a heartbeat. */
	JsonObject nextCommand() throws InterruptedException {
		return nextCommandWithin(5);
	}

	@Test
	void identifiesReceivesMessagesAndResumes() throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		server = new WebSocketServer(new InetSocketAddress("127.0.0.1", port)) {
			@Override
			public void onOpen(WebSocket conn, ClientHandshake handshake) {
				assertTrue(handshake.getResourceDescriptor().contains("v=1"), handshake.getResourceDescriptor());
				connections.add(conn);
				conn.send("{\"op\":10,\"d\":{\"heartbeat_interval\":200}}");
			}

			@Override
			public void onMessage(WebSocket conn, String message) {
				JsonObject payload = JsonParser.parseString(message).getAsJsonObject();
				if (payload.get("op").getAsInt() == 1) {
					conn.send("{\"op\":11}");
				}
				fromClient.add(payload);
			}

			@Override
			public void onClose(WebSocket conn, int code, String reason, boolean remote) {
			}

			@Override
			public void onError(WebSocket conn, Exception ex) {
			}

			@Override
			public void onStart() {
			}
		};
		server.setReuseAddr(true);
		server.start();
		Thread.sleep(300);

		gateway = new FluxerGateway(HttpClient.newHttpClient(), "ws://127.0.0.1:" + port, "1.token", new FluxerGateway.Listener() {
			@Override
			public void onReady(String selfUserId, String selfName) {
				ready.add(selfUserId);
			}

			@Override
			public void onMessageCreate(JsonObject message) {
				messages.add(message);
			}
		});
		gateway.start();

		// Identify with the raw token
		WebSocket first = connections.poll(5, TimeUnit.SECONDS);
		assertNotNull(first);
		JsonObject identify = nextCommand();
		assertEquals(2, identify.get("op").getAsInt());
		assertEquals("1.token", identify.getAsJsonObject("d").get("token").getAsString());

		// READY + a message
		first.send("{\"op\":0,\"s\":1,\"t\":\"READY\",\"d\":{\"session_id\":\"sess\",\"user\":{\"id\":\"999\",\"username\":\"bridge\"}}}");
		assertEquals("999", ready.poll(5, TimeUnit.SECONDS));
		first.send("{\"op\":0,\"s\":2,\"t\":\"MESSAGE_CREATE\",\"d\":{\"id\":\"m\",\"content\":\"hi\"}}");
		assertEquals("hi", messages.poll(5, TimeUnit.SECONDS).get("content").getAsString());

		// Heartbeats carry the last sequence
		JsonObject hb;
		long deadline = System.currentTimeMillis() + 5000;
		do {
			assertTrue(System.currentTimeMillis() < deadline, "no heartbeat with sequence 2");
			hb = next();
		} while (hb.get("op").getAsInt() != 1 || hb.get("d").isJsonNull() || hb.get("d").getAsLong() != 2);

		// Reconnect request -> new socket -> Resume with session and sequence
		first.send("{\"op\":7}");
		WebSocket second = connections.poll(10, TimeUnit.SECONDS);
		assertNotNull(second, "client did not reconnect");
		JsonObject resume = nextCommand();
		assertEquals(6, resume.get("op").getAsInt());
		assertEquals("sess", resume.getAsJsonObject("d").get("session_id").getAsString());
		assertEquals(2, resume.getAsJsonObject("d").get("seq").getAsLong());

		// Invalid session -> fresh identify on the same socket
		second.send("{\"op\":9,\"d\":false}");
		JsonObject reidentify = nextCommandWithin(8);
		assertEquals(2, reidentify.get("op").getAsInt());
	}

	JsonObject nextCommandWithin(int seconds) throws InterruptedException {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (System.currentTimeMillis() < deadline) {
			JsonObject o = fromClient.poll(500, TimeUnit.MILLISECONDS);
			if (o != null && o.get("op").getAsInt() != 1) {
				return o;
			}
		}
		throw new AssertionError("no command within " + seconds + "s");
	}
}
