package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexGrid;
import org.junit.jupiter.api.Test;

final class WorldGateEdgePlacementTest {
    private static final HexGrid GRID = new HexGrid(
        100, new CobbleventureBootstrap.BlockPoint(0, 64, 0)
    );
    private static final HexCoord ANCHOR = new HexCoord(0, 0);

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
    void northAlwaysStaysCenteredBetweenBothFaces() {
        assertEquals(
            new CobbleventureBootstrap.Point(0, -75),
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
            new CobbleventureBootstrap.Point(0, 75),
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
}
