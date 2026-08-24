package dev.buizz.cobbleventure.casino.mixin;

import dev.buizz.cobbleventure.casino.CasinoGameAccess;
import net.minecraft.server.level.ServerPlayer;
import net.narrnouille.cobblemoncasino.network.c2s.common.ReturnToMachineScreenC2SPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops a zero-balance configuration screen from reopening its casino game. */
@Mixin(targets = "net.narrnouille.cobblemoncasino.network.c2s_handlers.common.ReturnToMachineScreenReceiver", remap = false)
abstract class CasinoReturnScreenMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cobbleventure$requireBalanceBeforeReturn(
        ReturnToMachineScreenC2SPayload payload, IPayloadContext context, CallbackInfo callback
    ) {
        if (context.player() instanceof ServerPlayer player
            && CasinoGameAccess.denyIfEmpty(player)) {
            player.closeContainer();
            callback.cancel();
        }
    }
}
