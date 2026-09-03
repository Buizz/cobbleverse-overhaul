package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.buizz.cobbleventure.bootstrap.GatePokemonNetwork;
import dev.buizz.cobbleventure.bootstrap.GatePokemonSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only tagged story displays are immutable; battle encounters remain normal Pokemon. */
@Mixin(PokemonEntity.class)
abstract class GatePokemonActorMixin {
    @Inject(method = "isUncatchable", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$uncatchable(CallbackInfoReturnable<Boolean> ci) {
        if (actor()) ci.setReturnValue(true);
    }
    @Inject(method = "canBattle", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$eventBattleOnly(net.minecraft.world.entity.player.Player player, CallbackInfoReturnable<Boolean> ci) {
        PokemonEntity entity = (PokemonEntity) (Object) this;
        if (actor() || (entity.getPersistentData().hasUUID(GatePokemonSystem.CHALLENGER_KEY)
                && !entity.getPersistentData().getUUID(GatePokemonSystem.CHALLENGER_KEY).equals(player.getUUID()))) ci.setReturnValue(false);
    }
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$transientActor(CallbackInfoReturnable<Boolean> ci) {
        PokemonEntity entity = (PokemonEntity) (Object) this;
        if (actor() || entity.getPersistentData().hasUUID(GatePokemonSystem.CHALLENGER_KEY)) ci.setReturnValue(false);
    }
    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$fixedActor(CallbackInfoReturnable<Boolean> ci) {
        if (actor()) ci.setReturnValue(false);
    }
    @Inject(method = "canBeLeashed", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$noLeash(CallbackInfoReturnable<Boolean> ci) {
        if (actor()) ci.setReturnValue(false);
    }
    @Inject(method = "tick", at = @At("TAIL"))
    private void cobbleventure$poseAndBounds(CallbackInfo ci) {
        PokemonEntity entity = (PokemonEntity) (Object) this;
        var bounds = GatePokemonSystem.actorBounds(entity);
        if (bounds == null) return;
        entity.setBoundingBox(bounds);
        if (entity.level().isClientSide()) {
            var view = GatePokemonNetwork.clientView(entity);
            if (view != null) {
                entity.setEnablePoseTypeRecalculation(false);
                entity.getEntityData().set(PokemonEntity.getPOSE_TYPE(), view.pose().equals("sleep") ? PoseType.SLEEP : PoseType.STAND);
            }
        }
    }
    private boolean actor() {
        PokemonEntity entity = (PokemonEntity) (Object) this;
        return entity.getTags().contains(GatePokemonSystem.ACTOR_TAG)
            || (entity.level().isClientSide() && GatePokemonNetwork.clientView(entity) != null);
    }
}
