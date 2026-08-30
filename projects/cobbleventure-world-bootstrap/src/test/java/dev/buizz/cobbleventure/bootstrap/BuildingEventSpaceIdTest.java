package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildingEventSpaceIdTest {
    @Test
    void exposesOnlyResourceIdsToTheV5BoundarySnapshot() {
        assertTrue(BuildingEventSpaceIds.isPublic(
            "cobbleventure:building/pokemon_center"
        ));
        assertFalse(BuildingEventSpaceIds.isPublic(
            "__daycare_instance__|cobbleventure:generation_1|"
                + "cobbleventure:placeholder/daycare|1264,71,-314"
        ));
    }
}
