package dev.tesmp.fluxerbridge.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.tesmp.fluxerbridge.protocol.EventKind;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.network.chat.Component;

/**
 * <pre>
 * /fluxerbridge send &lt;text component&gt;            send to Fluxer (tag "datapack")
 * /fluxerbridge sendto &lt;tag&gt; &lt;text component&gt;   send with a custom routing tag
 * /fluxerbridge status                            show the connection state
 * /fluxerbridge reload                            re-read config/fluxerbridge.json
 * </pre>
 * The text component uses the same syntax as tellraw, so a datapack can copy
 * its tellraw message, e.g.
 * {@code fluxerbridge send ["A villager died at ",{"score":{"name":"@s","objective":"x"}}]}.
 */
final class BridgeCommand {
	private BridgeCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("fluxerbridge")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("send")
						.then(Commands.argument("message", ComponentArgument.textComponent(buildContext))
								.executes(ctx -> send(ctx, EventKind.TAG_DATAPACK))))
				.then(Commands.literal("sendto")
						.then(Commands.argument("tag", StringArgumentType.word())
								.then(Commands.argument("message", ComponentArgument.textComponent(buildContext))
										.executes(ctx -> send(ctx, StringArgumentType.getString(ctx, "tag"))))))
				.then(Commands.literal("status")
						.executes(BridgeCommand::status))
				.then(Commands.literal("reload")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.executes(BridgeCommand::reload)));
	}

	private static int send(CommandContext<CommandSourceStack> ctx, String tag) throws CommandSyntaxException {
		FluxerBridgeMod mod = FluxerBridgeMod.get();
		ModConfig cfg = mod.config();
		if (cfg == null || !cfg.send.datapack) {
			return 0;
		}
		Component message = ComponentArgument.getResolvedComponent(ctx, "message");
		mod.sendEvent(EventKind.DATAPACK, tag, null, null, message.getString());
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		FluxerBridgeMod mod = FluxerBridgeMod.get();
		ModConfig cfg = mod.config();
		String text;
		if (cfg == null) {
			text = "Fluxer bridge: config could not be loaded, see the server log";
		} else if (mod.isConnected()) {
			text = "Fluxer bridge: connected to " + cfg.bridgeUrl + " as \"" + cfg.serverId + "\"";
		} else {
			String problem = cfg.problem();
			text = "Fluxer bridge: not connected" + (problem != null ? " (" + problem + ")" : ", retrying " + cfg.bridgeUrl);
		}
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return mod.isConnected() ? 1 : 0;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		String problem = FluxerBridgeMod.get().reload();
		if (problem == null) {
			ctx.getSource().sendSuccess(() -> Component.literal("Fluxer bridge config reloaded"), true);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("Fluxer bridge config problem: " + problem));
		return 0;
	}
}
