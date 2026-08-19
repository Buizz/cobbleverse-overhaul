package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import dev.buizz.cobbleventure.adventure.PokemonCenterDefeatReturn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers a valid forfeit so its eventual victory event is handled as a player loss. */
@Mixin(value = ForfeitActionResponse.class, remap = false)
public abstract class ForfeitActionResponseMixin {
    @Inject(method = "isValid", at = @At("RETURN"))
    private void cobbleventure$recordForfeit(
        ActiveBattlePokemon activePokemon,
        ShowdownMoveset moveSet,
        boolean forceSwitch,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (callback.getReturnValueZ()
            && activePokemon.getActor() instanceof PlayerBattleActor playerActor) {
            PokemonCenterDefeatReturn.recordForfeit(playerActor);
        }
    }
}
