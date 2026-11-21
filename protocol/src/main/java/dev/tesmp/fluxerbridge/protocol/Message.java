package dev.tesmp.fluxerbridge.protocol;

import java.util.List;

/**
 * Every message exchanged between a Minecraft server (the Fabric mod) and the
 * bridge. Encoded as one JSON object per WebSocket text frame, with a
 * {@code "type"} field naming the record (see {@link MessageCodec}).
 *
 * <p>Flow: the mod connects, sends {@link Hello}, and the bridge answers with
 * {@link Welcome} or {@link Reject}. After that both sides may send
 * {@link Chat}, {@link ServerEvent}, {@link Ping}/{@link Pong} and the
 * status request/response pair at any time.
 */
public sealed interface Message permits
		Message.Hello, Message.Welcome, Message.Reject,
		Message.Chat, Message.ServerEvent,
		Message.Ping, Message.Pong,
		Message.StatusRequest, Message.Status {

	/** First message from the mod. Authenticates it as one configured server. */
	record Hello(String serverId, String secret, int protocolVersion, String softwareVersion) implements Message {
	}

	/** Bridge accepted the {@link Hello}. */
	record Welcome(String bridgeVersion, int protocolVersion) implements Message {
	}

	/** Bridge refused the {@link Hello}; the connection is closed afterwards. */
	record Reject(String reason) implements Message {
	}

	/**
	 * A chat line, in either direction.
	 *
	 * @param id       unique id, used to drop duplicates after reconnects
	 * @param tag      routing tag, {@code "chat"} for normal player chat
	 * @param author   display name of the sender
	 * @param authorId Minecraft UUID or Fluxer user id of the sender (may be null)
	 * @param text     plain text, already stripped of formatting
	 * @param origin   label of where it came from, e.g. {@code "Fluxer"} (may be null)
	 */
	record Chat(String id, String tag, String author, String authorId, String text, String origin) implements Message {
	}

	/**
	 * Something that happened on the Minecraft server and is not player chat.
	 *
	 * @param kind     see {@link EventKind}
	 * @param tag      routing tag the bridge uses to pick channels
	 * @param player   player name, when the event is about a player (may be null)
	 * @param playerId player UUID (may be null)
	 * @param text     human readable text, e.g. "Steve fell from a high place"
	 */
	record ServerEvent(String id, String kind, String tag, String player, String playerId, String text) implements Message {
	}

	record Ping(long time) implements Message {
	}

	record Pong(long time) implements Message {
	}

	/** Bridge asks the server who is online (for the {@code !players} command). */
	record StatusRequest(String requestId) implements Message {
	}

	record Status(String requestId, List<String> players, int maxPlayers, String version) implements Message {
	}
}
