package dev.tesmp.fluxerbridge.bridge;

import dev.tesmp.fluxerbridge.bridge.fluxer.Version;
import dev.tesmp.fluxerbridge.protocol.Message;
import dev.tesmp.fluxerbridge.protocol.MessageCodec;
import dev.tesmp.fluxerbridge.protocol.Protocol;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.DefaultExtension;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket server the Fabric mods connect to. Each connection must send a
 * valid {@link Message.Hello} within 10 seconds; after that it represents one
 * configured server id. A new connection for the same id replaces the old one.
 */
public final class MinecraftHub extends WebSocketServer implements Sinks.Minecraft {
	private static final Logger LOG = LoggerFactory.getLogger("MinecraftHub");
	private static final int CLOSE_AUTH_FAILED = 4001;
	private static final int CLOSE_REPLACED = 4002;
	private static final long AUTH_TIMEOUT_SECONDS = 10;

	private final BridgeConfig config;
	private final Router router;
	private final Map<String, WebSocket> servers = new ConcurrentHashMap<>();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "hub-timer");
		t.setDaemon(true);
		return t;
	});

	public MinecraftHub(InetSocketAddress address, BridgeConfig config, Router router) {
		// Same as the library default (no extensions, accept any subprotocol), but with a frame size limit.
		super(address, Collections.singletonList(new Draft_6455(
				Collections.singletonList(new DefaultExtension()),
				Collections.singletonList(new org.java_websocket.protocols.Protocol("")),
				Protocol.MAX_FRAME_CHARS * 4)));
		this.config = config;
		this.router = router;
		setReuseAddr(true);
		setConnectionLostTimeout(60);
	}

	@Override
	public void onStart() {
		LOG.info("Waiting for Minecraft servers on port {}", getPort());
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		timer.schedule(() -> {
			if (conn.isOpen() && conn.getAttachment() == null) {
				conn.close(CLOSE_AUTH_FAILED, "no hello received");
			}
		}, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void onMessage(WebSocket conn, String text) {
		Message message;
		try {
			message = MessageCodec.decode(text);
		} catch (MessageCodec.ProtocolException e) {
			LOG.warn("Invalid message from {}: {}", describe(conn), e.getMessage());
			if (conn.getAttachment() == null) {
				conn.close(CLOSE_AUTH_FAILED, "invalid hello");
			}
			return;
		}

		String serverId = conn.getAttachment();
		if (serverId == null) {
			authenticate(conn, message);
			return;
		}
		switch (message) {
			case Message.Ping ping -> conn.send(MessageCodec.encode(new Message.Pong(ping.time())));
			case Message.Pong ignored -> {
			}
			case Message.Hello ignored -> {
			}
			default -> {
				try {
					router.onMinecraftMessage(serverId, message);
				} catch (RuntimeException e) {
					LOG.error("Error routing message from {}", serverId, e);
				}
			}
		}
	}

	private void authenticate(WebSocket conn, Message message) {
		if (!(message instanceof Message.Hello hello)) {
			reject(conn, "expected hello");
			return;
		}
		BridgeConfig.Server server = hello.serverId() == null ? null : config.servers.get(hello.serverId());
		if (server == null) {
			LOG.warn("Refused connection from {}: server id \"{}\" is not in the bridge config. "
					+ "Add it under \"servers\" in the bridge config (configured: {}), or change serverId in that server's config/fluxerbridge.json.",
					describe(conn), hello.serverId(), config.servers.keySet());
			reject(conn, "unknown server id or wrong secret");
			return;
		}
		if (hello.secret() == null || !constantTimeEquals(server.secret, hello.secret())) {
			LOG.warn("Refused connection from {}: wrong secret for server \"{}\". "
					+ "servers.{}.secret in the bridge config must equal \"secret\" in that server's config/fluxerbridge.json.",
					describe(conn), hello.serverId(), hello.serverId());
			reject(conn, "unknown server id or wrong secret");
			return;
		}
		if (hello.protocolVersion() != Protocol.VERSION) {
			reject(conn, "protocol version " + hello.protocolVersion() + " is not supported, the bridge speaks version " + Protocol.VERSION);
			return;
		}
		conn.setAttachment(hello.serverId());
		WebSocket previous = servers.put(hello.serverId(), conn);
		if (previous != null && previous != conn) {
			previous.close(CLOSE_REPLACED, "replaced by a new connection");
		}
		conn.send(MessageCodec.encode(new Message.Welcome(Version.get(), Protocol.VERSION)));
		LOG.info("Server \"{}\" authenticated from {} (mod {})", hello.serverId(), describe(conn), hello.softwareVersion());
		router.onServerConnected(hello.serverId());
	}

	private void reject(WebSocket conn, String reason) {
		conn.send(MessageCodec.encode(new Message.Reject(reason)));
		conn.close(CLOSE_AUTH_FAILED, reason);
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		String serverId = conn.getAttachment();
		if (serverId != null && servers.remove(serverId, conn)) {
			router.onServerDisconnected(serverId);
		}
	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		if (conn == null) {
			if (ex instanceof java.net.BindException) {
				LOG.error("Can't listen on {}:{} ({}). Another program may be using the port; "
						+ "change listen.host / listen.port in the bridge config (and bridgeUrl in the mod config to match).",
						config.listen.host, config.listen.port, ex.getMessage());
				return;
			}
			LOG.error("Minecraft hub error", ex);
		} else {
			LOG.warn("Connection error from {}: {}", describe(conn), ex.toString());
		}
	}

	@Override
	public boolean isConnected(String serverId) {
		WebSocket conn = servers.get(serverId);
		return conn != null && conn.isOpen();
	}

	@Override
	public void send(String serverId, Message message) {
		WebSocket conn = servers.get(serverId);
		if (conn != null && conn.isOpen()) {
			conn.send(MessageCodec.encode(message));
		}
	}

	public void shutdown() {
		timer.shutdownNow();
		try {
			stop(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static String describe(WebSocket conn) {
		InetSocketAddress addr = conn.getRemoteSocketAddress();
		return addr == null ? "unknown" : addr.getAddress().getHostAddress();
	}
}
