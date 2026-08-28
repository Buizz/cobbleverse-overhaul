package dev.buizz.cobbleventure.bootstrap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
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
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Full-screen transition retained across dungeon preparation and dimension loading. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class DungeonTransitionOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventureBootstrap.MOD_ID, "dungeon_transition"
    );
    private static final int BACKGROUND_WIDTH = 1672;
    private static final int BACKGROUND_HEIGHT = 940;
    private static final long FADE_IN_NANOS = 480_000_000L;
    private static final long MINIMUM_HOLD_NANOS = 900_000_000L;
    private static final long REVEAL_NANOS = 650_000_000L;
    private static final long CANCEL_NANOS = 240_000_000L;
    private static final long MAXIMUM_WAIT_NANOS = 60_000_000_000L;
    private static TransitionState state;

    private DungeonTransitionOverlay() {}

    public static void start(String dungeonName, String backgroundTexture) {
        TransitionState current = state;
        if (current != null
            && current.dungeonName.equals(dungeonName)
            && current.background.toString().equals(backgroundTexture)
            && !current.cancelled) {
            return;
        }
        state = new TransitionState(
            dungeonName,
            ResourceLocation.parse(backgroundTexture),
            System.nanoTime()
        );
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.BEACON_ACTIVATE, 0.42F, 0.72F);
        }
    }

    public static void finish() {
        TransitionState current = state;
        if (current == null || current.cancelled || current.finishedAt != 0L) return;
        current.finishedAt = System.nanoTime();
    }

    public static void cancel() {
        TransitionState current = state;
        if (current == null || current.cancelled) return;
        current.cancelled = true;
        current.finishedAt = System.nanoTime();
    }

    public static void clear() {
        state = null;
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, DungeonTransitionOverlay::renderHud);
    }

    @SubscribeEvent
    public static void renderScreen(ScreenEvent.Render.Post event) {
        render(event.getGuiGraphics());
    }

    private static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (Minecraft.getInstance().screen == null) render(graphics);
    }

    private static void render(GuiGraphics graphics) {
        TransitionState transition = state;
        if (transition == null) return;

        long now = System.nanoTime();
        long age = Math.max(0L, now - transition.startedAt);
        if (transition.finishedAt == 0L && age >= MAXIMUM_WAIT_NANOS) {
            transition.cancelled = true;
            transition.finishedAt = now;
        }
        double fadeIn = smooth(clamp(age / (double)FADE_IN_NANOS));
        long revealEligibleAt = transition.cancelled
            ? transition.finishedAt
            : Math.max(
                transition.finishedAt,
                transition.startedAt + MINIMUM_HOLD_NANOS
            );
        if (transition.finishedAt != 0L && now >= revealEligibleAt
            && transition.revealStartedAt == 0L) {
            // Start from the first rendered frame after loading. Wall-clock time can
            // advance while Minecraft replaces the level and renders no GUI frames.
            transition.revealStartedAt = now;
        }
        double fadeOut = transition.revealStartedAt == 0L ? 0.0D
            : smooth(clamp(
                (now - transition.revealStartedAt) / (double)(transition.cancelled
                    ? CANCEL_NANOS : REVEAL_NANOS)
            ));
        double opacity = fadeIn * (1.0D - fadeOut);
        if (transition.finishedAt != 0L && fadeOut >= 1.0D) {
            if (state == transition) state = null;
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int alpha = (int)Math.round(opacity * 255.0D);
        if (alpha <= 0) return;

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, (float)opacity);
        graphics.blit(
            transition.background, 0, 0, width, height,
            0.0F, 0.0F, BACKGROUND_WIDTH, BACKGROUND_HEIGHT,
            BACKGROUND_WIDTH, BACKGROUND_HEIGHT
        );
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int darkness = (int)Math.round((92.0D + 126.0D * fadeIn) * opacity);
        graphics.fill(0, 0, width, height, darkness << 24 | 0x03070D);
        drawClosingPanels(graphics, width, height, fadeIn, opacity);
        drawTitle(graphics, transition, width, height, age, alpha);
        RenderSystem.disableBlend();
    }

    private static void drawClosingPanels(
        GuiGraphics graphics,
        int width,
        int height,
        double fadeIn,
        double opacity
    ) {
        int travel = (int)Math.round((1.0D - fadeIn) * width * 0.62D);
        int redAlpha = (int)Math.round(86.0D * opacity);
        int darkAlpha = (int)Math.round(150.0D * opacity);
        fillSlanted(
            graphics, -travel - width / 3, width / 2 - 18 - travel,
            0, height, 92, darkAlpha << 24 | 0x310C15
        );
        fillSlanted(
            graphics, width / 2 + 18 + travel, width + travel + width / 3,
            0, height, -92, darkAlpha << 24 | 0x101B2A
        );
        int lineWidth = Math.max(2, width / 320);
        graphics.fill(
            width / 2 - lineWidth, 0, width / 2 + lineWidth, height,
            redAlpha << 24 | 0xFF6878
        );
        int letterbox = Math.max(8, height / 24);
        graphics.fill(0, 0, width, letterbox, (int)(190 * opacity) << 24);
        graphics.fill(
            0, height - letterbox, width, height, (int)(190 * opacity) << 24
        );
    }

    private static void drawTitle(
        GuiGraphics graphics,
        TransitionState transition,
        int width,
        int height,
        long age,
        int alpha
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int centerX = width / 2;
        int centerY = height / 2;
        int textAlpha = Math.min(255, alpha);
        int mutedAlpha = Math.min(220, alpha);

        String eyebrow = "DUNGEON ENTRY";
        graphics.drawString(
            font, eyebrow, centerX - font.width(eyebrow) / 2,
            centerY - 39, textAlpha << 24 | 0xFF8794, true
        );

        String title = font.plainSubstrByWidth(
            transition.dungeonName, Math.max(80, width / 2)
        );
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY - 18, 500.0F);
        graphics.pose().scale(1.65F, 1.65F, 1.0F);
        graphics.drawString(
            font, title, -font.width(title) / 2, 0,
            textAlpha << 24 | 0xFFFFFF, true
        );
        graphics.pose().popPose();

        String status = transition.finishedAt == 0L
            ? "던전을 준비하는 중입니다"
            : transition.cancelled ? "입장이 취소되었습니다" : "진입 완료";
        graphics.drawString(
            font, status, centerX - font.width(status) / 2,
            centerY + 14,
            mutedAlpha << 24 | (transition.cancelled ? 0xFF9B87 : 0xCAD5E2),
            true
        );

        int trackWidth = Math.min(190, width / 3);
        int trackLeft = centerX - trackWidth / 2;
        int trackY = centerY + 34;
        graphics.fill(
            trackLeft, trackY, trackLeft + trackWidth, trackY + 2,
            Math.min(140, alpha) << 24 | 0x425165
        );
        double pulse = (age % 1_200_000_000L) / 1_200_000_000.0D;
        int segmentWidth = Math.max(28, trackWidth / 4);
        int segmentX = trackLeft
            + (int)Math.round((trackWidth - segmentWidth) * pulse);
        graphics.fill(
            segmentX, trackY, segmentX + segmentWidth, trackY + 2,
            textAlpha << 24 | 0xFF6878
        );
    }

    private static void fillSlanted(
        GuiGraphics graphics,
        int left,
        int right,
        int top,
        int bottom,
        int skew,
        int color
    ) {
        int height = Math.max(1, bottom - top);
        for (int y = top; y < bottom; y += 3) {
            int shift = skew * (y - top) / height;
            graphics.fill(
                left + shift, y, right + shift, Math.min(bottom, y + 3), color
            );
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double smooth(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static final class TransitionState {
        private final String dungeonName;
        private final ResourceLocation background;
        private final long startedAt;
        private long finishedAt;
        private long revealStartedAt;
        private boolean cancelled;

        private TransitionState(
            String dungeonName, ResourceLocation background, long startedAt
        ) {
            this.dungeonName = dungeonName;
            this.background = background;
            this.startedAt = startedAt;
        }
    }
}
