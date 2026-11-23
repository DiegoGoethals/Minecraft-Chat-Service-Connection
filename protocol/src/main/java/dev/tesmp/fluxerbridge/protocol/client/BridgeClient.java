package dev.tesmp.fluxerbridge.protocol.client;

import dev.tesmp.fluxerbridge.protocol.Message;
import dev.tesmp.fluxerbridge.protocol.MessageCodec;
import dev.tesmp.fluxerbridge.protocol.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client used by a Minecraft server to talk to the bridge.
 *
 * <ul>
 *   <li>Connects, authenticates with {@link Message.Hello} and waits for
 *       {@link Message.Welcome}.</li>
 *   <li>Reconnects forever with exponential backoff (1s, 2s, 4s ... 60s).</li>
 *   <li>Messages sent while disconnected are queued (bounded, and dropped
 *       after {@link #MAX_QUEUE_AGE_MS}) and flushed after reconnecting.</li>
 *   <li>Sends a ping every 20 seconds and reconnects when the bridge stops
 *       answering.</li>
 * </ul>
 *
 * <p>All state lives on one private thread, so the class is safe to call from
 * any thread. {@link Listener} callbacks run on that private thread too:
 * hand work over to the Minecraft server thread yourself.
 */
public final class BridgeClient implements AutoCloseable {
	public interface Listener {
		/** A message from the bridge other than the handshake, ping and pong. */
		void onMessage(Message message);

		default void onConnected() {
		}

		default void onDisconnected(String reason) {
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger("FluxerBridge");

	static final int MAX_QUEUE = 200;
	static final long MAX_QUEUE_AGE_MS = 5 * 60 * 1000;
	private static final long PING_INTERVAL_MS = 20_000;
	private static final long PONG_TIMEOUT_MS = 60_000;
	private static final long MAX_BACKOFF_MS = 60_000;
	private static final long SEND_TIMEOUT_MS = 10_000;

	private record Pending(Message message, long queuedAt) {
	}

	private final URI uri;
	private final String serverId;
	private final String secret;
	private final String softwareVersion;
	private final Listener listener;
	private final ScheduledExecutorService exec;
	private final HttpClient http;

	// Everything below is only touched on the exec thread (except the volatiles).
	private final Deque<Pending> queue = new ArrayDeque<>();
	private WebSocket socket;
	private int generation;
	private int attempt;
	private boolean rejected;
	private boolean droppedWarningShown;
	private long lastPongAt;
	private ScheduledFuture<?> pingTask;
	private volatile boolean ready;
	private volatile boolean closed;

	public BridgeClient(URI uri, String serverId, String secret, String softwareVersion, Listener listener) {
		String scheme = uri.getScheme();
		if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
			throw new IllegalArgumentException("Bridge URL must start with ws:// or wss://, got: " + uri);
		}
		this.uri = uri;
		this.serverId = serverId;
		this.secret = secret;
		this.softwareVersion = softwareVersion;
		this.listener = listener;
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "FluxerBridge-client");
			t.setDaemon(true);
			return t;
		});
		// Pending reconnects and pings must not delay shutdown.
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		this.exec = executor;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	public void start() {
		exec.execute(this::connect);
	}

	/** True once the bridge accepted us and until the connection drops. */
	public boolean isConnected() {
		return ready;
	}

	/** Sends now when connected, otherwise queues the message. Never blocks. */
	public void send(Message message) {
		if (closed) {
			return;
		}
		exec.execute(() -> {
			if (ready) {
				write(message);
			} else {
				enqueue(message);
			}
		});
	}

	/**
	 * Sends everything already handed to {@link #send}, closes the connection
	 * and stops reconnecting. Blocks for at most a few seconds.
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		exec.execute(() -> {
			closed = true;
			ready = false;
			cancelPing();
			WebSocket ws = socket;
			socket = null;
			generation++;
			if (ws != null) {
				try {
					ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").get(2, TimeUnit.SECONDS);
				} catch (Exception e) {
					ws.abort();
				}
			}
		});
		exec.shutdown();
		try {
			if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
				exec.shutdownNow();
			}
		} catch (InterruptedException e) {
			exec.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- internals

	private void connect() {
		if (closed) {
			return;
		}
		int gen = ++generation;
		LOG.info("Connecting to bridge at {}", uri);
		http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.buildAsync(uri, new SocketListener(gen))
				.whenComplete((ws, err) -> runOnExec(() -> {
					if (gen != generation || closed) {
						if (ws != null) {
							ws.abort();
						}
						return;
					}
					if (err != null) {
						LOG.warn("Could not connect to bridge at {}: {}. Is the bridge running? If it uses another "
								+ "host, container name or port, change bridgeUrl in config/fluxerbridge.json "
								+ "and run /fluxerbridge reload.", uri, rootMessage(err));
						scheduleReconnect();
						return;
					}
					socket = ws;
					lastPongAt = System.currentTimeMillis();
					writeRaw(new Message.Hello(serverId, secret, Protocol.VERSION, softwareVersion));
				}));
	}

	private void handle(int gen, String text) {
		if (gen != generation || closed) {
			return;
		}
		Message message;
		try {
			message = MessageCodec.decode(text);
		} catch (MessageCodec.ProtocolException e) {
			LOG.warn("Ignoring invalid message from bridge: {}", e.getMessage());
			return;
		}
		switch (message) {
			case Message.Welcome welcome -> {
				ready = true;
				rejected = false;
				attempt = 0;
				lastPongAt = System.currentTimeMillis();
				LOG.info("Connected to bridge (bridge {}, protocol {})", welcome.bridgeVersion(), welcome.protocolVersion());
				startPing(gen);
				flushQueue();
				safely(listener::onConnected);
			}
			case Message.Reject reject -> {
				rejected = true;
				LOG.error("Bridge refused this server: {}. serverId and secret in config/fluxerbridge.json must match "
						+ "an entry under \"servers\" in the bridge config; the bridge log says which one is wrong.", reject.reason());
			}
			case Message.Ping ping -> writeRaw(new Message.Pong(ping.time()));
			case Message.Pong ignored -> lastPongAt = System.currentTimeMillis();
			default -> {
				if (ready) {
					safely(() -> listener.onMessage(message));
				}
			}
		}
	}

	private void disconnected(int gen, String reason) {
		if (gen != generation || closed) {
			return;
		}
		generation++; // ignore any further callbacks from this socket
		boolean wasReady = ready;
		ready = false;
		cancelPing();
		WebSocket ws = socket;
		socket = null;
		if (ws != null) {
			ws.abort();
		}
		if (wasReady) {
			LOG.warn("Lost connection to bridge: {}", reason);
			safely(() -> listener.onDisconnected(reason));
		} else if (!rejected) {
			LOG.warn("Bridge connection closed before it was accepted: {}", reason);
		}
		scheduleReconnect();
	}

	private void scheduleReconnect() {
		if (closed) {
			return;
		}
		long delay = rejected ? MAX_BACKOFF_MS : Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt, 6));
		attempt++;
		LOG.info("Reconnecting to bridge in {}s", delay / 1000);
		exec.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
	}

	private void startPing(int gen) {
		cancelPing();
		pingTask = exec.scheduleAtFixedRate(() -> {
			if (gen != generation || !ready) {
				return;
			}
			if (System.currentTimeMillis() - lastPongAt > PONG_TIMEOUT_MS) {
				disconnected(gen, "bridge stopped answering pings");
				return;
			}
			writeRaw(new Message.Ping(System.currentTimeMillis()));
		}, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	private void cancelPing() {
		if (pingTask != null) {
			pingTask.cancel(false);
			pingTask = null;
		}
	}

	private void enqueue(Message message) {
		dropStale();
		if (queue.size() >= MAX_QUEUE) {
			queue.pollFirst();
			if (!droppedWarningShown) {
				droppedWarningShown = true;
				LOG.warn("Bridge is unreachable and the send queue is full; dropping the oldest messages");
			}
		}
		queue.addLast(new Pending(message, System.currentTimeMillis()));
	}

	private void dropStale() {
		long cutoff = System.currentTimeMillis() - MAX_QUEUE_AGE_MS;
		while (!queue.isEmpty() && queue.peekFirst().queuedAt() < cutoff) {
			queue.pollFirst();
		}
	}

	private void flushQueue() {
		dropStale();
		droppedWarningShown = false;
		while (ready && !queue.isEmpty()) {
			write(queue.pollFirst().message());
		}
	}

	/** Sends a message after the handshake, queueing it if the send fails. */
	private void write(Message message) {
		if (!writeRaw(message)) {
			enqueue(message);
		}
	}

	private boolean writeRaw(Message message) {
		WebSocket ws = socket;
		if (ws == null) {
			return false;
		}
		int gen = generation;
		try {
			// The JDK WebSocket allows one outstanding send at a time; waiting
			// here keeps sends ordered and never overlapping.
			ws.sendText(MessageCodec.encode(message), true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (Exception e) {
			disconnected(gen, "send failed: " + rootMessage(e));
			return false;
		}
	}

	private void runOnExec(Runnable task) {
		try {
			exec.execute(task);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// closed
		}
	}

	private static void safely(Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			LOG.error("Error in bridge listener", t);
		}
	}

	private static String rootMessage(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String msg = root.getMessage();
		return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
	}

	private final class SocketListener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buffer = new StringBuilder();

		SocketListener(int gen) {
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (buffer.length() > Protocol.MAX_FRAME_CHARS) {
				buffer.setLength(0);
				runOnExec(() -> disconnected(gen, "message from bridge too large"));
				return null;
			}
			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);
				runOnExec(() -> handle(gen, text));
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			runOnExec(() -> disconnected(gen, "closed by bridge (" + statusCode + (reason.isEmpty() ? "" : " " + reason) + ")"));
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			runOnExec(() -> disconnected(gen, rootMessage(error)));
		}
	}
}
