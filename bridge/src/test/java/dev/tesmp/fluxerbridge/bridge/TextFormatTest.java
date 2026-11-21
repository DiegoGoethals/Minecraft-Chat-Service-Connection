package dev.tesmp.fluxerbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextFormatTest {
	@Test
	void convertsFluxerMarkupToPlainText() {
		Map<String, String> names = Map.of("111", "Victoria");
		assertEquals("hi @Victoria and @user", TextFormat.fluxerToMinecraft("hi <@111> and <@!222>", names::get));
		assertEquals("ping @role in #channel", TextFormat.fluxerToMinecraft("ping <@&5> in <#6>", names::get));
		assertEquals("nice :pog: :wave:", TextFormat.fluxerToMinecraft("nice <:pog:123> <a:wave:456>", names::get));
		assertEquals("line one line two", TextFormat.fluxerToMinecraft("line one\n\n  line two", names::get));
	}

	@Test
	void stripsMinecraftFormattingCodes() {
		assertEquals("red text", TextFormat.fluxerToMinecraft("§cred text", id -> null));
	}

	@Test
	void escapesMarkdownAndMentions() {
		assertEquals("\\*bold\\* \\_x\\_ \\`c\\` \\[a\\](b)", TextFormat.escapeMarkdown("*bold* _x_ `c` [a](b)"));
		assertEquals("\\> quote", TextFormat.escapeMarkdown("> quote"));
		assertEquals("@​everyone", TextFormat.escapeMarkdown("@everyone"));
		assertEquals("a - b", TextFormat.escapeMarkdown("a - b"));
	}

	@Test
	void fillsPlaceholdersOnce() {
		assertEquals("Steve: {text}", TextFormat.fill("{author}: {text}", Map.of("author", "Steve", "text", "{text}")));
		assertEquals("{unknown}", TextFormat.fill("{unknown}", Map.of()));
	}

	@Test
	void truncates() {
		assertEquals("abc", TextFormat.truncate("abc", 3));
		assertEquals("ab…", TextFormat.truncate("abcd", 3));
	}
}
