package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.buizz.cobbleventure.adventure.daycare.DaycareProjectionService;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents daycare display entities from becoming gameplay Pokemon. */
@Mixin(PokemonEntity.class)
abstract class DaycareProjectionPokemonMixin {
    @Inject(method = "isUncatchable", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$projectionIsUncatchable(
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (isProjection()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "canBattle", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$projectionCannotBattle(
        Player player, CallbackInfoReturnable<Boolean> callback
    ) {
        if (isProjection()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$projectionIsTransient(
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (isProjection()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "canBeLeashed", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$projectionCannotBeLeashed(
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (isProjection()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$projectionIgnoresInteraction(
        Player player, InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> callback
    ) {
        if (isProjection()) {
            callback.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    private boolean isProjection() {
        return ((PokemonEntity) (Object) this).getTags()
            .contains(DaycareProjectionService.ENTITY_TAG);
    }
}
