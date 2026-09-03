package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.client.GymBlockerVisibility;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents an invisible gym blocker from consuming the local player's crosshair. */
@Mixin(LivingEntity.class)
public abstract class GymBlockerPickMixin {
    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$ignoreHiddenGymBlocker(
        CallbackInfoReturnable<Boolean> callback
    ) {
        var gatePokemon = dev.buizz.cobbleventure.bootstrap.GatePokemonNetwork.clientView((LivingEntity) (Object) this);
        if (GymBlockerVisibility.isHidden((LivingEntity) (Object) this) || (gatePokemon != null && gatePokemon.hidden())) {
            callback.setReturnValue(false);
        }
    }
}
