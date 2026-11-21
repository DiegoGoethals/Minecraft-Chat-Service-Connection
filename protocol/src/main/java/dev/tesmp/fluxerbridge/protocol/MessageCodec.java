package dev.tesmp.fluxerbridge.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * Converts {@link Message}s to and from JSON text.
 *
 * <pre>{@code {"type":"chat","id":"...","tag":"chat","author":"Steve","text":"hi"}}</pre>
 */
public final class MessageCodec {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private static final Map<String, Class<? extends Message>> TYPES = Map.of(
			"hello", Message.Hello.class,
			"welcome", Message.Welcome.class,
			"reject", Message.Reject.class,
			"chat", Message.Chat.class,
			"event", Message.ServerEvent.class,
			"ping", Message.Ping.class,
			"pong", Message.Pong.class,
			"status_request", Message.StatusRequest.class,
			"status", Message.Status.class);

	private MessageCodec() {
	}

	public static String typeName(Message message) {
		return switch (message) {
			case Message.Hello ignored -> "hello";
			case Message.Welcome ignored -> "welcome";
			case Message.Reject ignored -> "reject";
			case Message.Chat ignored -> "chat";
			case Message.ServerEvent ignored -> "event";
			case Message.Ping ignored -> "ping";
			case Message.Pong ignored -> "pong";
			case Message.StatusRequest ignored -> "status_request";
			case Message.Status ignored -> "status";
		};
	}

	public static String encode(Message message) {
		JsonObject json = GSON.toJsonTree(message).getAsJsonObject();
		JsonObject out = new JsonObject();
		out.addProperty("type", typeName(message));
		for (Map.Entry<String, JsonElement> e : json.entrySet()) {
			out.add(e.getKey(), e.getValue());
		}
		return GSON.toJson(out);
	}

	/**
	 * @return the decoded message
	 * @throws ProtocolException when the text is not valid JSON, has no known
	 *                           type, or does not fit the type's fields
	 */
	public static Message decode(String text) throws ProtocolException {
		if (text.length() > Protocol.MAX_FRAME_CHARS) {
			throw new ProtocolException("Message too large (" + text.length() + " chars)");
		}
		try {
			JsonElement element = JsonParser.parseString(text);
			if (!element.isJsonObject()) {
				throw new ProtocolException("Message is not a JSON object");
			}
			JsonObject obj = element.getAsJsonObject();
			JsonElement typeEl = obj.get("type");
			if (typeEl == null || !typeEl.isJsonPrimitive()) {
				throw new ProtocolException("Message has no type");
			}
			Class<? extends Message> type = TYPES.get(typeEl.getAsString());
			if (type == null) {
				throw new ProtocolException("Unknown message type: " + typeEl.getAsString());
			}
			Message message = GSON.fromJson(obj, type);
			if (message == null) {
				throw new ProtocolException("Empty message");
			}
			return message;
		} catch (JsonParseException | IllegalStateException | NumberFormatException e) {
			throw new ProtocolException("Invalid message: " + e.getMessage(), e);
		}
	}

	public static final class ProtocolException extends Exception {
		public ProtocolException(String message) {
			super(message);
		}

		public ProtocolException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
