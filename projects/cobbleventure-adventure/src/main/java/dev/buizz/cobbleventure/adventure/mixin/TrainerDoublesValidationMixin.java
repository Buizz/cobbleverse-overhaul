package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.gitlab.srcmc.rctapi.api.battle.BattleContext;
import com.gitlab.srcmc.rctapi.api.battle.BattleContextValidator;
import dev.buizz.cobbleventure.adventure.event.UnderfilledTrainerDoubles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BattleContextValidator.class, remap = false)
public abstract class TrainerDoublesValidationMixin {
    @Inject(method = "validate", at = @At("RETURN"))
    private void cobbleventure$allowEmptySecondPlayerSlot(
        ErroredBattleStart errors, BattleContext context,
        CallbackInfoReturnable<ErroredBattleStart> callback
    ) {
        UnderfilledTrainerDoubles.relaxPlayerCount(callback.getReturnValue(), context);
    }
}
