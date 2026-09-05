package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FacilityMusicZoneTest {
    @Test
    void usesTheBuildingFootprintRatherThanTheRotatedPlacementPivot() {
        var zone = FacilityMusicZoneGeometry.bounds(100, 70, 200, 23, 22, 15);

        assertTrue(zone.contains(100.5D, 71.0D, 200.5D));
        assertTrue(zone.contains(122.5D, 84.0D, 221.5D));
        assertFalse(zone.contains(123.0D, 71.0D, 200.5D));
        assertFalse(zone.contains(122.5D, 85.0D, 221.5D));
    }
}
