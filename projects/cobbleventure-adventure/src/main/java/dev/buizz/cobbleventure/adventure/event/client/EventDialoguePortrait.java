package dev.buizz.cobbleventure.adventure.event.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Reuses Minecraft's inventory entity renderer for a live NPC dialogue portrait. */
final class EventDialoguePortrait {
    private EventDialoguePortrait() {}

    static LivingEntity find(Minecraft minecraft, UUID npcId) {
        if (minecraft == null || minecraft.level == null) return null;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getUUID().equals(npcId)
                && entity instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
        }
        return null;
    }

    static void render(
        GuiGraphics graphics, LivingEntity entity,
        int left, int top, int right, int bottom,
        int mouseX, int mouseY
    ) {
        if (entity == null || right <= left || bottom <= top) return;
        graphics.fill(left, top, right, bottom, 0xB80A1017);
        graphics.fill(left, top, right, top + 2, 0xFF5E7789);
        int scale = Math.max(30, Math.min(78, bottom - top - 8));
        graphics.enableScissor(
            Math.max(0, left), Math.max(0, top),
            Math.min(graphics.guiWidth(), right), Math.min(graphics.guiHeight(), bottom)
        );
        InventoryScreen.renderEntityInInventoryFollowsMouse(
            graphics, left, top, right, bottom, scale, 0.0625F,
            mouseX, mouseY, entity
        );
        graphics.disableScissor();
    }
}
