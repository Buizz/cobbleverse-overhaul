package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.playermenu.CobbleventurePlayerMenu;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Full-screen, main-series-inspired trainer versus cut-in. */
@EventBusSubscriber(modid = CobbleventurePlayerMenu.MOD_ID, value = Dist.CLIENT)
public final class BattleIntroOverlay {
    private static final int PORTRAIT_Y_OFFSET = 21;
    private static final float PORTRAIT_INWARD_ANGLE = 0.65F;
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventurePlayerMenu.MOD_ID, "battle_intro"
    );
    private static IntroState state;

    private BattleIntroOverlay() {}

    public static void start(
        int playerEntityId,
        int opponentEntityId,
        String playerName,
        String opponentName,
        int durationTicks
    ) {
        BattleWarningOverlay.stop();
        state = new IntroState(
            playerEntityId,
            opponentEntityId,
            playerName,
            opponentName,
            Math.max(20, durationTicks),
            System.nanoTime()
        );
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.9F, 0.72F);
        }
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, BattleIntroOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        IntroState intro = state;
        if (intro == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        double elapsedTicks = (System.nanoTime() - intro.startedAt) / 50_000_000.0D;
        double progress = clamp(elapsedTicks / intro.durationTicks);
        if (progress >= 1.0D) {
            state = null;
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int centerX = width / 2;
        int bandHeightTarget = Math.max(116, Math.min(height * 46 / 100, 260));
        int bandTop = (height - bandHeightTarget) / 2;
        int bandBottom = bandTop + bandHeightTarget;
        int bandHeight = bandBottom - bandTop;
        double entrance = easeOutBack(clamp(progress / 0.32D));
        double vsEntrance = easeOutBack(clamp((progress - 0.24D) / 0.22D));
        double exit = easeInCubic(clamp((progress - 0.78D) / 0.22D));
        int leftOffset = (int)Math.round((-width * 0.58D) * (1.0D - entrance) - width * 0.45D * exit);
        int rightOffset = (int)Math.round((width * 0.58D) * (1.0D - entrance) + width * 0.45D * exit);

        RenderSystem.enableBlend();
        graphics.fill(0, bandTop - 5, width, bandBottom + 5, 0x7A11141D);
        drawSpeedLines(graphics, width, bandTop, bandBottom, progress);
        drawPanels(graphics, centerX, bandTop, bandBottom, leftOffset, rightOffset);

        Entity own = minecraft.level == null ? null : minecraft.level.getEntity(intro.playerEntityId);
        Entity opponent = minecraft.level == null ? null : minecraft.level.getEntity(intro.opponentEntityId);
        // Portraits intentionally break out above the coloured band. Keeping
        // the scissor at the band edge clipped heads and hats behind its top
        // border, making the trainers look embedded in the panel.
        int portraitTop = Math.max(0, bandTop - bandHeight / 2) + PORTRAIT_Y_OFFSET;
        int portraitBottom = bandBottom - Math.max(24, bandHeight / 7) + PORTRAIT_Y_OFFSET;
        renderPortrait(
            graphics,
            own,
            width / 18 + leftOffset,
            portraitTop,
            centerX - width / 18 + leftOffset,
            portraitBottom,
            -PORTRAIT_INWARD_ANGLE
        );
        renderPortrait(
            graphics,
            opponent,
            centerX + width / 18 + rightOffset,
            portraitTop,
            width - width / 18 + rightOffset,
            portraitBottom,
            PORTRAIT_INWARD_ANGLE
        );

        Font font = minecraft.font;
        int nameY = bandBottom - Math.max(22, bandHeight / 9);
        drawNamePlate(
            graphics, font, intro.playerName,
            width / 18 + leftOffset, centerX - width / 15 + leftOffset,
            nameY, 0xFF53D9FF, false
        );
        drawNamePlate(
            graphics, font, intro.opponentName,
            centerX + width / 15 + rightOffset, width - width / 18 + rightOffset,
            nameY, 0xFFFF5866, true
        );

        if (vsEntrance > 0.0D) {
            double pulse = 1.0D + Math.sin(elapsedTicks * 0.38D) * 0.025D;
            float scale = (float)((2.4D + 1.35D * vsEntrance) * pulse);
            drawVs(graphics, font, centerX, height / 2, scale);
        }

        if (!intro.impactPlayed && progress >= 0.38D) {
            intro.impactPlayed = true;
            if (minecraft.player != null) {
                minecraft.player.playSound(SoundEvents.ANVIL_LAND, 0.42F, 1.48F);
            }
        }

        double openingFlash = 1.0D - clamp(progress / 0.075D);
        double impactFlash = 1.0D - clamp(Math.abs(progress - 0.39D) / 0.055D);
        int flashAlpha = (int)Math.round(Math.max(openingFlash * 150.0D, impactFlash * 118.0D));
        if (flashAlpha > 0) {
            graphics.fill(0, bandTop - 5, width, bandBottom + 5, flashAlpha << 24 | 0xFFFFFF);
        }
        int closeAlpha = (int)Math.round(clamp((progress - 0.90D) / 0.10D) * 255.0D);
        if (closeAlpha > 0) {
            graphics.fill(0, bandTop - 5, width, bandBottom + 5, closeAlpha << 24);
        }
        RenderSystem.disableBlend();
    }

    private static void drawPanels(
        GuiGraphics graphics, int centerX, int top, int bottom, int leftOffset, int rightOffset
    ) {
        int height = bottom - top;
        fillSlanted(graphics, -120 + leftOffset, centerX - 13 + leftOffset, top, bottom, 44, 0xF01A3150);
        fillSlanted(graphics, -95 + leftOffset, centerX - 24 + leftOffset, top + 5, bottom - 5, 36, 0xEB126A91);
        fillSlanted(graphics, centerX + 13 + rightOffset, centerX * 2 + 120 + rightOffset, top, bottom, -44, 0xF04B1828);
        fillSlanted(graphics, centerX + 24 + rightOffset, centerX * 2 + 95 + rightOffset, top + 5, bottom - 5, -36, 0xEBAE263C);
        for (int index = -3; index <= 3; index++) {
            int x = centerX + index * 5;
            graphics.fill(x - 1, top - height / 14, x + 2, bottom + height / 14, index == 0 ? 0xFFFFFFFF : 0xA0FFFFFF);
        }
        graphics.fill(0, top - 3, centerX * 2, top, 0xD8FFFFFF);
        graphics.fill(0, bottom, centerX * 2, bottom + 3, 0xD8FFFFFF);
    }

    private static void drawSpeedLines(
        GuiGraphics graphics, int width, int top, int bottom, double progress
    ) {
        int travel = (int)Math.round(progress * width * 2.2D);
        for (int index = -8; index < 18; index++) {
            int x = Math.floorMod(index * 73 + travel, width + 180) - 90;
            int alpha = 28 + Math.floorMod(index * 19, 34);
            fillSlanted(graphics, x, x + 54, top, bottom, 72, alpha << 24 | 0xDDEBFF);
        }
    }

    private static void fillSlanted(
        GuiGraphics graphics, int left, int right, int top, int bottom, int skew, int color
    ) {
        int height = Math.max(1, bottom - top);
        for (int y = top; y < bottom; y += 2) {
            int shift = skew * (y - top) / height;
            graphics.fill(left + shift, y, right + shift, Math.min(bottom, y + 2), color);
        }
    }

    private static void renderPortrait(
        GuiGraphics graphics,
        Entity entity,
        int left,
        int top,
        int right,
        int bottom,
        float inwardAngle
    ) {
        if (!(entity instanceof LivingEntity living) || right <= left || bottom <= top) return;
        int clipLeft = Math.max(0, left);
        int clipTop = Math.max(0, top);
        int clipRight = Math.min(graphics.guiWidth(), right);
        int clipBottom = Math.min(graphics.guiHeight(), bottom);
        if (clipRight <= clipLeft || clipBottom <= clipTop) return;
        int scale = Math.max(42, Math.min(96, bottom - top - 8));
        graphics.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
        InventoryScreen.renderEntityInInventoryFollowsAngle(
            graphics, left, top, right, bottom, scale, 0.0625F,
            inwardAngle, 0.0F, living
        );
        graphics.disableScissor();
    }

    private static void drawNamePlate(
        GuiGraphics graphics,
        Font font,
        String name,
        int left,
        int right,
        int y,
        int accent,
        boolean alignRight
    ) {
        if (right <= left) return;
        graphics.fill(left, y - 5, right, y + 14, 0xC8141720);
        graphics.fill(left, y - 5, right, y - 2, accent);
        String visible = font.plainSubstrByWidth(name, Math.max(10, right - left - 12));
        int x = alignRight ? right - font.width(visible) - 6 : left + 6;
        graphics.drawString(font, visible, x, y + 1, 0xFFFFFFFF, true);
    }

    private static void drawVs(GuiGraphics graphics, Font font, int centerX, int centerY, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 500.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        int width = font.width("VS");
        graphics.drawString(font, "VS", -width / 2 + 1, -font.lineHeight / 2 + 1, 0xFF5A101A, false);
        graphics.drawString(font, "VS", -width / 2, -font.lineHeight / 2, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double easeInCubic(double value) {
        return value * value * value;
    }

    private static double easeOutBack(double value) {
        double shifted = value - 1.0D;
        return 1.0D + 2.70158D * shifted * shifted * shifted + 1.70158D * shifted * shifted;
    }

    private static final class IntroState {
        private final int playerEntityId;
        private final int opponentEntityId;
        private final String playerName;
        private final String opponentName;
        private final int durationTicks;
        private final long startedAt;
        private boolean impactPlayed;

        private IntroState(
            int playerEntityId,
            int opponentEntityId,
            String playerName,
            String opponentName,
            int durationTicks,
            long startedAt
        ) {
            this.playerEntityId = playerEntityId;
            this.opponentEntityId = opponentEntityId;
            this.playerName = playerName;
            this.opponentName = opponentName;
            this.durationTicks = durationTicks;
            this.startedAt = startedAt;
        }
    }
}
