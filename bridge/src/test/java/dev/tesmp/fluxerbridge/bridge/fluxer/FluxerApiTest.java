package dev.tesmp.fluxerbridge.bridge.fluxer;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FluxerApiTest {
	private static final String BOT = "100";

	private static JsonArray hooks(String json) {
		return JsonParser.parseString(json).getAsJsonArray();
	}

	@Test
	void reusesWebhookTheBotCreated() {
		JsonArray list = hooks("""
				[{"id":"1","name":"TESMP","token":"t1","type":1,"user":{"id":"100"}}]""");
		assertEquals(new FluxerApi.Webhook("1", "t1"), FluxerApi.pickWebhook(list, "TESMP", BOT));
	}

	@Test
	void reusesWebhookCreatedByHandWhenTheBotHasNone() {
		JsonArray list = hooks("""
				[{"id":"2","name":"TESMP","token":"t2","type":1,"user":{"id":"555"}}]""");
		assertEquals(new FluxerApi.Webhook("2", "t2"), FluxerApi.pickWebhook(list, "TESMP", BOT));
	}

	@Test
	void prefersTheBotsOwnWebhook() {
		JsonArray list = hooks("""
				[{"id":"2","name":"TESMP","token":"t2","type":1,"user":{"id":"555"}},
				 {"id":"1","name":"TESMP","token":"t1","type":1,"user":{"id":"100"}}]""");
		assertEquals(new FluxerApi.Webhook("1", "t1"), FluxerApi.pickWebhook(list, "TESMP", BOT));
	}

	@Test
	void ignoresSurroundingWhitespaceLikeFluxerDoes() {
		JsonArray list = hooks("""
				[{"id":"1","name":"TESMP","token":"t1","type":1,"user":{"id":"100"}}]""");
		assertEquals(new FluxerApi.Webhook("1", "t1"), FluxerApi.pickWebhook(list, " TESMP ", BOT));
	}

	@Test
	void skipsOtherNamesAndWebhooksWithoutToken() {
		JsonArray list = hooks("""
				[{"id":"1","name":"Other","token":"t1","type":1,"user":{"id":"100"}},
				 {"id":"2","name":"TESMP","type":2,"user":{"id":"100"}},
				 {"id":"3","name":"TESMP","token":null,"type":1,"user":{"id":"100"}}]""");
		assertNull(FluxerApi.pickWebhook(list, "TESMP", BOT));
	}
}
