package dev.tesmp.fluxerbridge.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCodecTest {
	@Test
	void everyMessageTypeRoundTrips() throws Exception {
		List<Message> messages = List.of(
				new Message.Hello("tesmp", "secret", Protocol.VERSION, "0.1.0"),
				new Message.Welcome("0.1.0", Protocol.VERSION),
				new Message.Reject("nope"),
				new Message.Chat("1", "chat", "Steve", "uuid", "hello <world> & \"quotes\"", null),
				new Message.ServerEvent("2", EventKind.DATAPACK, "datapack", null, null, "A villager died at 10 64 -3"),
				new Message.Ping(5),
				new Message.Pong(5),
				new Message.StatusRequest("r"),
				new Message.Status("r", List.of("Steve", "Alex"), 20, "26.3"));
		for (Message m : messages) {
			assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)));
		}
	}

	@Test
	void encodesTypeField() {
		String json = MessageCodec.encode(new Message.Ping(1));
		assertTrue(json.contains("\"type\":\"ping\""), json);
	}

	@Test
	void ignoresUnknownFields() throws Exception {
		Message m = MessageCodec.decode("{\"type\":\"pong\",\"time\":3,\"futureField\":true}");
		assertEquals(new Message.Pong(3), m);
	}

	@Test
	void rejectsBadInput() {
		assertThrows(MessageCodec.ProtocolException.class, () -> MessageCodec.decode("not json"));
		assertThrows(MessageCodec.ProtocolException.class, () -> MessageCodec.decode("[1,2]"));
		assertThrows(MessageCodec.ProtocolException.class, () -> MessageCodec.decode("{\"time\":1}"));
		assertThrows(MessageCodec.ProtocolException.class, () -> MessageCodec.decode("{\"type\":\"explode\"}"));
		assertThrows(MessageCodec.ProtocolException.class, () -> MessageCodec.decode("{\"type\":\"ping\",\"time\":\"abc\"}"));
		assertThrows(MessageCodec.ProtocolException.class, () -> MessageCodec.decode("x".repeat(Protocol.MAX_FRAME_CHARS + 1)));
	}
}
