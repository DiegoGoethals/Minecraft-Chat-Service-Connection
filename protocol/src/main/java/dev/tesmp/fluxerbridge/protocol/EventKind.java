package dev.tesmp.fluxerbridge.protocol;

/**
 * Values used in {@link Message.ServerEvent#kind()}, and the default routing
 * tag the mod attaches to each of them.
 */
public final class EventKind {
	public static final String JOIN = "join";
	public static final String LEAVE = "leave";
	public static final String DEATH = "death";
	public static final String ADVANCEMENT = "advancement";
	public static final String SERVER_START = "server_start";
	public static final String SERVER_STOP = "server_stop";
	/** {@code /say} or {@code /me}, from a player, the console or a datapack function. */
	public static final String SAY = "say";
	/** Sent explicitly with {@code /fluxerbridge send}. */
	public static final String DATAPACK = "datapack";
	/** A {@code tellraw @a ...} captured by the optional tellraw hook. */
	public static final String TELLRAW = "tellraw";

	public static final String TAG_CHAT = "chat";
	public static final String TAG_JOINS = "joins";
	public static final String TAG_DEATHS = "deaths";
	public static final String TAG_ADVANCEMENTS = "advancements";
	public static final String TAG_STATUS = "status";
	public static final String TAG_SAY = "say";
	public static final String TAG_DATAPACK = "datapack";
	public static final String TAG_TELLRAW = "tellraw";

	private EventKind() {
	}
}
