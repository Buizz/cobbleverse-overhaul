package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

final class FacilityRuntimeGroundTest {
    @Test
    void rejectsAPlacedBuildingRoofAsTheRoadSurface() {
        assertEquals(70, FacilityRuntimeGround.correctedRoadSurfaceY(82, 70));
    }

    @Test
    void preservesSmallAuthoredRoadHeightDifferences() {
        assertEquals(71, FacilityRuntimeGround.correctedRoadSurfaceY(71, 70));
        assertEquals(70, FacilityRuntimeGround.correctedRoadSurfaceY(70, 70));
    }

    @Test
    void usesPlannedTerrainWithoutReadingAnUnloadedChunk() {
        assertEquals(70, FacilityRuntimeGround.resolvedRoadSurfaceY(
            false, () -> fail("unloaded chunk heightmap must not be read"), 70
        ));
    }
}
