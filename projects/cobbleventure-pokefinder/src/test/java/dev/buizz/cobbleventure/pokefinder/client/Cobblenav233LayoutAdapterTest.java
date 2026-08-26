package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Cobblenav233LayoutAdapterTest {
    private static final Cobblenav233LayoutAdapter.Layout LAYOUT =
        new Cobblenav233LayoutAdapter.Layout(10, 20, 1.0F, true);

    @Test
    void rotatesWorldOffsetUsingCobblenavYawConvention() {
        Cobblenav233LayoutAdapter.RadarPoint point =
            Cobblenav233LayoutAdapter.worldToRadar(LAYOUT, 10.0D, 0.0D, 180.0F, 64.0D, false);

        assertTrue(point.visible());
        assertFalse(point.edgePinned());
        assertEquals(87.0D, point.x(), 0.0001D);
        assertEquals(67.5D, point.y(), 0.0001D);
    }

    @Test
    void hidesOutOfRangeMarkerWithoutEdgeTracking() {
        Cobblenav233LayoutAdapter.RadarPoint point =
            Cobblenav233LayoutAdapter.worldToRadar(LAYOUT, 65.0D, 0.0D, 180.0F, 64.0D, false);

        assertFalse(point.visible());
    }

    @Test
    void pinsTrackedMarkerToLocalRadarEdge() {
        Cobblenav233LayoutAdapter.RadarPoint point =
            Cobblenav233LayoutAdapter.worldToRadar(LAYOUT, 96.0D, 0.0D, 180.0F, 64.0D, true);

        assertTrue(point.visible());
        assertTrue(point.edgePinned());
        assertEquals(116.7D, point.x(), 0.0001D);
        assertEquals(67.5D, point.y(), 0.0001D);
    }

    @Test
    void hidesTrackedMarkerBeyondFallbackAreaLimit() {
        Cobblenav233LayoutAdapter.RadarPoint point =
            Cobblenav233LayoutAdapter.worldToRadar(LAYOUT, 257.0D, 0.0D, 180.0F, 64.0D, true);

        assertFalse(point.visible());
    }

    @Test
    void keepsGlobalObjectivePinnedBeyondTheOrdinaryFallbackLimit() {
        Cobblenav233LayoutAdapter.RadarPoint point =
            Cobblenav233LayoutAdapter.worldToRadar(
                LAYOUT, 2048.0D, 0.0D, 180.0F, 64.0D,
                Double.POSITIVE_INFINITY, true
            );

        assertTrue(point.visible());
        assertTrue(point.edgePinned());
        assertEquals(116.7D, point.x(), 0.0001D);
    }

    @Test
    void logicalRadarCoordinatesRemainStableAcrossOverlayScales() {
        var half = Cobblenav233LayoutAdapter.worldToRadar(
            new Cobblenav233LayoutAdapter.Layout(10, 20, 0.5F, true),
            10.0D, 0.0D, 180.0F, 64.0D, false
        );
        var doubleScale = Cobblenav233LayoutAdapter.worldToRadar(
            new Cobblenav233LayoutAdapter.Layout(10, 20, 2.0F, true),
            10.0D, 0.0D, 180.0F, 64.0D, false
        );

        assertEquals(half.x(), doubleScale.x(), 0.0001D);
        assertEquals(half.y(), doubleScale.y(), 0.0001D);
    }

    @Test
    void placesPinnedHudOnEitherBottomCorner() {
        assertEquals(8, Cobblenav233LayoutAdapter.layoutLeft(500, 8, false));
        assertEquals(
            347,
            Cobblenav233LayoutAdapter.layoutLeft(500, 8, true)
        );
    }

    @Test
    void switchesPinnedHudBetweenBothSides() {
        assertEquals(PokefinderHudPosition.RIGHT, PokefinderHudPosition.LEFT.opposite());
        assertEquals(PokefinderHudPosition.LEFT, PokefinderHudPosition.RIGHT.opposite());
    }

    @Test
    void migratesLegacyThreeStateHudSetting() {
        assertEquals(
            new PinnedPokefinderHud.State(false, PokefinderHudPosition.LEFT),
            PinnedPokefinderHud.parseState("off")
        );
        assertEquals(
            new PinnedPokefinderHud.State(true, PokefinderHudPosition.RIGHT),
            PinnedPokefinderHud.parseState("right")
        );
    }
}
