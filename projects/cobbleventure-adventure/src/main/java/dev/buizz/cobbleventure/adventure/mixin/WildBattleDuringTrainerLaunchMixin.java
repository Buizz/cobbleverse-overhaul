package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.buizz.cobbleventure.adventure.event.EventBattleBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Closes the delayed trainer-launch window where a wild battle could win the race. */
@Mixin(PokemonEntity.class)
abstract class WildBattleDuringTrainerLaunchMixin {
    @Inject(method = "canBattle", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$blockWildBattleDuringTrainerLaunch(
        Player player, CallbackInfoReturnable<Boolean> callback
    ) {
        PokemonEntity pokemon = (PokemonEntity) (Object) this;
        if (player instanceof ServerPlayer serverPlayer
            && pokemon.getPokemon().isWild()
            && EventBattleBridge.hasPendingTrainerBattle(serverPlayer.getUUID())) {
            callback.setReturnValue(false);
        }
    }
}
