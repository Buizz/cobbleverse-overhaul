package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects forged or stale forfeit actions for NPC trainer battles server-side. */
@Mixin(value = ForfeitActionResponse.class, remap = false)
public abstract class ForfeitActionResponseMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$rejectTrainerForfeit(
        ActiveBattlePokemon activePokemon,
        ShowdownMoveset moveSet,
        boolean forceSwitch,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (activePokemon.getActor().getBattle().isPvN()) {
            callback.setReturnValue(false);
        }
    }
}
