package dev.buizz.cobbleventure.bootstrap.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Create's in-world Nixie and elevator labels visible while Caxton handles GUI text. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.redstone.nixieTube.NixieTubeRenderer")
public abstract class CreateNixieTubeFontMixin {
    private static final ResourceLocation WORLD_LABEL_FONT =
        ResourceLocation.withDefaultNamespace("uniform");

    @Inject(method = "drawInWorldString", at = @At("HEAD"), cancellable = true, remap = false)
    private static void cobbleventure$useWorldLabelFont(
        PoseStack poseStack,
        MultiBufferSource buffer,
        String text,
        int color,
        CallbackInfo callback
    ) {
        Font font = Minecraft.getInstance().font;
        font.drawInBatch(
            Component.literal(text)
                .withStyle(style -> style.withFont(WORLD_LABEL_FONT))
                .getVisualOrderText(),
            0,
            0,
            color,
            false,
            poseStack.last().pose(),
            buffer,
            Font.DisplayMode.NORMAL,
            0,
            LightTexture.FULL_BRIGHT
        );

        if (buffer instanceof BufferSource bufferSource) {
            BakedGlyph whiteGlyph = ((MinecraftFontAccessor) font)
                .cobbleventure$getFontSet(WORLD_LABEL_FONT)
                .whiteGlyph();
            bufferSource.endBatch(whiteGlyph.renderType(Font.DisplayMode.NORMAL));
        }

        callback.cancel();
    }
}
