package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.ItemAcquisition;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.playermenu.CobbleventurePlayerMenu;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Shows item rewards above both the normal HUD and an open EasyNPC screen. */
@EventBusSubscriber(modid = CobbleventurePlayerMenu.MOD_ID, value = Dist.CLIENT)
public final class ItemAcquisitionOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventurePlayerMenu.MOD_ID, "item_acquired"
    );
    private static final int DURATION_TICKS = ItemAcquisition.NOTICE_DURATION_TICKS;
    private static Notice notice;

    private ItemAcquisitionOverlay() {}

    public static void show(Component message, ResourceLocation sound) {
        notice = new Notice(message, System.nanoTime());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager().getSoundEvent(sound) == null) {
            minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0F, 0.8F)
            );
        } else {
            minecraft.getSoundManager().play(new SimpleSoundInstance(
                sound, SoundSource.PLAYERS, 0.8F, 1.0F, RandomSource.create(),
                false, 0, SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true
            ));
        }
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, ItemAcquisitionOverlay::renderHud);
    }

    @SubscribeEvent
    public static void renderScreen(ScreenEvent.Render.Post event) {
        render(event.getGuiGraphics());
    }

    private static void renderHud(GuiGraphics graphics, DeltaTracker ignored) {
        if (Minecraft.getInstance().screen == null) render(graphics);
    }

    private static void render(GuiGraphics graphics) {
        Notice current = notice;
        if (current == null) return;
        double elapsed = (System.nanoTime() - current.startedAt()) / 50_000_000.0D;
        if (elapsed >= DURATION_TICKS) {
            notice = null;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        String text = current.message().getString();
        int width = Math.min(320, minecraft.font.width(text) + 34);
        String visible = minecraft.font.plainSubstrByWidth(text, width - 34);
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 58;
        float alpha = (float)Math.min(1.0D, Math.min(elapsed / 5.0D, (DURATION_TICKS - elapsed) / 10.0D));
        int background = ((int)(alpha * 218.0F) << 24) | 0x142136;
        int border = ((int)(alpha * 255.0F) << 24) | 0xE9C74F;
        int foreground = ((int)(alpha * 255.0F) << 24) | 0xFFFFFF;

        RenderSystem.enableBlend();
        graphics.fill(x + 3, y + 3, x + width + 3, y + 29, ((int)(alpha * 90.0F) << 24));
        graphics.fill(x, y, x + width, y + 29, background);
        graphics.fill(x, y, x + width, y + 2, border);
        graphics.fill(x, y + 27, x + width, y + 29, border);
        graphics.drawCenteredString(minecraft.font, visible, x + width / 2, y + 10, foreground);
        RenderSystem.disableBlend();
    }

    private record Notice(Component message, long startedAt) {}
}
