package dev.buizz.cobbleventure.bootstrap;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEncounterProximityTest {
    @Test
    void defeatDoesNotRetriggerUntilPlayerLeavesAndReenters() {
        Set<String> inside = new HashSet<>();

        assertTrue(DungeonSystem.observeDungeonTriggerEntry(
            inside, "trainer", true, true
        ));
        assertFalse(DungeonSystem.observeDungeonTriggerEntry(
            inside, "trainer", true, false
        ));
        assertFalse(DungeonSystem.observeDungeonTriggerEntry(
            inside, "trainer", true, true
        ));

        assertFalse(DungeonSystem.observeDungeonTriggerEntry(
            inside, "trainer", false, true
        ));
        assertTrue(DungeonSystem.observeDungeonTriggerEntry(
            inside, "trainer", true, true
        ));
    }

    @Test
    void failedGeneratedEncounterStartRetriesWhilePlayerRemainsNearby() {
        Set<String> inside = new HashSet<>();

        assertTrue(DungeonSystem.observeDungeonTriggerEntry(
            inside, "generated-trainer", true, true
        ));

        DungeonSystem.releaseDungeonTriggerAfterFailedStart(
            inside, "generated-trainer"
        );

        assertTrue(DungeonSystem.observeDungeonTriggerEntry(
            inside, "generated-trainer", true, true
        ));
    }

    @Test
    void defeatRecoveryBlocksDungeonOwnedWarningsAndRetriggers() {
        assertFalse(DungeonSystem.canActivateDungeonEncounterForPlayer(
            true, true
        ));
        assertTrue(DungeonSystem.canActivateDungeonEncounterForPlayer(
            true, false
        ));
        assertFalse(DungeonSystem.canActivateDungeonEncounterForPlayer(
            false, false
        ));
    }
}
