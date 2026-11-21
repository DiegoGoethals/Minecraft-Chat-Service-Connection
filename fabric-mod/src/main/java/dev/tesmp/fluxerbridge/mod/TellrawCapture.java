package dev.tesmp.fluxerbridge.mod;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import dev.tesmp.fluxerbridge.protocol.EventKind;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.network.chat.Component;

/**
 * Called by {@code TellRawCommandMixin} before every {@code /tellraw}. Only
 * active when {@code tellraw.enabled} is true in the config. Never throws, so
 * it can't break the tellraw command itself.
 */
public final class TellrawCapture {
	private TellrawCapture() {
	}

	public static void onTellraw(CommandContext<CommandSourceStack> ctx) {
		try {
			FluxerBridgeMod mod = FluxerBridgeMod.get();
			ModConfig cfg = mod == null ? null : mod.config();
			if (cfg == null || !cfg.tellraw.enabled) {
				return;
			}
			if (cfg.tellraw.onlyEveryone && !targetsText(ctx).startsWith("@a")) {
				return;
			}
			Component message;
			try {
				message = ComponentArgument.getResolvedComponent(ctx, "message");
			} catch (Exception e) {
				message = ComponentArgument.getRawComponent(ctx, "message");
			}
			String text = message.getString();
			if (text.isBlank() || !cfg.tellraw.matches(text)) {
				return;
			}
			String tag = cfg.tellraw.tag == null || cfg.tellraw.tag.isBlank() ? EventKind.TAG_TELLRAW : cfg.tellraw.tag;
			mod.sendEvent(EventKind.TELLRAW, tag, null, null, text);
		} catch (Throwable t) {
			FluxerBridgeMod.LOG.debug("tellraw capture failed", t);
		}
	}

	/** The target selector exactly as typed, e.g. "@a" or "@a[tag=x]". */
	private static String targetsText(CommandContext<CommandSourceStack> ctx) {
		for (ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
			if ("targets".equals(node.getNode().getName())) {
				return node.getRange().get(ctx.getInput()).trim();
			}
		}
		return "";
	}
}
