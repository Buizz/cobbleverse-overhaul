package dev.buizz.cobbleventure.adventure.event.client;

import net.minecraft.client.gui.GuiGraphics;

/** Small deterministic rounded-panel renderer shared by authored dialogue screens. */
final class EventPanelRenderer {
    private EventPanelRenderer() {}

    static void dialogue(
        GuiGraphics graphics, int left, int top, int right, int bottom,
        EventDialogueTheme theme
    ) {
        panel(
            graphics, left, top, right, bottom, theme.panelCornerRadius,
            theme.panelAccent, theme.panelBorderWidth,
            theme.panelInnerBorder, theme.panelInnerBorderWidth,
            theme.panelBackground, theme.panelShadow, theme.panelShadowOffset
        );
    }

    static void choice(
        GuiGraphics graphics, int left, int top, int right, int bottom,
        EventDialogueTheme theme
    ) {
        panel(
            graphics, left, top, right, bottom, theme.choiceCornerRadius,
            theme.choicePanelBorder, theme.panelBorderWidth,
            theme.choicePanelInnerBorder, theme.panelInnerBorderWidth,
            theme.choicePanelBackground, theme.panelShadow, theme.panelShadowOffset
        );
    }

    static void roundedFill(
        GuiGraphics graphics, int left, int top, int right, int bottom,
        int radius, int color
    ) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return;
        int boundedRadius = Math.clamp(radius, 0, Math.min(width, height) / 2);
        if (boundedRadius == 0) {
            graphics.fill(left, top, right, bottom, color);
            return;
        }
        for (int row = 0; row < height; row++) {
            int edgeDistance = row < boundedRadius
                ? boundedRadius - row - 1
                : row >= height - boundedRadius ? row - (height - boundedRadius) : 0;
            int inset = edgeDistance == 0 ? 0 : boundedRadius - (int)Math.floor(
                Math.sqrt((double)boundedRadius * boundedRadius - (double)edgeDistance * edgeDistance)
            );
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
        }
    }

    private static void panel(
        GuiGraphics graphics, int left, int top, int right, int bottom, int radius,
        int border, int borderWidth, int innerBorder, int innerBorderWidth,
        int background, int shadow, int shadowOffset
    ) {
        if (shadowOffset > 0) {
            roundedFill(
                graphics, left + shadowOffset, top + shadowOffset,
                right + shadowOffset, bottom + shadowOffset, radius, shadow
            );
        }
        roundedFill(graphics, left, top, right, bottom, radius, border);
        int firstInset = Math.min(borderWidth, Math.min((right - left) / 2, (bottom - top) / 2));
        roundedFill(
            graphics, left + firstInset, top + firstInset,
            right - firstInset, bottom - firstInset,
            Math.max(0, radius - firstInset), innerBorder
        );
        int secondInset = firstInset + innerBorderWidth;
        roundedFill(
            graphics, left + secondInset, top + secondInset,
            right - secondInset, bottom - secondInset,
            Math.max(0, radius - secondInset), background
        );
    }
}
