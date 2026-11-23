package dev.tesmp.fluxerbridge.mod;

import dev.tesmp.fluxerbridge.protocol.EventKind;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Listens to Minecraft and forwards what the config allows.
 *
 * <p>Joins, leaves, deaths and advancements are detected from the vanilla
 * broadcast messages (by translation key), so they follow the gamerules
 * {@code showDeathMessages} and {@code announceAdvancements}. Messages this
 * mod broadcasts itself are plain literals and are therefore never sent back.
 */
final class MinecraftEvents {
	private MinecraftEvents() {
	}

	static void register(FluxerBridgeMod mod) {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			ModConfig cfg = mod.config();
			if (cfg != null && cfg.send.chat) {
				mod.sendChat(sender, message.decoratedContent().getString());
			}
		});

		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
			ModConfig cfg = mod.config();
			if (cfg == null || !cfg.send.say) {
				return;
			}
			String author = source.getTextName();
			String content = message.decoratedContent().getString();
			boolean emote = params.chatType().is(ChatType.EMOTE_COMMAND);
			String format = emote ? cfg.send.emoteFormat : cfg.send.sayFormat;
			if (format == null || format.isBlank()) {
				format = emote ? "* {author} {text}" : "[{author}] {text}";
			}
			String text = format.replace("{author}", author).replace("{text}", content);
			ServerPlayer player = source.getPlayer();
			mod.sendEvent(EventKind.SAY, EventKind.TAG_SAY, author, player != null ? player.getStringUUID() : null, text);
		});

		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (!overlay) {
				onGameMessage(mod, server, message);
			}
		});
	}

	private static void onGameMessage(FluxerBridgeMod mod, MinecraftServer server, Component message) {
		ModConfig cfg = mod.config();
		if (cfg == null || !(message.getContents() instanceof TranslatableContents translatable)) {
			return;
		}
		String key = translatable.getKey();
		String kind;
		String tag;
		if (key.startsWith("multiplayer.player.joined")) {
			if (!cfg.send.joins) {
				return;
			}
			kind = EventKind.JOIN;
			tag = EventKind.TAG_JOINS;
		} else if (key.equals("multiplayer.player.left")) {
			if (!cfg.send.joins) {
				return;
			}
			kind = EventKind.LEAVE;
			tag = EventKind.TAG_JOINS;
		} else if (key.startsWith("death.")) {
			if (!cfg.send.deaths) {
				return;
			}
			kind = EventKind.DEATH;
			tag = EventKind.TAG_DEATHS;
		} else if (key.startsWith("chat.type.advancement.")) {
			if (!cfg.send.advancements) {
				return;
			}
			kind = EventKind.ADVANCEMENT;
			tag = EventKind.TAG_ADVANCEMENTS;
		} else {
			return;
		}

		String playerName = null;
		String playerId = null;
		Object[] args = translatable.getArgs();
		if (args.length > 0) {
			playerName = args[0] instanceof Component c ? c.getString() : String.valueOf(args[0]);
			ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
			if (player != null) {
				playerId = player.getStringUUID();
			}
		}
		mod.sendEvent(kind, tag, playerName, playerId, message.getString());
	}
}
