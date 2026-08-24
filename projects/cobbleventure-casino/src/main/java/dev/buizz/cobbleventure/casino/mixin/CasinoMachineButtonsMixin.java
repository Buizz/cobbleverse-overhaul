package dev.buizz.cobbleventure.casino.mixin;

import net.narrnouille.cobblemoncasino.screen.widget.SlotButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the obsolete deposit and withdrawal controls from every casino game screen. */
@Mixin(targets = {
    "net.narrnouille.cobblemoncasino.screen.custom.slot.SlotMachineScreen",
    "net.narrnouille.cobblemoncasino.screen.custom.blackjack.BlackjackTableScreen"
}, remap = false)
abstract class CasinoMachineButtonsMixin {
    @Shadow private SlotButton betButton;
    @Shadow private SlotButton withdrawButton;

    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void cobbleventure$hideBalanceTransferButtons(CallbackInfo callback) {
        cobbleventure$hideButtons();
    }

    @Inject(method = "updateButtons", at = @At("RETURN"), require = 0, remap = false)
    private void cobbleventure$keepBlackjackButtonsHidden(CallbackInfo callback) {
        cobbleventure$hideButtons();
    }

    @Inject(method = "updateUiLockState", at = @At("RETURN"), require = 0, remap = false)
    private void cobbleventure$keepSlotButtonsHidden(CallbackInfo callback) {
        cobbleventure$hideButtons();
    }

    private void cobbleventure$hideButtons() {
        betButton.visible = false;
        betButton.active = false;
        withdrawButton.visible = false;
        withdrawButton.active = false;
    }
}
