package dev.buizz.cobbleventure.adventure.daycare.client;

/** GUI-scale-aware daycare dimensions derived from Minecraft's scaled viewport. */
record DaycareScreenLayout(
    float scale,
    int panelX, int panelY, int panelWidth, int panelHeight,
    int padding, int groupGap, int cardGap,
    int partyX, int partyWidth,
    int storageX, int storageWidth,
    int gridTop, int gridHeight,
    int storageCardWidth, int storageCardHeight,
    int actionY
) {
    private static final int DESIGN_WIDTH = 560;
    private static final int DESIGN_HEIGHT = 306;

    static DaycareScreenLayout calculate(int screenWidth, int screenHeight) {
        float scale = scaleFor(screenWidth, screenHeight);
        int panelWidth = Math.min(
            Math.max(1, screenWidth - 16), Math.max(300, Math.round(DESIGN_WIDTH * scale))
        );
        int panelHeight = Math.min(
            Math.max(1, screenHeight - 12), Math.max(228, Math.round(DESIGN_HEIGHT * scale))
        );
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;
        int padding = scaled(12, scale, 8);
        int groupGap = scaled(15, scale, 8);
        int cardGap = scaled(6, scale, 4);
        int headerHeight = scaled(55, scale, 45);

        int partyWidth = Math.clamp(panelWidth / 4, 88, 140);
        int availableStorageWidth = panelWidth - padding * 2 - partyWidth - groupGap;
        int storageWidth = Math.min(
            Math.max(126, availableStorageWidth),
            Math.max(210, Math.round(330 * scale))
        );
        int contentWidth = partyWidth + groupGap + storageWidth;
        int partyX = panelX + (panelWidth - contentWidth) / 2;
        int storageX = partyX + partyWidth + groupGap;
        int gridTop = panelY + headerHeight;
        int gridBudget = Math.max(72, panelHeight - headerHeight - 97);
        int gridHeight = Math.min(scaled(170, scale, 84), gridBudget);
        int storageCardWidth = Math.max(38, (storageWidth - cardGap * 2) / 3);
        int storageCardHeight = Math.min(
            Math.max(34, (gridHeight - cardGap) / 2),
            Math.max(38, Math.round(64 * scale))
        );
        gridHeight = storageCardHeight * 2 + cardGap;
        int actionY = gridTop + gridHeight + scaled(10, scale, 7);
        return new DaycareScreenLayout(
            scale, panelX, panelY, panelWidth, panelHeight,
            padding, groupGap, cardGap,
            partyX, partyWidth, storageX, storageWidth,
            gridTop, gridHeight, storageCardWidth, storageCardHeight, actionY
        );
    }

    static float scaleFor(int screenWidth, int screenHeight) {
        float widthScale = Math.max(1, screenWidth - 32) / 700F;
        float heightScale = Math.max(1, screenHeight - 28) / 390F;
        return Math.clamp(Math.min(widthScale, heightScale), .68F, 1F);
    }

    private static int scaled(int value, float scale, int minimum) {
        return Math.max(minimum, Math.round(value * scale));
    }
}
