package dev.tesmp.fluxerbridge.mod;

import dev.tesmp.fluxerbridge.protocol.Message;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the in-game line for a message that came from Fluxer, from the
 * {@code receive.format} template. {@code &} color codes are only honoured in
 * the template itself; text from Fluxer is always inserted as-is.
 */
final class ChatFormatter {
	private static final Pattern TOKEN = Pattern.compile("&([0-9a-fk-orA-FK-OR])|\\{(origin|author|text)}");

	private ChatFormatter() {
	}

	static Component format(ModConfig.Receive receive, Message.Chat chat) {
		String text = sanitize(chat.text());
		int max = Math.max(16, receive.maxLength);
		if (text.length() > max) {
			text = text.substring(0, max - 1) + "…";
		}
		String origin = sanitize(chat.origin() == null ? "Fluxer" : chat.origin());
		String author = sanitize(chat.author() == null ? "Unknown" : chat.author());
		String template = receive.format == null || receive.format.isBlank() ? "[{origin}] {author}: {text}" : receive.format;

		MutableComponent line = Component.empty();
		Style style = Style.EMPTY;
		Matcher m = TOKEN.matcher(template);
		int last = 0;
		while (m.find()) {
			append(line, template.substring(last, m.start()), style);
			last = m.end();
			if (m.group(1) != null) {
				style = applyCode(style, Character.toLowerCase(m.group(1).charAt(0)));
			} else {
				String value = switch (m.group(2)) {
					case "origin" -> origin;
					case "author" -> author;
					default -> text;
				};
				append(line, value, style);
			}
		}
		append(line, template.substring(last), style);
		return line;
	}

	private static Style applyCode(Style style, char code) {
		ChatFormatting formatting = ChatFormatting.getByCode(code);
		if (formatting == null || formatting == ChatFormatting.RESET) {
			return Style.EMPTY;
		}
		boolean isColor = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
		// Like classic Minecraft codes: a color also clears bold/italic/etc.
		return isColor ? Style.EMPTY.withColor(formatting) : style.applyFormat(formatting);
	}

	private static void append(MutableComponent line, String part, Style style) {
		if (!part.isEmpty()) {
			line.append(Component.literal(part).withStyle(style));
		}
	}

	/** Drops section signs and control characters so remote text can't inject formatting. */
	static String sanitize(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§' || Character.isISOControl(c)) {
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}
}
