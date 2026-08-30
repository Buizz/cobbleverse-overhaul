package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexGrid;
import org.junit.jupiter.api.Test;

final class WorldGateEdgePlacementTest {
    private static final HexGrid GRID = new HexGrid(
        100, new CobbleventureBootstrap.BlockPoint(0, 64, 0)
    );
    private static final HexCoord ANCHOR = new HexCoord(0, 0);

    @Test
    void legacyDefaultGateResourceResolvesToThePackagedNbt() {
        var objects = JsonParser.parseString("""
            [{
              "id": "legacy_gate",
              "type": "gate",
              "anchor": {"q": 0, "r": 0},
              "resource": "cobbleventure:gate/default",
              "properties": {}
            }]
            """).getAsJsonArray();

        assertEquals(
            "cobbleventure:gate/default_gate",
            WorldGateSystem.parse(objects).getFirst().structure()
        );
    }

    @Test
    void eastAndWestUseTheirSingleFaceCenter() {
        assertEquals(
            new CobbleventureBootstrap.Point(87, 0),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "east", ignored -> false)
        );
        assertEquals(
            new CobbleventureBootstrap.Point(-87, 0),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "west", ignored -> false)
        );
    }

    @Test
    void eastAndWestNaturalGateShouldersCanReachTheirOuterBarrier() {
        assertTrue(WorldGateSystem.gateHasOpenFace(
            "east", offset -> offset.equals(new HexCoord(1, 0))
        ));
        assertTrue(WorldGateSystem.gateHasOpenFace(
            "west", offset -> offset.equals(new HexCoord(-1, 0))
        ));
    }

    @Test
    void northAlwaysStaysCenteredBetweenBothFaces() {
        assertEquals(
            new CobbleventureBootstrap.Point(0, -100),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "north", ignored -> true)
        );
    }

    @Test
    void northUsesTheOnlyOpenFace() {
        assertEquals(
            new CobbleventureBootstrap.Point(-44, -75),
            WorldGateSystem.gateEdgeCenter(
                GRID, ANCHOR, "north", offset -> offset.equals(new HexCoord(0, -1))
            )
        );
    }

    @Test
    void southAlwaysStaysCenteredBetweenBothFaces() {
        assertEquals(
            new CobbleventureBootstrap.Point(0, 100),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "south", ignored -> true)
        );
    }

    @Test
    void obstacleSpanMatchesTheSelectedHexBoundary() {
        assertEquals(30, WorldGateSystem.gateBoundaryHalfLength(64, "east", 1, 3));
        assertEquals(26, WorldGateSystem.gateBoundaryHalfLength(64, "north", 1, 3));
        assertEquals(53, WorldGateSystem.gateBoundaryHalfLength(64, "north", 2, 3));
    }

    @Test
    void naturalObstacleUsesAShallowBoundaryBand() {
        assertEquals(6, WorldGateSystem.naturalGateBoundaryDepth(64, 2));
        assertEquals(9, WorldGateSystem.naturalGateBoundaryDepth(64, 7));
    }

    @Test
    void gateApproachNeverChangesMoreThanOneBlockAtATime() {
        assertEquals(65, WorldGateSystem.nextGateApproachY(64, 70));
        assertEquals(63, WorldGateSystem.nextGateApproachY(64, 58));
        assertEquals(64, WorldGateSystem.nextGateApproachY(64, 64));
    }

    @Test
    void wideGateTriggerCoversTheWholeAuthoredOpening() {
        assertTrue(WorldGateSystem.crossedGateOpening(
            3.0D, 15.4D, 1.0D, 15.4D, 2.15D, 31
        ));
        assertTrue(WorldGateSystem.crossedGateOpening(
            -3.0D, -15.4D, -1.0D, -15.4D, 2.15D, 31
        ));
        assertFalse(WorldGateSystem.crossedGateOpening(
            3.0D, 17.0D, 1.0D, 17.0D, 2.15D, 31
        ));
    }

    @Test
    void diagonalMovementUsesItsGatePlaneIntersection() {
        assertTrue(WorldGateSystem.crossedGateOpening(
            3.0D, 4.0D, 1.0D, 8.0D, 2.15D, 11
        ));
        assertFalse(WorldGateSystem.crossedGateOpening(
            3.0D, 9.0D, 1.0D, 4.0D, 2.15D, 11
        ));
    }

    @Test
    void lockedPlayerAlreadyInsideTheWideOpeningCannotUseATrackingGap() {
        assertTrue(WorldGateSystem.insideGateTriggerZone(
            0.0D, 15.9D, 2.15D, 31
        ));
        assertTrue(WorldGateSystem.insideGateTriggerZone(
            2.4D, -15.9D, 2.15D, 31
        ));
        assertFalse(WorldGateSystem.insideGateTriggerZone(
            0.0D, 17.0D, 2.15D, 31
        ));
        assertFalse(WorldGateSystem.insideGateTriggerZone(
            3.0D, 0.0D, 2.15D, 31
        ));
    }

    @Test
    void gateDialogueApproachFollowsTheRoadInsteadOfACircle() {
        assertTrue(WorldGateSystem.insideGateDialogueApproach(
            7.9D, 6.4D, 11
        ));
        assertFalse(WorldGateSystem.insideGateDialogueApproach(
            0.0D, 6.6D, 11
        ));
        assertFalse(WorldGateSystem.insideGateDialogueApproach(
            8.1D, 0.0D, 11
        ));
    }
}
