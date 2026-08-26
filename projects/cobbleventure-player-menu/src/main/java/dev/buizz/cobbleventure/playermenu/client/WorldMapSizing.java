package dev.buizz.cobbleventure.playermenu.client;

/** Pure responsive sizing rules for the world map viewport. */
final class WorldMapSizing {
    private static final int CONTENT_PADDING = 12;
    private static final int CONTENT_TOP_INSET = 22;
    private static final int MIN_HEX_SIZE = 7;
    private static final int MAX_FITTED_HEX_SIZE = 12;
    private static final int MAX_ZOOMED_HEX_SIZE = 32;

    private WorldMapSizing() {}

    static int responsiveHexSize(
        int mapWidth, int mapHeight, double boundsWidth, double boundsHeight, int zoomLevel
    ) {
        double horizontal = (mapWidth - CONTENT_PADDING) / boundsWidth;
        double vertical = (mapHeight - CONTENT_PADDING - CONTENT_TOP_INSET) / boundsHeight;
        int fitted = Math.max(
            MIN_HEX_SIZE,
            Math.min(MAX_FITTED_HEX_SIZE, (int) Math.floor(Math.min(horizontal, vertical)))
        );
        return Math.min(MAX_ZOOMED_HEX_SIZE, fitted + Math.max(0, zoomLevel) * 2);
    }
}
