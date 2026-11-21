package dev.tesmp.fluxerbridge.mod.mixin;

import com.mojang.brigadier.context.CommandContext;
import dev.tesmp.fluxerbridge.mod.TellrawCapture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TellRawCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the body of {@code /tellraw} (a lambda inside
 * {@link TellRawCommand#register}). {@code require = 0}: if a future Minecraft
 * version renames the lambda, the hook silently does nothing instead of
 * crashing the server.
 */
@Mixin(TellRawCommand.class)
public abstract class TellRawCommandMixin {
	@Inject(method = "lambda$register$0", at = @At("HEAD"), require = 0)
	private static void fluxerbridge$captureTellraw(CommandContext<CommandSourceStack> context, CallbackInfoReturnable<Integer> cir) {
		TellrawCapture.onTellraw(context);
	}
}
