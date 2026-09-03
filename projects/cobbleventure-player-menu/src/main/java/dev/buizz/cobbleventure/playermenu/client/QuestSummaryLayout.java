package dev.buizz.cobbleventure.playermenu.client;

/** Responsive placement independent of Minecraft, including the inline fallback. */
record QuestSummaryLayout(int x, int y, int width, int height, Mode mode) {
    enum Mode { FULL, COMPACT, INLINE }

    static QuestSummaryLayout calculate(
        int screenHeight, int infoX, int infoWidth, int trainerTop,
        int overviewHeight, int infoHeight, int margin, int gap
    ) {
        int availableWidth = infoX - gap - margin;
        int availableHeight = Math.max(0, screenHeight - margin - trainerTop);
        if (availableWidth >= 112 && availableHeight >= 70) {
            boolean full = availableWidth >= 220 && availableHeight >= 142;
            int width = Math.min(full ? 330 : 219, availableWidth);
            return new QuestSummaryLayout(
                infoX - gap - width, trainerTop, width,
                Math.min(full ? 142 : 96, availableHeight), full ? Mode.FULL : Mode.COMPACT
            );
        }
        // Share the selected-entry description area; never cover menu actions or party controls.
        int y = trainerTop + overviewHeight + gap + 48;
        return new QuestSummaryLayout(
            infoX + 8, y, Math.max(1, infoWidth - 16),
            Math.max(0, Math.min(infoHeight - 56, screenHeight - margin - y)), Mode.INLINE
        );
    }

    static int visibleLines(int availableHeight, int lineAdvance, int limit) {
        return Math.max(0, Math.min(limit, availableHeight / Math.max(1, lineAdvance)));
    }

    static boolean controlsFit(int controlsTop, int infoBottom, int gap) {
        return controlsTop >= infoBottom + gap;
    }
}
