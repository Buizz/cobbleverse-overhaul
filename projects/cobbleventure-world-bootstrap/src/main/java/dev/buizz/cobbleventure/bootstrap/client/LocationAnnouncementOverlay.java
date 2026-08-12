package dev.buizz.cobbleventure.bootstrap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
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

/** A compact lower-left location plaque instead of a center-screen title. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class LocationAnnouncementOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventureBootstrap.MOD_ID, "location_announcement"
    );
    private static final int DURATION_TICKS = 90;
    private static Announcement announcement;

    private LocationAnnouncementOverlay() {}

    public static void show(Component title, Component subtitle, boolean town) {
        announcement = new Announcement(title, subtitle, town, System.nanoTime());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.45F, 1.15F);
        }
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, LocationAnnouncementOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Announcement current = announcement;
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null || minecraft.options.hideGui) return;

        double elapsedTicks = (System.nanoTime() - current.startedAt()) / 50_000_000.0D;
        if (elapsedTicks >= DURATION_TICKS) {
            announcement = null;
            return;
        }

        Font font = minecraft.font;
        int maximumTextWidth = Math.max(font.width(current.title()), font.width(current.subtitle()));
        int plaqueWidth = Math.max(146, Math.min(238, maximumTextWidth + 34));
        int plaqueHeight = 43;
        double entrance = easeOutCubic(clamp(elapsedTicks / 10.0D));
        double exit = easeInCubic(clamp((elapsedTicks - 76.0D) / 14.0D));
        int x = 8 + (int)Math.round((-plaqueWidth - 18) * (1.0D - entrance) - plaqueWidth * exit);
        int y = Math.max(48, graphics.guiHeight() - plaqueHeight - 54);
        int accent = current.town() ? 0xFFFFC84A : 0xFF77C9FF;

        RenderSystem.enableBlend();
        graphics.fill(x + 4, y + 4, x + plaqueWidth + 4, y + plaqueHeight + 4, 0x50000000);
        graphics.fill(x, y, x + plaqueWidth, y + plaqueHeight, 0xD91A1D25);
        graphics.fill(x, y, x + 5, y + plaqueHeight, accent);
        graphics.fill(x + 5, y, x + plaqueWidth, y + 2, 0xA8FFFFFF);
        graphics.fill(x + 5, y + plaqueHeight - 2, x + plaqueWidth, y + plaqueHeight, 0x70000000);

        String visibleTitle = font.plainSubstrByWidth(
            current.title().getString(), plaqueWidth - 22
        );
        String visibleSubtitle = font.plainSubstrByWidth(
            current.subtitle().getString(), plaqueWidth - 22
        );
        graphics.drawString(font, visibleTitle, x + 13, y + 9, 0xFFFFFFFF, true);
        graphics.drawString(font, visibleSubtitle, x + 13, y + 25, 0xFFB9BDC8, false);
        RenderSystem.disableBlend();
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
        Component title, Component subtitle, boolean town, long startedAt
    ) {}
}
