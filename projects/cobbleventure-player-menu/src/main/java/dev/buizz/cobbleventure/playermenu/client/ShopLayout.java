package dev.buizz.cobbleventure.playermenu.client;

/** Resolution-independent sizing rules for the shop panel. */
final class ShopLayout {
    private static final int MAX_WIDTH = 480;
    private static final int MAX_HEIGHT = 286;
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 220;
    private static final float SCREEN_WIDTH_RATIO = 0.82F;
    private static final float SCREEN_HEIGHT_RATIO = 0.82F;

    private ShopLayout() {}

    static Panel panel(int screenWidth, int screenHeight) {
        int proportionalWidth = Math.round(screenWidth * SCREEN_WIDTH_RATIO);
        int proportionalHeight = Math.round(screenHeight * SCREEN_HEIGHT_RATIO);
        int width = Math.min(screenWidth - 16,
            Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, proportionalWidth)));
        int height = Math.min(screenHeight - 16,
            Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, proportionalHeight)));
        return new Panel(Math.max(1, width), Math.max(1, height));
    }

    static int contentWidth(int panelWidth) {
        int proportional = panelWidth < 360 ? panelWidth * 3 / 5 : panelWidth * 2 / 3;
        return Math.max(180, proportional);
    }

    record Panel(int width, int height) {}

    static DescriptionArea descriptionArea(int panelWidth, int panelHeight, int captionHeight) {
        int leftWidth = contentWidth(panelWidth);
        int top = 68 + 28 + captionHeight + 6;
        int bottom = panelHeight - 10 - 80 - 5;
        return new DescriptionArea(leftWidth + 13, top, panelWidth - leftWidth - 35,
            Math.max(0, bottom - top));
    }

    record DescriptionArea(int x, int y, int width, int height) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        int visibleLines(int lineHeight) {
            return Math.max(1, height / Math.max(1, lineHeight));
        }

        int clampScroll(int requested, int totalLines, int lineHeight) {
            return Math.clamp(requested, 0, Math.max(0, totalLines - visibleLines(lineHeight)));
        }
    }
}
