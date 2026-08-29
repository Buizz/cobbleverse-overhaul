package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GymStaffPresetSelectionTest {
    @Test
    void leaderUsesInteractionPreset() {
        assertEquals(
            "easy_npc:preset/encounter/brock__v5.npc.snbt",
            GymInteriorSystem.staffPreset("cobbleventure:npc/gym_leader/brock", "leader")
        );
    }

    @Test
    void interiorTrainerUsesProximityPreset() {
        assertEquals(
            "easy_npc:preset/encounter/firered_camper_liam__v5_proximity.npc.snbt",
            GymInteriorSystem.staffPreset("cobbleventure:npc/firered_camper_liam", "trainer")
        );
    }
}
