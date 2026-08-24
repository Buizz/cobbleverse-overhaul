package dev.buizz.cobbleventure.casino.mixin;

import dev.buizz.cobbleventure.casino.CasinoCashier;
import net.minecraft.server.level.ServerPlayer;
import net.narrnouille.cobblemoncasino.network.c2s.common.OpenWithdrawScreenC2SPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.narrnouille.cobblemoncasino.network.c2s_handlers.common.WithdrawScreenReceiver", remap = false)
abstract class CasinoWithdrawScreenMixin {
    @Inject(method = "openWithdrawScreen", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cobbleventure$disableWithdrawal(
        OpenWithdrawScreenC2SPayload payload, IPayloadContext context, CallbackInfo callback
    ) {
        if (context.player() instanceof ServerPlayer player) CasinoCashier.showWithdrawalDisabled(player);
        callback.cancel();
    }
}
