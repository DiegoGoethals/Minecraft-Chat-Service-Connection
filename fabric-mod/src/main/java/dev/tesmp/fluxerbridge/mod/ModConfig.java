package dev.tesmp.fluxerbridge.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code config/fluxerbridge.json}. Created with defaults (and a random
 * secret) on first start. Decides what this server sends; the bridge decides
 * which Fluxer channel it ends up in.
 */
public final class ModConfig {
	/**
	 * Where the bridge listens: ws://host:port. In Docker, host is the bridge's
	 * service or container name; port is listen.port in the bridge config.
	 */
	public String bridgeUrl = "ws://fluxer-bridge:8765";
	/** Any name for this server; must match a key under "servers" in the bridge config. */
	public String serverId = "tesmp";
	/** Must match servers.<serverId>.secret in the bridge config. */
	public String secret = "";

	public Send send = new Send();
	public Receive receive = new Receive();
	public Tellraw tellraw = new Tellraw();

	public static final class Send {
		/** Normal player chat. */
		public boolean chat = true;
		/** /say and /me, also when a datapack function runs them. */
		public boolean say = true;
		/** Messages sent with /fluxerbridge send (for datapacks). */
		public boolean datapack = true;
		/** "Server started" / "Server stopping". */
		public boolean serverStatus = true;
		/** Join and leave messages. */
		public boolean joins = false;
		/** Player death messages. */
		public boolean deaths = false;
		/** Advancement messages. */
		public boolean advancements = false;

		/** Text sent when the server has started (with serverStatus on). */
		public String serverStartedMessage = "The server is online";
		/** Text sent when the server is stopping (with serverStatus on). */
		public String serverStoppingMessage = "The server is shutting down";
		/** How /say is sent. Placeholders: {author} {text}. */
		public String sayFormat = "[{author}] {text}";
		/** How /me is sent. Placeholders: {author} {text}. */
		public String emoteFormat = "* {author} {text}";
	}

	public static final class Receive {
		/** Show Fluxer messages in Minecraft chat. */
		public boolean enabled = true;
		/**
		 * How Fluxer messages look in game. Placeholders: {origin} {author} {text}.
		 * Use &amp; color codes, e.g. &amp;9 = blue, &amp;7 = gray, &amp;r = reset.
		 */
		public String format = "&9[{origin}] &f{author}&7: &r{text}";
		/** Longer messages are cut off. */
		public int maxLength = 256;
	}

	/**
	 * Optional: forward "tellraw @a ..." messages from datapacks without
	 * changing the datapack. Off by default; prefer /fluxerbridge send.
	 */
	public static final class Tellraw {
		public boolean enabled = false;
		/** Only forward tellraw aimed at everyone (target selector starts with @a). */
		public boolean onlyEveryone = true;
		/** Regular expressions; when not empty, only matching messages are forwarded. */
		public List<String> patterns = new ArrayList<>();
		/** Routing tag for the bridge. */
		public String tag = "tellraw";

		transient List<Pattern> compiled = List.of();

		public boolean matches(String text) {
			if (compiled.isEmpty()) {
				return true;
			}
			for (Pattern p : compiled) {
				if (p.matcher(text).find()) {
					return true;
				}
			}
			return false;
		}
	}

	// ------------------------------------------------------------------ loading

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * Loads the config, creating it with defaults and a fresh secret when it
	 * doesn't exist.
	 */
	public static ModConfig load(Path path) throws IOException {
		if (!Files.exists(path)) {
			ModConfig config = new ModConfig();
			byte[] bytes = new byte[24];
			new SecureRandom().nextBytes(bytes);
			config.secret = HexFormat.of().formatHex(bytes);
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config));
			FluxerBridgeMod.LOG.info("Created {} with a new random secret. Put the same secret in the bridge config.", path);
			config.compile();
			return config;
		}
		ModConfig config;
		try {
			config = GSON.fromJson(Files.readString(path), ModConfig.class);
		} catch (JsonParseException e) {
			throw new IOException("the file is not valid JSON: " + e.getMessage()
					+ ". Fix it, or delete the file to have a fresh one created on the next start", e);
		}
		if (config == null) {
			config = new ModConfig();
		}
		if (config.send == null) {
			config.send = new Send();
		}
		if (config.receive == null) {
			config.receive = new Receive();
		}
		if (config.tellraw == null) {
			config.tellraw = new Tellraw();
		}
		config.compile();
		return config;
	}

	private void compile() throws IOException {
		List<Pattern> patterns = new ArrayList<>();
		if (tellraw.patterns != null) {
			for (String p : tellraw.patterns) {
				try {
					patterns.add(Pattern.compile(p));
				} catch (PatternSyntaxException e) {
					throw new IOException("Invalid tellraw pattern \"" + p + "\": " + e.getDescription());
				}
			}
		}
		tellraw.compiled = patterns;
	}

	/** @return a problem that prevents connecting, or null when the config is usable */
	public String problem() {
		if (bridgeUrl == null || bridgeUrl.isBlank()) {
			return "bridgeUrl is empty. Set it to the bridge's address, e.g. \"ws://fluxer-bridge:8765\"";
		}
		try {
			URI uri = URI.create(bridgeUrl);
			String scheme = uri.getScheme();
			if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
				return "bridgeUrl must start with ws:// or wss://, e.g. \"ws://fluxer-bridge:8765\"";
			}
			if (uri.getHost() == null) {
				return "bridgeUrl has no host name, e.g. \"ws://fluxer-bridge:8765\"";
			}
		} catch (IllegalArgumentException e) {
			return "bridgeUrl \"" + bridgeUrl + "\" is not a valid URL, e.g. \"ws://fluxer-bridge:8765\"";
		}
		if (serverId == null || serverId.isBlank()) {
			return "serverId is empty. Use the name this server has under \"servers\" in the bridge config";
		}
		if (secret == null || secret.isBlank()) {
			return "secret is empty. Use the same value as servers.<serverId>.secret in the bridge config "
					+ "(or delete this file to generate a new one)";
		}
		return null;
	}
}
