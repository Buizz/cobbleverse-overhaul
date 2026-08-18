package dev.buizz.cobbleventure.bootstrap.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.buizz.cobbleventure.bootstrap.client.GymBlockerVisibility;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Omits gym blocker NPCs that the local player has already unlocked. */
@Mixin(EntityRenderDispatcher.class)
public abstract class GymBlockerEntityRenderMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void cobbleventure$hideUnlockedGymBlocker(
        E entity, double x, double y, double z, float yaw, float partialTick,
        PoseStack poseStack, MultiBufferSource buffers, int packedLight,
        CallbackInfo callback
    ) {
        if (GymBlockerVisibility.isHidden(entity)) {
            callback.cancel();
        }
    }
}
