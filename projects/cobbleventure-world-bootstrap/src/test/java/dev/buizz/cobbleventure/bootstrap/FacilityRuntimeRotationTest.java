package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FacilityRuntimeRotationTest {
    @Test
    void usesTheCompiledFacilityRotation() {
        assertEquals(
            "clockwise_90",
            FacilityPlacementRotation.resolve(
                "facility_player_house_1", "clockwise_90", "clockwise_180"
            )
        );
    }

    @Test
    void onlyUsesLegacyGymRotationWhenNoCompiledPlotExists() {
        assertEquals(
            "counterclockwise_90",
            FacilityPlacementRotation.resolve(
                "gym_building", "counterclockwise_90", "clockwise_180"
            )
        );
        assertEquals(
            "clockwise_180",
            FacilityPlacementRotation.resolve(
                "gym_building", null, "clockwise_180"
            )
        );
        assertEquals(
            "none",
            FacilityPlacementRotation.resolve(
                "facility_player_house_1", null, "clockwise_180"
            )
        );
    }
}
