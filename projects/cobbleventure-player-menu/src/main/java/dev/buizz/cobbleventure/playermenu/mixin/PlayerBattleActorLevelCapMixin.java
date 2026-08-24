package dev.buizz.cobbleventure.playermenu.mixin;

import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import dev.buizz.cobbleventure.playermenu.BattleLevelCap;
import java.util.List;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Caps every player-owned battle team without changing the stored Pokemon. */
@Mixin(PlayerBattleActor.class)
public abstract class PlayerBattleActorLevelCapMixin {
    @Inject(
        method = "<init>(Ljava/util/UUID;Ljava/util/List;)V",
        at = @At("RETURN")
    )
    private void cobbleventure$applyBattleLevelCap(
        UUID playerId, List<? extends BattlePokemon> ignored, CallbackInfo callback
    ) {
        PlayerBattleActor actor = (PlayerBattleActor) (Object) this;
        List<BattlePokemon> team = actor.getPokemonList();
        List<BattlePokemon> adjusted = BattleLevelCap.adjustPlayerTeam(playerId, team);
        team.clear();
        team.addAll(adjusted);
        for (BattlePokemon pokemon : adjusted) {
            pokemon.setActor(actor);
        }
    }
}
