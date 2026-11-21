package dev.tesmp.fluxerbridge.bridge.fluxer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The few Fluxer HTTP API calls the bridge needs.
 * Docs: https://docs.fluxer.com/http-api/messages/ and /http-api/webhooks/
 */
public final class FluxerApi {
	private static final Logger LOG = LoggerFactory.getLogger("FluxerApi");
	private static final String USER_AGENT = "FluxerMinecraftBridge/" + Version.get();
	private static final int MAX_ATTEMPTS = 4;

	public record Endpoints(String apiPublic, String gateway) {
	}

	public record Webhook(String id, String token) {
	}

	private final HttpClient http;
	private final String apiBase;
	private final String token;

	public FluxerApi(HttpClient http, String apiPublic, String token) {
		this.http = http;
		this.apiBase = stripSlash(apiPublic) + "/v1";
		this.token = token;
	}

	/** Reads {@code /.well-known/fluxer} to find the API and Gateway URLs. */
	public static Endpoints discover(HttpClient http, String instance) throws IOException, InterruptedException {
		URI uri = URI.create(stripSlash(instance) + "/.well-known/fluxer");
		HttpRequest req = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", USER_AGENT)
				.GET().build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200) {
			throw new IOException("Fluxer discovery at " + uri + " returned HTTP " + res.statusCode());
		}
		JsonObject endpoints = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("endpoints");
		if (endpoints == null || !endpoints.has("api_public") || !endpoints.has("gateway")) {
			throw new IOException("Fluxer discovery at " + uri + " has no api_public/gateway endpoints");
		}
		return new Endpoints(endpoints.get("api_public").getAsString(), endpoints.get("gateway").getAsString());
	}

	/** The bot's own user object. Also proves the token works. */
	public JsonObject currentUser() throws IOException, InterruptedException {
		return request("GET", "/users/@me", null, true).getAsJsonObject();
	}

	public void createMessage(String channelId, String content) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("content", content);
		body.add("allowed_mentions", noMentions());
		request("POST", "/channels/" + channelId + "/messages", body, true);
	}

	/**
	 * Reuses a webhook with this name that the bot created earlier in the
	 * channel, or creates one. Needs the Manage Webhooks permission.
	 */
	public Webhook getOrCreateWebhook(String channelId, String name, String botUserId) throws IOException, InterruptedException {
		JsonElement list = request("GET", "/channels/" + channelId + "/webhooks", null, true);
		if (list != null && list.isJsonArray()) {
			for (JsonElement el : list.getAsJsonArray()) {
				JsonObject hook = el.getAsJsonObject();
				boolean sameName = name.equals(string(hook, "name"));
				boolean ours = hook.has("user") && botUserId.equals(string(hook.getAsJsonObject("user"), "id"));
				if (sameName && ours && hook.has("token")) {
					return new Webhook(string(hook, "id"), string(hook, "token"));
				}
			}
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		JsonObject created = request("POST", "/channels/" + channelId + "/webhooks", body, true).getAsJsonObject();
		LOG.info("Created webhook \"{}\" in channel {}", name, channelId);
		return new Webhook(string(created, "id"), string(created, "token"));
	}

	public void executeWebhook(Webhook webhook, String username, String avatarUrl, String content) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("content", content);
		if (username != null) {
			body.addProperty("username", username);
		}
		if (avatarUrl != null && !avatarUrl.isBlank()) {
			body.addProperty("avatar_url", avatarUrl);
		}
		body.add("allowed_mentions", noMentions());
		request("POST", "/webhooks/" + webhook.id() + "/" + webhook.token(), body, false);
	}

	// ---------------------------------------------------------------- internals

	private static JsonObject noMentions() {
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		return mentions;
	}

	private JsonElement request(String method, String path, JsonObject body, boolean auth) throws IOException, InterruptedException {
		for (int attempt = 1; ; attempt++) {
			HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(apiBase + path))
					.timeout(Duration.ofSeconds(20))
					.header("User-Agent", USER_AGENT);
			if (auth) {
				req.header("Authorization", "Bot " + token);
			}
			if (body != null) {
				req.header("Content-Type", "application/json");
				req.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
			} else {
				req.method(method, HttpRequest.BodyPublishers.noBody());
			}

			HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
			int status = res.statusCode();
			if (status >= 200 && status < 300) {
				String text = res.body();
				return text == null || text.isBlank() ? null : JsonParser.parseString(text);
			}
			if (status == 429 && attempt < MAX_ATTEMPTS) {
				double retryAfter = retryAfterSeconds(res);
				LOG.warn("Rate limited by Fluxer on {} {}, retrying in {}s", method, redact(path), retryAfter);
				Thread.sleep((long) Math.ceil(retryAfter * 1000));
				continue;
			}
			if (status >= 500 && attempt < MAX_ATTEMPTS) {
				Thread.sleep(1000L * attempt);
				continue;
			}
			throw new FluxerException(status, describeError(res));
		}
	}

	private static double retryAfterSeconds(HttpResponse<String> res) {
		try {
			JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
			if (body.has("retry_after")) {
				return Math.max(0.1, Math.min(60, body.get("retry_after").getAsDouble()));
			}
		} catch (RuntimeException ignored) {
			// fall back to the header
		}
		return res.headers().firstValue("Retry-After").map(v -> {
			try {
				return Double.parseDouble(v);
			} catch (NumberFormatException e) {
				return 1.0;
			}
		}).orElse(1.0);
	}

	private static String describeError(HttpResponse<String> res) {
		String body = res.body();
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			String code = string(json, "code");
			String message = string(json, "message");
			return (code != null ? code + ": " : "") + (message != null ? message : body);
		} catch (RuntimeException e) {
			return body == null ? "" : body.substring(0, Math.min(200, body.length()));
		}
	}

	/** Never log webhook tokens. */
	private static String redact(String path) {
		return path.startsWith("/webhooks/") ? "/webhooks/…" : path;
	}

	static String string(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	private static String stripSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	public static final class FluxerException extends IOException {
		private final int status;

		public FluxerException(int status, String message) {
			super("Fluxer API error " + status + ": " + message);
			this.status = status;
		}

		public int status() {
			return status;
		}
	}
}
