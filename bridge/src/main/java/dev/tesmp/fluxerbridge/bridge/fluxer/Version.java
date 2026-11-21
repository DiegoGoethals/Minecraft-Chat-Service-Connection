package dev.tesmp.fluxerbridge.bridge.fluxer;

/** The bridge's version, read from the jar manifest (set by Gradle). */
public final class Version {
	private Version() {
	}

	public static String get() {
		String v = Version.class.getPackage().getImplementationVersion();
		return v == null ? "dev" : v;
	}
}
