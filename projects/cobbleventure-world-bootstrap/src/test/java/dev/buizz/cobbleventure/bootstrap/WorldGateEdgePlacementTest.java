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
    void northStaysBetweenBothFacesWhenBothAreOpen() {
        assertEquals(
            new CobbleventureBootstrap.Point(0, -75),
            WorldGateSystem.gateEdgeCenter(GRID, ANCHOR, "north", ignored -> true)
        );
    }

    @Test
    void northMovesToTheOnlyOpenDiagonalFace() {
        assertEquals(
            new CobbleventureBootstrap.Point(-44, -75),
            WorldGateSystem.gateEdgeCenter(
                GRID, ANCHOR, "north", offset -> offset.equals(new HexCoord(0, -1))
            )
        );
    }

    @Test
    void southMovesToTheOnlyOpenDiagonalFace() {
        assertEquals(
            new CobbleventureBootstrap.Point(44, 75),
            WorldGateSystem.gateEdgeCenter(
                GRID, ANCHOR, "south", offset -> offset.equals(new HexCoord(0, 1))
            )
        );
    }
}
