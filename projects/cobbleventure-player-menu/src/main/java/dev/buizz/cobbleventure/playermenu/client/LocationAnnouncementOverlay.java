package dev.buizz.cobbleventure.playermenu.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.cobblemon.mod.common.client.CobblemonClient;
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
        Minecraft minecraft = Minecraft.getInstance();
        MenuTheme theme = MenuTheme.load(minecraft);
        announcement = new Announcement(
            title, subtitle, detail, town, theme, System.nanoTime()
        );
        currentArea = new CurrentArea(title, town, theme);
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
        if (minecraft.options.hideGui || CobblemonClient.INSTANCE.getBattle() != null) return;

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
        MenuTheme theme = current.theme();
        ThemedOverlayPanel.draw(
            graphics, theme, x, y, plaqueWidth, plaqueHeight, 1.0F,
            accentColor(theme, current.town())
        );
        String visibleTitle = font.plainSubstrByWidth(
            current.title().getString(), plaqueWidth - 22
        );
        String visibleSubtitle = font.plainSubstrByWidth(
            current.subtitle().getString(), plaqueWidth - 22
        );
        graphics.drawString(font, visibleTitle, x + 13, y + 9, theme.textColor, false);
        graphics.drawString(font, visibleSubtitle, x + 13, y + 25, theme.selectedTextColor, false);
        if (hasDetail) {
            String visibleDetail = font.plainSubstrByWidth(
                current.detail().getString(), plaqueWidth - 22
            );
            int detailColor = current.detail().getString().endsWith("클리어")
                && !current.detail().getString().endsWith("미클리어")
                    ? 0xFF277A4B : theme.accent;
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
        MenuTheme theme = area.theme();
        ThemedOverlayPanel.draw(
            graphics, theme, x, y, plaqueWidth, plaqueHeight, 1.0F,
            accentColor(theme, area.town())
        );
        String visibleTitle = font.plainSubstrByWidth(
            area.title().getString(), plaqueWidth - 22
        );
        graphics.drawString(font, visibleTitle, x + 13, y + 7, theme.textColor, false);
        RenderSystem.disableBlend();
    }

    private static int accentColor(MenuTheme theme, boolean town) {
        return town ? theme.accent : theme.border;
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
        Component title, Component subtitle, Component detail, boolean town,
        MenuTheme theme, long startedAt
    ) {}

    private record CurrentArea(Component title, boolean town, MenuTheme theme) {}
}
