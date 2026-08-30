package dev.buizz.cobbleventure.bootstrap;

import java.util.List;

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

    @Test
    void connectsARegionalRoadWhenItsCorridorOnlyTouchesATownHex() {
        var centerline = List.of(
            new CobbleventureBootstrap.Point(-100, 61),
            new CobbleventureBootstrap.Point(100, 61)
        );

        assertTrue(RegionalRouteGeometry.corridorOverlapsHexTile(
            centerline, new CobbleventureBootstrap.Point(0, 0), 64, 12
        ));
        assertFalse(RegionalRouteGeometry.corridorOverlapsHexTile(
            centerline, new CobbleventureBootstrap.Point(0, 0), 64, 8
        ));
    }

    @Test
    void selectsTheRoadEndClosestToTheOverlappedTown() {
        var centerline = List.of(
            new CobbleventureBootstrap.Point(-100, 0),
            new CobbleventureBootstrap.Point(100, 0)
        );

        assertFalse(RegionalRouteGeometry.nearestRouteEndIsLast(
            centerline, new CobbleventureBootstrap.Point(-80, 10)
        ));
        assertTrue(RegionalRouteGeometry.nearestRouteEndIsLast(
            centerline, new CobbleventureBootstrap.Point(80, 10)
        ));
    }

    @Test
    void keepsTheAuthoredTownEndpointWhenALogBridgeStartsOverWater() {
        var centerline = List.of(
            new CobbleventureBootstrap.Point(1265, 405),
            new CobbleventureBootstrap.Point(1265, 463)
        );

        assertEquals(
            new CobbleventureBootstrap.Point(1265, 405),
            RegionalRouteGeometry.connectedEndpoint(centerline, false)
        );
        assertEquals(
            new CobbleventureBootstrap.Point(1265, 463),
            RegionalRouteGeometry.connectedEndpoint(centerline, true)
        );
    }
}
