package dev.buizz.cobbleventure.casino.client;

import net.minecraft.client.gui.GuiGraphics;

/** Shared rounded panel renderer matching the existing player-menu presentation. */
final class CasinoThemedPanel {
    private CasinoThemedPanel() {}

    static void draw(
        GuiGraphics graphics, CasinoMenuTheme theme,
        int x, int y, int width, int height, float opacity, int accent
    ) {
        float alpha = Math.clamp(opacity, 0, 1);
        int radius = Math.min(theme.cornerRadius, Math.min(width, height) / 2);
        roundedFill(graphics,
            x + theme.shadowOffset, y + theme.shadowOffset,
            x + width + theme.shadowOffset, y + height + theme.shadowOffset,
            radius, withOpacity(theme.shadow, alpha));
        roundedFill(graphics, x, y, x + width, y + height,
            radius, withOpacity(theme.border, alpha));
        roundedFill(graphics, x + 1, y + 1, x + width - 1, y + height - 1,
            Math.max(0, radius - 1), withOpacity(theme.innerBorder, alpha));
        roundedFill(graphics, x + 2, y + 2, x + width - 2, y + height - 2,
            Math.max(0, radius - 2), withOpacity(theme.background, alpha));
        int inset = Math.max(5, radius);
        graphics.fill(x + inset, y + 1, x + width - inset, y + 3, withOpacity(accent, alpha));
    }

    static int withOpacity(int color, float opacity) {
        int alpha = Math.round((color >>> 24) * Math.clamp(opacity, 0, 1));
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    static void roundedFill(
        GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color
    ) {
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);
        int safeRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int edge = Math.min(row, height - 1 - row);
            int inset = 0;
            if (edge < safeRadius) {
                double vertical = safeRadius - edge - .5D;
                inset = safeRadius - (int)Math.floor(Math.sqrt(
                    Math.max(0, safeRadius * safeRadius - vertical * vertical)
                ));
            }
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
        }
    }
}
