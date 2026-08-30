package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TownAccessRoadEndTest {
    @Test
    void clipsOnlyTheBuildingSideOfEveryCardinalEntrance() {
        assertFalse(TownAccessRoadGeometry.isBeyondEnd(0, 5, 0, 0, 1, 0));
        assertTrue(TownAccessRoadGeometry.isBeyondEnd(0, 5, 0, 0, 0, -1));

        assertFalse(TownAccessRoadGeometry.isBeyondEnd(0, -5, 0, 0, -1, 0));
        assertTrue(TownAccessRoadGeometry.isBeyondEnd(0, -5, 0, 0, 0, 1));

        assertFalse(TownAccessRoadGeometry.isBeyondEnd(5, 0, 0, 0, 0, 1));
        assertTrue(TownAccessRoadGeometry.isBeyondEnd(5, 0, 0, 0, -1, 0));

        assertFalse(TownAccessRoadGeometry.isBeyondEnd(-5, 0, 0, 0, 0, -1));
        assertTrue(TownAccessRoadGeometry.isBeyondEnd(-5, 0, 0, 0, 1, 0));
    }

    @Test
    void keepsTheAuthoredEntranceAnchorItself() {
        assertFalse(TownAccessRoadGeometry.isBeyondEnd(0, 5, 0, 0, 0, 0));
    }

    @Test
    void makesTheLastSegmentPerpendicularToTheEntranceFace() {
        assertEquals(15, TownAccessRoadGeometry.cornerX(false, 16, 15));
        assertEquals(-32, TownAccessRoadGeometry.cornerZ(false, -32, -29));

        assertEquals(16, TownAccessRoadGeometry.cornerX(true, 16, 15));
        assertEquals(-29, TownAccessRoadGeometry.cornerZ(true, -32, -29));
    }

    @Test
    void connectsOpenEndedRegionalRoutesToTheTown() {
        assertEquals(
            "route:route_custom_22",
            RegionalRouteGeometry.gateTarget(null, "route_custom_22")
        );
        assertEquals(
            "cobbleventure:settlement/fuchsia_city",
            RegionalRouteGeometry.gateTarget(
                "cobbleventure:settlement/fuchsia_city", "route_custom_09"
            )
        );
    }

    @Test
    void treatsOnlyTheOceanPartOfALogBridgeAsAquatic() {
        assertFalse(RegionalRouteGeometry.approachIsAquatic(
            "log_bridge", true, false
        ));
        assertTrue(RegionalRouteGeometry.approachIsAquatic(
            "log_bridge", true, true
        ));
        assertTrue(RegionalRouteGeometry.approachIsAquatic(
            "water", true, false
        ));
    }
}
