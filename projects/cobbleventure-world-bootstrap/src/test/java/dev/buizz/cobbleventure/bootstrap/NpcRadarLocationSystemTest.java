package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class NpcRadarLocationSystemTest {
    @Test
    void classifiesBoundNpcProfilesWithoutScanningAmbientNpcs() {
        Set<String> trainers = Set.of("sample_youngster_minjun");
        assertEquals(RadarLocationCatalog.NpcKind.TRAINER,
            NpcRadarLocationSystem.kind(
                "cobbleventure/samples/sample_youngster_minjun", trainers));
        assertEquals(RadarLocationCatalog.NpcKind.GYM_LEADER,
            NpcRadarLocationSystem.kind("cobbleventure/gym_leaders/brock", trainers));
        assertEquals(RadarLocationCatalog.NpcKind.IMPORTANT_NPC,
            NpcRadarLocationSystem.kind(
                "cobbleventure/rewards/feature_map_guide", trainers));
        assertNull(NpcRadarLocationSystem.kind(
            "cobbleventure/facilities/pokemon_center_nurse", trainers));
    }

    @Test
    void mapsProfilesToExistingPerPlayerFlags() {
        assertEquals("cobbleventure:flag/trainer/sample_youngster_minjun/defeated",
            NpcRadarLocationSystem.trainerFlag("sample_youngster_minjun"));
        assertEquals("cobbleventure:flag/gym/kanto/giovanni_gym/defeated",
            NpcRadarLocationSystem.gymFlag("giovanni_gym"));
        assertEquals("cobbleventure:flag/rewards/field_move/rock_smash",
            NpcRadarLocationSystem.rewardFlag("field_move_rock_smash_instructor"));
        assertEquals("cobbleventure:flag/rewards/item/potion_supplier",
            NpcRadarLocationSystem.rewardFlag("item_potion_supplier"));
        assertEquals("cobbleventure:flag/rewards/item/coin_case_guest",
            NpcRadarLocationSystem.rewardFlag("item_coin_case_guest"));
        assertEquals("cobbleventure:flag/rewards/feature/settlement_teleport",
            NpcRadarLocationSystem.rewardFlag("feature_teleport_guide"));
    }

    @Test
    void rendersTrainerCompletionFromTheSpecificNpcInstanceResult() {
        assertEquals("AVAILABLE", NpcRadarLocationSystem.completionState(
            RadarLocationCatalog.NpcKind.TRAINER, false));
        assertEquals("DEFEATED", NpcRadarLocationSystem.completionState(
            RadarLocationCatalog.NpcKind.TRAINER, true));
        assertEquals("COMPLETED", NpcRadarLocationSystem.completionState(
            RadarLocationCatalog.NpcKind.IMPORTANT_NPC, true));
    }

    @Test
    void matchesAuthoredQuestNpcToItsRuntimeBindingSlug() {
        assertTrue(NpcRadarLocationSystem.matchesObjectiveNpc(
            "cobbleventure/story/professor_oak",
            "cobbleventure:npc/professor_oak"
        ));
        assertFalse(NpcRadarLocationSystem.matchesObjectiveNpc(
            "cobbleventure/story/professor_oak",
            "cobbleventure:npc/starter_town_gatekeeper_minho"
        ));
    }
}
