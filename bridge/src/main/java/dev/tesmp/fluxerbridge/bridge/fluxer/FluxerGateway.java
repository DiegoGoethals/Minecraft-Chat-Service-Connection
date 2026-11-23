package dev.tesmp.fluxerbridge.bridge.fluxer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Fluxer Gateway client: identify, heartbeat, resume and reconnect,
 * and hand MESSAGE_CREATE events to a listener.
 * Docs: https://docs.fluxer.com/gateway/overview/
 */
public final class FluxerGateway implements AutoCloseable {
	public interface Listener {
		void onReady(String selfUserId, String selfName);

		void onMessageCreate(JsonObject message);
	}

	private static final Logger LOG = LoggerFactory.getLogger("FluxerGateway");

	private static final int OP_DISPATCH = 0;
	private static final int OP_HEARTBEAT = 1;
	private static final int OP_IDENTIFY = 2;
	private static final int OP_RESUME = 6;
	private static final int OP_RECONNECT = 7;
	private static final int OP_INVALID_SESSION = 9;
	private static final int OP_HELLO = 10;
	private static final int OP_HEARTBEAT_ACK = 11;

	/** Events the bridge never needs (names from the event-filtering docs). */
	private static final List<String> IGNORED_EVENTS = List.of(
			"TYPING_START", "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE");

	private static final long RESUME_WINDOW_MS = 55_000;
	private static final long MAX_BACKOFF_MS = 60_000;
	private static final long FATAL_RETRY_MS = 5 * 60_000;
	private static final int MAX_EVENT_CHARS = 32 * 1024 * 1024;

	private final HttpClient http;
	private final String gatewayUrl;
	private final String token;
	private final Listener listener;
	private final ScheduledThreadPoolExecutor exec;

	// Only touched on the exec thread.
	private WebSocket socket;
	private int generation;
	private int attempt;
	private ScheduledFuture<?> heartbeatTask;
	private boolean ackReceived;
	private Long sequence;
	private String sessionId;
	private long disconnectedAt;
	private boolean closed;

