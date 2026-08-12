package dev.buizz.cobbleventure.playermenu.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Fits Cobblemon XP Bar beside Battle Extras' speed range on the player battle tile. */
@Mixin(targets = "com.cobblemonxpbar.client.XPBarOverlay", remap = false)
abstract class XPBarOverlayMixin {
    private static final float COMPATIBLE_WIDTH_SCALE = 0.68F;

    @Redirect(
        method = "lambda$registerOverlays$3",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"
        ),
        remap = false
    )
    private static void cobbleventure$fitXpBarBesideSpeedRange(
        GuiGraphics graphics,
        ResourceLocation texture,
        int x,
        int y,
        float u,
        float v,
        int width,
        int height,
        int textureWidth,
        int textureHeight
    ) {
        if (!texture.getNamespace().equals("cobblemonxpbar")) {
            graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
            return;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(x, 0.0F, 0.0F);
        graphics.pose().scale(COMPATIBLE_WIDTH_SCALE, 1.0F, 1.0F);
        graphics.pose().translate(-x, 0.0F, 0.0F);
        graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight);
        graphics.pose().popPose();
    }
}
