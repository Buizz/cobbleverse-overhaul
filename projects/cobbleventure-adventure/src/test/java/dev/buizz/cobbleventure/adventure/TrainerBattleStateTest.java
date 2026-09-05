package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TrainerBattleStateTest {
    @Test
    void mergesDuplicateNpcResultsIntoTheRetainedInstance() {
        var data = new TrainerBattleState.BattleData();
        UUID player = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        UUID duplicate = UUID.randomUUID();
        data.setDefeated(player, duplicate, true);

        data.mergeNpcInstances(retained, Set.of(duplicate));

        assertTrue(data.isDefeated(player, retained));
        assertFalse(data.isDefeated(player, duplicate));
    }

    @Test
    void victoryIsScopedByBothPlayerAndSpawnedNpc() {
        TrainerBattleState.BattleData data = new TrainerBattleState.BattleData();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        UUID firstNpc = UUID.randomUUID();
        UUID secondNpc = UUID.randomUUID();

        data.setDefeated(firstPlayer, firstNpc, true);

        assertTrue(data.isDefeated(firstPlayer, firstNpc));
        assertFalse(data.isDefeated(firstPlayer, secondNpc));
        assertFalse(data.isDefeated(secondPlayer, firstNpc));

        data.setDefeated(firstPlayer, firstNpc, false);
        assertFalse(data.isDefeated(firstPlayer, firstNpc));
    }

    @Test
    void gymLeaderAndInteriorTrainerTagsAreRecognized() {
        assertTrue(TrainerBattleState.isGymLeader(Set.of(
            "cobbleventure_regional_npc",
            "cves_binding/cobbleventure/gym_leaders/brock"
        )));
        assertEquals(
            "cobbleventure:flag/gym/kanto/brock/defeated",
            TrainerBattleState.gymLeaderVictoryFlag(Set.of(
                "cves_binding/cobbleventure/gym_leaders/brock"
            ))
        );
        assertTrue(TrainerBattleState.isGymTrainer(Set.of(
            "cobbleventure_regional_npc",
            "cves_binding/cobbleventure/generation_1/firered/firered_camper_liam",
            "cves_trigger/proximity"
        )));
        assertEquals(
            "cobbleventure:flag/trainer/firered_camper_liam/defeated",
            TrainerBattleState.trainerVictoryFlag(Set.of(
                "cves_binding/cobbleventure/generation_1/firered/firered_camper_liam"
            ))
        );
    }

    @Test
    void unrelatedOrInteractiveNpcsAreNotCompletedWithGymLeader() {
        assertFalse(TrainerBattleState.isGymLeader(Set.of(
            "cves_binding/cobbleventure/generation_1/firered/firered_camper_liam"
        )));
        assertFalse(TrainerBattleState.isGymTrainer(Set.of(
            "cobbleventure_regional_npc",
            "cves_binding/cobbleventure/story/gym_guide"
        )));
        assertFalse(TrainerBattleState.isGymTrainer(Set.of(
            "cobbleventure_regional_npc",
            "cves_trigger/proximity"
        )));
    }
}
