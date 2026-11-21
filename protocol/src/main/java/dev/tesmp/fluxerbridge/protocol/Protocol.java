package dev.tesmp.fluxerbridge.protocol;

/** Constants shared by the mod and the bridge. */
public final class Protocol {
	/**
	 * Bump when a change would break an older mod or bridge. Adding optional
	 * fields or new message types that the other side may ignore does not
	 * require a bump.
	 */
	public static final int VERSION = 1;

	public static final int DEFAULT_PORT = 8765;

	/** Largest text frame either side accepts. */
	public static final int MAX_FRAME_CHARS = 64 * 1024;

	private Protocol() {
	}
}
