package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.playermenu.CobbleventurePlayerMenu;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Cinematic letterbox shown when a proximity trainer notices the player. */
@EventBusSubscriber(modid = CobbleventurePlayerMenu.MOD_ID, value = Dist.CLIENT)
public final class BattleWarningOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventurePlayerMenu.MOD_ID, "battle_warning"
    );
    private static WarningState state;

    private BattleWarningOverlay() {}

    public static void start(String opponentName) {
        state = new WarningState(opponentName, System.nanoTime());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.75F, 0.72F);
        }
    }

    public static void stop() {
        state = null;
    }

    public static void dismiss() {
        if (state != null && state.closingAt == 0L) {
            state.closingAt = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, BattleWarningOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        WarningState warning = state;
        if (warning == null) return;
        double elapsedTicks = (System.nanoTime() - warning.startedAt) / 50_000_000.0D;
        double entrance = smoothStep(clamp(elapsedTicks / 8.0D));
        double exit = warning.closingAt == 0L ? 0.0D : smoothStep(clamp(
            (System.nanoTime() - warning.closingAt) / 400_000_000.0D
        ));
        if (exit >= 1.0D) {
            state = null;
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int targetHeight = Math.max(30, Math.min(72, height * 14 / 100));
        double visibility = entrance * (1.0D - exit);
        int barHeight = Math.max(1, (int)Math.round(targetHeight * visibility));
        int alpha = (int)Math.round(166.0D * visibility);

        RenderSystem.enableBlend();
        int color = alpha << 24;
        graphics.fill(0, 0, width, barHeight, color);
        graphics.fill(0, height - barHeight, width, height, color);

        if (visibility > 0.45D) {
            Minecraft minecraft = Minecraft.getInstance();
            Font font = minecraft.font;
            int textAlpha = Math.min(255, (int)Math.round(255.0D * visibility));
            String title = warning.opponentName + "에게 발각되었습니다";
            String subtitle = "더 가까이 접근하면 배틀이 시작됩니다";
            graphics.drawCenteredString(
                font, title, width / 2, Math.max(7, barHeight / 2 - font.lineHeight / 2),
                textAlpha << 24 | 0xFFD05A
            );
            graphics.drawCenteredString(
                font, subtitle, width / 2,
                height - Math.max(font.lineHeight + 7, barHeight / 2 + font.lineHeight / 2),
                textAlpha << 24 | 0xFFFFFF
            );
        }
        RenderSystem.disableBlend();
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double smoothStep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static final class WarningState {
        private final String opponentName;
        private final long startedAt;
        private long closingAt;

        private WarningState(String opponentName, long startedAt) {
            this.opponentName = opponentName;
            this.startedAt = startedAt;
        }
    }
}
