package dev.buizz.cobbleventure.adventure.daycare.client;

/** GUI-scale-aware daycare dimensions derived from Minecraft's scaled viewport. */
record DaycareScreenLayout(
    float scale,
    int panelX, int panelY, int panelWidth, int panelHeight,
    int padding, int gap,
    int headerHeight,
    int storedLabelY, int storedX, int storedY,
    int storedCardWidth, int storedCardHeight, int storedGap,
    int contentY, int contentHeight,
    int partyPanelX, int partyPanelWidth,
    int partyGridX, int partyGridY,
    int partyCardWidth, int partyCardHeight, int partyGap,
    int detailPanelX, int detailPanelWidth,
    int detailModelX, int detailModelY, int detailModelSize,
    int detailInfoX, int detailInfoY,
    int actionY, int actionHeight,
    int statusY, int closeX, int closeY, int closeWidth, int closeHeight,
    int eggX, int eggY, int eggWidth, int eggHeight
) {
    private static final int DESIGN_WIDTH = 720;
    private static final int DESIGN_HEIGHT = 390;

    static DaycareScreenLayout calculate(int screenWidth, int screenHeight) {
        int marginX = screenWidth >= 760 ? 18 : 6;
        int marginY = screenHeight >= 430 ? 12 : 6;
        int panelWidth = Math.max(300, Math.min(DESIGN_WIDTH, screenWidth - marginX * 2));
        int panelHeight = Math.max(236, Math.min(DESIGN_HEIGHT, screenHeight - marginY * 2));
        panelWidth = Math.min(panelWidth, screenWidth);
        panelHeight = Math.min(panelHeight, screenHeight);
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;

        float scale = Math.clamp(Math.min(
            panelWidth / (float) DESIGN_WIDTH,
            panelHeight / (float) DESIGN_HEIGHT
        ), .55F, 1F);
        int padding = Math.clamp(Math.round(14 * scale), 6, 14);
        int gap = Math.clamp(Math.round(12 * scale), 5, 12);
        int headerHeight = Math.clamp(Math.round(42 * scale), 25, 42);
        int footerHeight = Math.clamp(Math.round(39 * scale), 27, 39);

        int storedLabelY = panelY + headerHeight;
        int storedY = storedLabelY + 13;
        int storedX = panelX + padding;
        int storedWidth = panelWidth - padding * 2;
        int storedGap = Math.clamp(Math.round(9 * scale), 3, 9);
        int storedCardWidth = Math.max(36, (storedWidth - storedGap * 5) / 6);
        int storedCardHeight = Math.clamp(Math.round(84 * scale), 43, 84);

        int contentY = storedY + storedCardHeight + gap;
        int contentBottom = panelY + panelHeight - footerHeight;
        int contentHeight = Math.max(102, contentBottom - contentY);
        if (contentY + contentHeight > panelY + panelHeight) {
            contentHeight = panelY + panelHeight - contentY;
        }

        int innerWidth = panelWidth - padding * 2;
        int partyPanelWidth = Math.clamp(
            Math.round(innerWidth * .43F), 126, Math.max(126, innerWidth - 150 - gap)
        );
        int partyPanelX = panelX + padding;
        int detailPanelX = partyPanelX + partyPanelWidth + gap;
        int detailPanelWidth = panelX + panelWidth - padding - detailPanelX;

        int sectionPadding = Math.clamp(Math.round(9 * scale), 5, 9);
        int sectionTitleHeight = Math.clamp(Math.round(22 * scale), 16, 22);
        int partyGap = Math.clamp(Math.round(6 * scale), 3, 6);
        int partyGridX = partyPanelX + sectionPadding;
        int partyGridY = contentY + sectionTitleHeight + sectionPadding;
        int partyGridWidth = partyPanelWidth - sectionPadding * 2;
        int partyCardWidth = Math.max(48, (partyGridWidth - partyGap) / 2);
        int partyGridHeight = contentHeight - sectionTitleHeight - sectionPadding * 2;
        int partyCardHeight = Math.max(22, (partyGridHeight - partyGap * 2) / 3);

        int detailModelSize = Math.clamp(
            Math.min(contentHeight - 47, detailPanelWidth / 3), 42, 100
        );
        int detailModelX = detailPanelX + sectionPadding;
        int detailModelY = contentY + sectionTitleHeight + sectionPadding;
        int detailInfoX = detailModelX + detailModelSize + gap;
        int detailInfoY = detailModelY + 2;
        int actionHeight = Math.clamp(Math.round(28 * scale), 20, 28);
        int actionY = contentY + contentHeight - sectionPadding - actionHeight;

        int closeWidth = Math.clamp(Math.round(100 * scale), 66, 100);
        int closeHeight = Math.clamp(Math.round(24 * scale), 18, 24);
        int closeX = panelX + panelWidth - padding - closeWidth;
        int closeY = panelY + panelHeight - padding - closeHeight;
        int statusY = panelY + panelHeight - footerHeight + 7;

        int eggWidth = Math.clamp(Math.round(92 * scale), 70, 92);
        int eggHeight = Math.clamp(Math.round(25 * scale), 19, 25);
        int eggX = panelX + panelWidth - padding - eggWidth;
        int eggY = panelY + Math.max(5, (headerHeight - eggHeight) / 2);

        return new DaycareScreenLayout(
            scale, panelX, panelY, panelWidth, panelHeight, padding, gap,
            headerHeight, storedLabelY, storedX, storedY,
            storedCardWidth, storedCardHeight, storedGap,
            contentY, contentHeight,
            partyPanelX, partyPanelWidth,
            partyGridX, partyGridY, partyCardWidth, partyCardHeight, partyGap,
            detailPanelX, detailPanelWidth,
            detailModelX, detailModelY, detailModelSize,
            detailInfoX, detailInfoY, actionY, actionHeight,
            statusY, closeX, closeY, closeWidth, closeHeight,
            eggX, eggY, eggWidth, eggHeight
        );
    }
}
