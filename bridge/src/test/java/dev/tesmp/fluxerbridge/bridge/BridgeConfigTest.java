package dev.tesmp.fluxerbridge.bridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeConfigTest {
	static final String VALID = """
			{
			  "fluxer": { "token": "${FLUXER_TOKEN}" },
			  "servers": { "tesmp": { "secret": "${TESMP_SECRET}", "displayName": "TESMP" } },
			  "links": [ { "server": "tesmp", "channelId": "1234567890" } ]
			}
			""";

	static final Map<String, String> ENV = Map.of("FLUXER_TOKEN", "123.abc\"quote", "TESMP_SECRET", "0123456789abcdef0123");

	@Test
	void parsesWithEnvironmentAndDefaults() {
		BridgeConfig config = BridgeConfig.parse(VALID, ENV::get);
		assertEquals("123.abc\"quote", config.fluxer.token);
		assertEquals("https://fluxer.app", config.fluxer.instance);
		assertEquals(8765, config.listen.port);
		BridgeConfig.Link link = config.links.getFirst();
		assertTrue(link.useWebhook());
		assertTrue(link.acceptsTag("anything"));
		assertEquals("TESMP", config.displayName("tesmp"));
	}

	@Test
	void missingEnvironmentVariableIsAnError() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BridgeConfig.parse(VALID, name -> null));
		assertTrue(e.getMessage().contains("FLUXER_TOKEN"), e.getMessage());
	}

	@Test
	void reportsAllProblems() {
		String bad = """
				{ "fluxer": { "token": "" },
				  "servers": { "tesmp": { "secret": "short" } },
				  "links": [ { "server": "other", "channelId": "#general", "style": "fancy" } ] }
				""";
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BridgeConfig.parse(bad, n -> null));
		String msg = e.getMessage();
		assertTrue(msg.contains("fluxer.token"), msg);
		assertTrue(msg.contains("too short"), msg);
		assertTrue(msg.contains("not in servers"), msg);
		assertTrue(msg.contains("numeric channel id"), msg);
		assertTrue(msg.contains("style"), msg);
	}

	@Test
	void exampleConfigInDocsIsValid() throws IOException {
		Path example = Path.of("..", "docs", "bridge.example.json");
		String json = Files.readString(example);
		BridgeConfig config = BridgeConfig.parse(json, name -> switch (name) {
			case "FLUXER_TOKEN" -> "123.abc";
			default -> "0123456789abcdef0123456789abcdef";
		});
		assertTrue(config.links.size() >= 1);
	}
}
