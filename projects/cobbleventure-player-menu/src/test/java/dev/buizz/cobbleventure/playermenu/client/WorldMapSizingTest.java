package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WorldMapSizingTest {
    @Test
    void keepsMapCellsReadableOnSmallGuiViewports() {
        assertEquals(7, WorldMapSizing.responsiveHexSize(110, 120, 40.0D, 30.0D, 0));
    }

    @Test
    void stillFitsTheWholeMapWhenEnoughSpaceIsAvailable() {
        assertEquals(12, WorldMapSizing.responsiveHexSize(800, 500, 40.0D, 30.0D, 0));
    }

    @Test
    void appliesZoomAfterTheMinimumReadableSize() {
        assertEquals(11, WorldMapSizing.responsiveHexSize(110, 120, 40.0D, 30.0D, 2));
        assertEquals(32, WorldMapSizing.responsiveHexSize(110, 120, 40.0D, 30.0D, 20));
    }
}
