package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/** Shared opening transition for screens owned by the player-menu mod. */
final class MenuOpeningEffect {
    private static final long DURATION_MILLIS = 150L;
    private long startedAt;

    void start(Minecraft minecraft, SoundEvent sound, float pitch, float volume) {
        startedAt = System.currentTimeMillis();
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }

    boolean finished() {
        return System.currentTimeMillis() - startedAt >= DURATION_MILLIS;
    }

    void begin(GuiGraphics graphics, int width, int height) {
        float progress = progress();
        float scale = 0.965F + progress * 0.035F;
        graphics.pose().pushPose();
        graphics.pose().translate(width / 2.0F, height / 2.0F + (1.0F - progress) * 9.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate(-width / 2.0F, -height / 2.0F, 0.0F);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, Math.max(0.2F, progress));
    }

    void end(GuiGraphics graphics) {
        graphics.pose().popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private float progress() {
        float linear = Math.max(0.0F, Math.min(
            1.0F,
            (System.currentTimeMillis() - startedAt) / (float) DURATION_MILLIS
        ));
        float inverse = 1.0F - linear;
        return 1.0F - inverse * inverse * inverse;
    }
}
