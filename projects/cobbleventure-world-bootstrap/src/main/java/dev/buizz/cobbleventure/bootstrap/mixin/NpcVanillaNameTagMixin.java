package dev.buizz.cobbleventure.bootstrap.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.buizz.cobbleventure.bootstrap.client.NpcNameTagRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents the vanilla dark nameplate from being drawn behind themed NPC nameplates. */
@Mixin(EntityRenderer.class)
public abstract class NpcVanillaNameTagMixin<T extends Entity> {
    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$hideVanillaNpcNameTag(
        T entity,
        Component name,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        float partialTick,
        CallbackInfo callback
    ) {
        if (NpcNameTagRenderer.handles(entity)) {
            callback.cancel();
        }
    }
}
