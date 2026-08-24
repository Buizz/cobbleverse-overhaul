package dev.buizz.cobbleventure.casino.mixin;

import dev.buizz.cobbleventure.casino.CasinoGameAccess;
import net.minecraft.server.level.ServerPlayer;
import net.narrnouille.cobblemoncasino.network.c2s.common.OpenMenuScreenC2SPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops a zero-balance slot session from moving into its configuration menu. */
@Mixin(targets = "net.narrnouille.cobblemoncasino.network.c2s_handlers.common.MenuScreenReceiver", remap = false)
abstract class CasinoMenuScreenMixin {
    @Inject(method = "openMenuScreen", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cobbleventure$requireBalanceBeforeMenu(
        OpenMenuScreenC2SPayload payload, IPayloadContext context, CallbackInfo callback
    ) {
        if (context.player() instanceof ServerPlayer player
            && CasinoGameAccess.denyIfEmpty(player)) {
            player.closeContainer();
            callback.cancel();
        }
    }
}
