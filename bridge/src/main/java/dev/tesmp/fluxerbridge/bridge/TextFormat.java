package dev.tesmp.fluxerbridge.bridge;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text conversions between Fluxer markdown and Minecraft plain text. */
public final class TextFormat {
	private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
	private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
	private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");
	private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");
	private static final Pattern TIMESTAMP = Pattern.compile("<t:(-?\\d+)(?::[tTdDfFR])?>");
	private static final Pattern MARKDOWN_SPECIAL = Pattern.compile("([\\\\*_~`|\\[\\]])");
	private static final Pattern LINE_START_SPECIAL = Pattern.compile("[>#-]");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

	/** Fluxer allows at least 4000 characters for bots and webhooks. */
	public static final int FLUXER_MAX_LENGTH = 2000;

	private TextFormat() {
	}

	/**
	 * Turns a Fluxer message into one line of plain text for Minecraft:
	 * mentions become {@code @name}, custom emoji become {@code :name:},
	 * newlines become spaces.
	 *
	 * @param userNames resolves a user id to a display name, or null when unknown
	 */
	public static String fluxerToMinecraft(String content, Function<String, String> userNames) {
		if (content == null) {
			return "";
		}
		String text = content;
		text = replace(text, USER_MENTION, m -> {
			String name = userNames.apply(m.group(1));
			return "@" + (name == null ? "user" : name);
		});
		text = replace(text, ROLE_MENTION, m -> "@role");
		text = replace(text, CHANNEL_MENTION, m -> "#channel");
		text = replace(text, CUSTOM_EMOJI, m -> ":" + m.group(1) + ":");
		text = replace(text, TIMESTAMP, m -> "(time)");
		text = text.replaceAll("\\s*[\\r\\n]+\\s*", " ");
		return stripControl(text).trim();
	}

	/** Escapes Fluxer markdown so Minecraft text is shown exactly as typed. */
	public static String escapeMarkdown(String text) {
		if (text == null) {
			return "";
		}
		String escaped = MARKDOWN_SPECIAL.matcher(text).replaceAll("\\\\$1");
		// Quote, heading and list markers only matter at the start of the text.
		if (LINE_START_SPECIAL.matcher(escaped).lookingAt()) {
			escaped = "\\" + escaped;
		}
		// Avoid accidental mentions even though allowed_mentions already blocks pings.
		return escaped.replace("@", "@​");
	}

	/** Fills {name} placeholders. Unknown placeholders are left as they are. */
	public static String fill(String template, Map<String, String> values) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String value = values.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
		}
		m.appendTail(out);
		return out.toString();
	}

	public static String truncate(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		return text.substring(0, Math.max(0, max - 1)) + "…";
	}

	/** Removes control characters (and Minecraft's section-sign color codes). */
	public static String stripControl(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§') {
				i++; // skip the formatting code character too
				continue;
			}
			if (Character.isISOControl(c)) {
				sb.append(' ');
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String replace(String text, Pattern pattern, Function<Matcher, String> fn) {
		Matcher m = pattern.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(out, Matcher.quoteReplacement(fn.apply(m)));
		}
		m.appendTail(out);
		return out.toString();
	}
}
