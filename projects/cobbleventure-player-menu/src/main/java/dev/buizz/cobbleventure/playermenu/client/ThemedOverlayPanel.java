package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.gui.GuiGraphics;

/** Shared compact panel renderer for HUD notices using the global menu theme. */
public final class ThemedOverlayPanel {
    private ThemedOverlayPanel() {}

    public static void draw(
        GuiGraphics graphics, MenuTheme theme,
        int x, int y, int width, int height
    ) {
        draw(graphics, theme, x, y, width, height, 1.0F, theme.accent);
    }

    public static void draw(
        GuiGraphics graphics, MenuTheme theme,
        int x, int y, int width, int height, float opacity, int accent
    ) {
        float alpha = Math.clamp(opacity, 0.0F, 1.0F);
        int radius = Math.min(theme.cornerRadius, Math.min(width, height) / 2);
        int shadowOffset = theme.shadowOffset;
        fillRoundedRect(
            graphics,
            x + shadowOffset, y + shadowOffset,
            x + width + shadowOffset, y + height + shadowOffset,
            radius, withOpacity(theme.shadow, alpha)
        );
        fillRoundedRect(
            graphics, x, y, x + width, y + height,
            radius, withOpacity(theme.border, alpha)
        );
        fillRoundedRect(
            graphics, x + 1, y + 1, x + width - 1, y + height - 1,
            Math.max(0, radius - 1), withOpacity(theme.innerBorder, alpha)
        );
        fillRoundedRect(
            graphics, x + 2, y + 2, x + width - 2, y + height - 2,
            Math.max(0, radius - 2), withOpacity(theme.background, alpha)
        );
        int accentInset = Math.max(5, radius);
        graphics.fill(
            x + accentInset, y + 1, x + width - accentInset, y + 3,
            withOpacity(accent, alpha)
        );
    }

    public static int withOpacity(int color, float opacity) {
        int sourceAlpha = color >>> 24;
        int alpha = Math.round(sourceAlpha * Math.clamp(opacity, 0.0F, 1.0F));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static void fillRoundedRect(
        GuiGraphics graphics,
        int left, int top, int right, int bottom,
        int radius, int color
    ) {
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);
        int effectiveRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int edgeDistance = Math.min(row, height - 1 - row);
            int inset = 0;
            if (edgeDistance < effectiveRadius) {
                double vertical = effectiveRadius - edgeDistance - 0.5D;
                inset = effectiveRadius - (int)Math.floor(Math.sqrt(
                    Math.max(0.0D, effectiveRadius * effectiveRadius - vertical * vertical)
                ));
            }
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
        }
    }
}
