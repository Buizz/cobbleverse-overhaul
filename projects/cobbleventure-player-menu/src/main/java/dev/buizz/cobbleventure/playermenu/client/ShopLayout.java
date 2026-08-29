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
}
