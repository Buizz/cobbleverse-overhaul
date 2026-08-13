package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.playermenu.CobbleventurePlayerMenu;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** A top-left entry plaque that settles into a persistent current-area label. */
@EventBusSubscriber(modid = CobbleventurePlayerMenu.MOD_ID, value = Dist.CLIENT)
public final class LocationAnnouncementOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventurePlayerMenu.MOD_ID, "location_announcement"
    );
    private static final int DURATION_TICKS = 90;
    private static Announcement announcement;
    private static CurrentArea currentArea;

    private LocationAnnouncementOverlay() {}

    public static void show(
        Component title, Component subtitle, Component detail, boolean town
    ) {
        announcement = new Announcement(title, subtitle, detail, town, System.nanoTime());
        currentArea = new CurrentArea(title, town);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.playSound(
                SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.45F, 1.15F
            );
        }
    }

    public static void clear() {
        announcement = null;
        currentArea = null;
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, LocationAnnouncementOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) return;

        Announcement current = announcement;
        if (current != null) {
            double elapsedTicks = (System.nanoTime() - current.startedAt()) / 50_000_000.0D;
            if (elapsedTicks < DURATION_TICKS) {
                renderAnnouncement(graphics, minecraft.font, current, elapsedTicks);
                return;
            }
            announcement = null;
        }
        if (currentArea != null) {
            renderCurrentArea(graphics, minecraft.font, currentArea);
        }
    }

    private static void renderAnnouncement(
        GuiGraphics graphics, Font font, Announcement current, double elapsedTicks
    ) {
        int maximumTextWidth = Math.max(
            Math.max(font.width(current.title()), font.width(current.subtitle())),
            font.width(current.detail())
        );
        int plaqueWidth = Math.max(146, Math.min(238, maximumTextWidth + 34));
        boolean hasDetail = !current.detail().getString().isEmpty();
        int plaqueHeight = hasDetail ? 57 : 43;
        double entrance = easeOutCubic(clamp(elapsedTicks / 10.0D));
        double exit = easeInCubic(clamp((elapsedTicks - 76.0D) / 14.0D));
        int x = 8 + (int)Math.round(
            (-plaqueWidth - 18) * (1.0D - entrance) - plaqueWidth * exit
        );
        int y = 8;

        RenderSystem.enableBlend();
        drawPlaque(graphics, x, y, plaqueWidth, plaqueHeight, accentColor(current.town()));
        String visibleTitle = font.plainSubstrByWidth(
            current.title().getString(), plaqueWidth - 22
        );
        String visibleSubtitle = font.plainSubstrByWidth(
            current.subtitle().getString(), plaqueWidth - 22
        );
        graphics.drawString(font, visibleTitle, x + 13, y + 9, 0xFFFFFFFF, true);
        graphics.drawString(font, visibleSubtitle, x + 13, y + 25, 0xFFB9BDC8, false);
        if (hasDetail) {
            String visibleDetail = font.plainSubstrByWidth(
                current.detail().getString(), plaqueWidth - 22
            );
            int detailColor = current.detail().getString().endsWith("클리어")
                && !current.detail().getString().endsWith("미클리어")
                    ? 0xFF8DE59B : 0xFFD8D0AA;
            graphics.drawString(font, visibleDetail, x + 13, y + 39, detailColor, false);
        }
        RenderSystem.disableBlend();
    }

    private static void renderCurrentArea(
        GuiGraphics graphics, Font font, CurrentArea area
    ) {
        int plaqueWidth = Math.max(88, Math.min(190, font.width(area.title()) + 29));
        int plaqueHeight = 23;
        int x = 8;
        int y = 8;

        RenderSystem.enableBlend();
        drawPlaque(graphics, x, y, plaqueWidth, plaqueHeight, accentColor(area.town()));
        String visibleTitle = font.plainSubstrByWidth(
            area.title().getString(), plaqueWidth - 22
        );
        graphics.drawString(font, visibleTitle, x + 13, y + 7, 0xFFFFFFFF, true);
        RenderSystem.disableBlend();
    }

    private static void drawPlaque(
        GuiGraphics graphics, int x, int y, int width, int height, int accent
    ) {
        graphics.fill(x + 4, y + 4, x + width + 4, y + height + 4, 0x50000000);
        graphics.fill(x, y, x + width, y + height, 0xD91A1D25);
        graphics.fill(x, y, x + 5, y + height, accent);
        graphics.fill(x + 5, y, x + width, y + 2, 0xA8FFFFFF);
        graphics.fill(x + 5, y + height - 2, x + width, y + height, 0x70000000);
    }

    private static int accentColor(boolean town) {
        return town ? 0xFFFFC84A : 0xFF77C9FF;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double easeOutCubic(double value) {
        double inverse = 1.0D - value;
        return 1.0D - inverse * inverse * inverse;
    }

    private static double easeInCubic(double value) {
        return value * value * value;
    }

    private record Announcement(
        Component title, Component subtitle, Component detail, boolean town, long startedAt
    ) {}

    private record CurrentArea(Component title, boolean town) {}
}
