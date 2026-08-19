package dev.buizz.cobbleventure.adventure.event.client;

import dev.buizz.cobbleventure.adventure.event.EventPresentationGateway;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Non-pausing fade-out/fade-in overlay controlled by the server presentation await. */
final class EventFadeScreen extends Screen {
    private static final long HALF_DURATION_MILLIS = 250L;
    private final EventPresentationGateway.FadeColor color;
    private final long startedAt = System.currentTimeMillis();

    EventFadeScreen(EventPresentationGateway.FadeColor color) {
        super(Component.literal("CVES fade"));
        this.color = color;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        double phase = Math.min(2D, elapsed / (double) HALF_DURATION_MILLIS);
        double opacity = phase <= 1D ? phase : 2D - phase;
        int alpha = Math.max(0, Math.min(255, (int) Math.round(opacity * 255D)));
        int rgb = color == EventPresentationGateway.FadeColor.WHITE ? 0xFFFFFF : 0x000000;
        graphics.fill(0, 0, width, height, (alpha << 24) | rgb);
    }
}
