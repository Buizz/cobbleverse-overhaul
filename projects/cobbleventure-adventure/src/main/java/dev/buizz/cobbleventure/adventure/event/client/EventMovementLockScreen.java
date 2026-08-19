package dev.buizz.cobbleventure.adventure.event.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Transparent non-pausing screen that consumes player input during authored movement. */
final class EventMovementLockScreen extends Screen {
    EventMovementLockScreen() {
        super(Component.literal("CVES movement"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally transparent: the authored world movement remains visible.
    }
}