	public FluxerGateway(HttpClient http, String gatewayUrl, String token, Listener listener) {
		this.http = http;
		this.gatewayUrl = gatewayUrl;
		this.token = token;
		this.listener = listener;
		this.exec = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "fluxer-gateway");
			t.setDaemon(true);
			return t;
		});
		exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
	}

	public void start() {
		exec.execute(this::connect);
	}

	@Override
	public void close() {
		exec.execute(() -> {
			closed = true;
			stopHeartbeat();
			if (socket != null) {
				socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
				socket = null;
			}
		});
		exec.shutdown();
		try {
			exec.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- internals

	private void connect() {
		if (closed) {
			return;
		}
		int gen = ++generation;
		String sep = gatewayUrl.contains("?") ? "&" : "?";
		URI uri = URI.create(gatewayUrl + sep + "v=1&encoding=json");
		LOG.info("Connecting to Fluxer Gateway ({})", uri.getHost());
		http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.buildAsync(uri, new SocketListener(gen))
				.whenComplete((ws, err) -> runOnExec(() -> {
					if (gen != generation || closed) {
						if (ws != null) {
							ws.abort();
						}
						return;
					}
					if (err != null) {
						LOG.warn("Could not connect to Fluxer Gateway: {}", root(err));
						scheduleReconnect(false);
						return;
					}
					socket = ws;
				}));
	}

	private void handle(int gen, String text) {
		if (gen != generation || closed) {
			return;
		}
		JsonObject payload;
		try {
			payload = JsonParser.parseString(text).getAsJsonObject();
		} catch (RuntimeException e) {
			LOG.warn("Ignoring unreadable Gateway payload");
			return;
		}
		int op = payload.get("op").getAsInt();
		switch (op) {
			case OP_HELLO -> {
				long interval = payload.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
				startHeartbeat(gen, interval);
				if (sessionId != null && sequence != null && System.currentTimeMillis() - disconnectedAt < RESUME_WINDOW_MS) {
					resume();
				} else {
					identify();
				}
			}
			case OP_HEARTBEAT_ACK -> ackReceived = true;
			case OP_HEARTBEAT -> sendHeartbeat();
			case OP_RECONNECT -> {
				LOG.info("Gateway asked us to reconnect");
				dropConnection(gen, "reconnect requested", false);
			}
			case OP_INVALID_SESSION -> {
				LOG.info("Gateway session is no longer valid, identifying again");
				sessionId = null;
				sequence = null;
				exec.schedule(() -> {
					if (gen == generation) {
						identify();
					}
				}, 1000 + ThreadLocalRandom.current().nextInt(4000), TimeUnit.MILLISECONDS);
			}
			case OP_DISPATCH -> dispatch(payload);
			default -> {
				// other opcodes are not relevant for a bot
			}
		}
	}

	private void dispatch(JsonObject payload) {
		JsonElement s = payload.get("s");
		if (s != null && !s.isJsonNull()) {
			sequence = s.getAsLong();
		}
		String type = payload.has("t") && !payload.get("t").isJsonNull() ? payload.get("t").getAsString() : "";
		JsonElement d = payload.get("d");
		switch (type) {
			case "READY" -> {
				JsonObject data = d.getAsJsonObject();
				sessionId = data.get("session_id").getAsString();
				JsonObject user = data.getAsJsonObject("user");
				String id = user.get("id").getAsString();
				String name = user.get("username").getAsString();
				attempt = 0;
				LOG.info("Connected to Fluxer as {} ({})", name, id);
				try {
					listener.onReady(id, name);
				} catch (RuntimeException e) {
					LOG.error("Error handling READY", e);
				}
			}
			case "RESUMED" -> {
				attempt = 0;
				LOG.info("Resumed Fluxer Gateway session");
			}
			case "MESSAGE_CREATE" -> {
				try {
					listener.onMessageCreate(d.getAsJsonObject());
				} catch (RuntimeException e) {
					LOG.error("Error handling a Fluxer message", e);
				}
			}
			default -> {
				// not needed
			}
		}
	}

	private void identify() {
		// A fresh session: never heartbeat with the old session's sequence.
		sessionId = null;
		sequence = null;
		JsonObject properties = new JsonObject();
		properties.addProperty("os", System.getProperty("os.name", "linux"));
		properties.addProperty("browser", "FluxerMinecraftBridge");
		properties.addProperty("device", "FluxerMinecraftBridge");
		JsonArray ignored = new JsonArray();
		IGNORED_EVENTS.forEach(ignored::add);
		JsonObject d = new JsonObject();
		d.addProperty("token", token);
		d.add("properties", properties);
		d.add("ignored_events", ignored);
		send(OP_IDENTIFY, d);
	}

	private void resume() {
		JsonObject d = new JsonObject();
		d.addProperty("token", token);
		d.addProperty("session_id", sessionId);
		d.addProperty("seq", sequence);
		LOG.info("Resuming Fluxer Gateway session");
		send(OP_RESUME, d);
	}

	private void startHeartbeat(int gen, long interval) {
		stopHeartbeat();
		ackReceived = true;
		long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
		heartbeatTask = exec.scheduleAtFixedRate(() -> {
			if (gen != generation) {
				return;
			}
			if (!ackReceived) {
				LOG.warn("Fluxer Gateway did not acknowledge the last heartbeat, reconnecting");
				dropConnection(gen, "heartbeat timeout", false);
				return;
			}
			ackReceived = false;
			sendHeartbeat();
		}, first, interval, TimeUnit.MILLISECONDS);
	}

	private void stopHeartbeat() {
		if (heartbeatTask != null) {
			heartbeatTask.cancel(false);
			heartbeatTask = null;
		}
	}

	private void sendHeartbeat() {
		send(OP_HEARTBEAT, sequence == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(sequence));
	}

	private void send(int op, JsonElement d) {
		WebSocket ws = socket;
		if (ws == null) {
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("op", op);
		payload.add("d", d);
		int gen = generation;
		try {
			ws.sendText(payload.toString(), true).get(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			dropConnection(gen, "send failed: " + root(e), false);
		}
	}

	/** Abort the socket (keeping the session resumable) and reconnect. */
	private void dropConnection(int gen, String reason, boolean fatal) {
		if (gen != generation || closed) {
			return;
		}
		generation++;
		stopHeartbeat();
		if (socket != null) {
			socket.abort();
			socket = null;
		}
		disconnectedAt = System.currentTimeMillis();
		LOG.warn("Disconnected from Fluxer Gateway: {}", reason);
		scheduleReconnect(fatal);
	}

	private void onClosed(int gen, int code, String reason) {
		boolean fatal = false;
		switch (code) {
			case 4004 -> {
				LOG.error("Fluxer rejected the bot token (4004). Check fluxer.token in the bridge config (or the environment variable it reads).");
				fatal = true;
				sessionId = null;
			}
			case 4012 -> {
				LOG.error("Fluxer no longer supports this Gateway version (4012). Update the bridge.");
				fatal = true;
			}
			case 4007, 4009 -> sessionId = null;
			default -> {
			}
		}
		dropConnection(gen, "closed with code " + code + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"), fatal);
	}

	private void scheduleReconnect(boolean fatal) {
		if (closed) {
			return;
		}
		long delay = fatal ? FATAL_RETRY_MS : Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt, 6));
		attempt++;
		LOG.info("Reconnecting to Fluxer Gateway in {}s", delay / 1000);
		exec.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
	}

	private void runOnExec(Runnable task) {
		try {
			exec.execute(task);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// shutting down
		}
	}

	private static String root(Throwable t) {
		Throwable r = t;
		while (r.getCause() != null && r.getCause() != r) {
			r = r.getCause();
		}
		return r.getClass().getSimpleName() + (r.getMessage() == null ? "" : ": " + r.getMessage());
	}

	private final class SocketListener implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buffer = new StringBuilder();

		SocketListener(int gen) {
			this.gen = gen;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			// Queued before any onText task, so Hello always finds the socket.
			runOnExec(() -> {
				if (gen == generation && !closed) {
					socket = webSocket;
				}
			});
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (buffer.length() > MAX_EVENT_CHARS) {
				buffer.setLength(0);
				runOnExec(() -> dropConnection(gen, "Gateway payload too large", false));
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
		public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
			// We never ask for compression, so binary frames are unexpected.
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			runOnExec(() -> onClosed(gen, statusCode, reason));
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			runOnExec(() -> dropConnection(gen, root(error), false));
		}
	}
}
