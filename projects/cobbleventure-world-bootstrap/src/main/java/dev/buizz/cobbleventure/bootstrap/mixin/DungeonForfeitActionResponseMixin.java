package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import com.cobblemon.mod.common.battles.ShowdownMoveset;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import dev.buizz.cobbleventure.bootstrap.DungeonBattleRules;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects forfeit requests when the active dungeon disables fleeing. */
@Mixin(value = ForfeitActionResponse.class, remap = false)
public abstract class DungeonForfeitActionResponseMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$enforceDungeonFleeRule(
        ActiveBattlePokemon activePokemon,
        ShowdownMoveset moveSet,
        boolean forceSwitch,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (activePokemon.getActor() instanceof PlayerBattleActor actor) {
            ServerPlayer player = actor.getEntity();
            if (player != null && !DungeonBattleRules.allowsFlee(player)) {
                callback.setReturnValue(false);
            }
        }
    }
}
