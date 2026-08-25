package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEntryEligibilityTest {
    private static final DungeonDefinition.Difficulty DIFFICULTY =
        new DungeonDefinition.Difficulty(25, 30, 24, 31);

    @Test
    void blocksEmptyAndWipedParties() {
        DungeonDefinition.Eligibility settings = settings("average", "warn");

        var empty = DungeonEntryEligibility.evaluate(
            settings, DIFFICULTY,
            new DungeonEntryEligibility.PartySnapshot(0, 0, 0, 0)
        );
        var wiped = DungeonEntryEligibility.evaluate(
            settings, DIFFICULTY,
            new DungeonEntryEligibility.PartySnapshot(3, 0, 27, 30)
        );

        assertFalse(empty.allowed());
        assertEquals(DungeonEntryEligibility.Issue.PARTY_TOO_SMALL, empty.issue());
        assertFalse(wiped.allowed());
        assertEquals(DungeonEntryEligibility.Issue.NO_USABLE_POKEMON, wiped.issue());
    }

    @Test
    void warnsOrBlocksUsingTheConfiguredLevelMeasure() {
        var party = new DungeonEntryEligibility.PartySnapshot(3, 3, 20, 27);
        var warning = DungeonEntryEligibility.evaluate(
            settings("average", "warn"), DIFFICULTY, party
        );
        var allowed = DungeonEntryEligibility.evaluate(
            settings("highest", "enforce"), DIFFICULTY, party
        );

        assertTrue(warning.allowed());
        assertEquals(DungeonEntryEligibility.Issue.LEVEL_OUTSIDE_RECOMMENDED, warning.issue());
        assertEquals(20, warning.measuredLevel());
        assertTrue(allowed.allowed());
        assertEquals(27, allowed.measuredLevel());
    }

    private static DungeonDefinition.Eligibility settings(
        String measure, String policy
    ) {
        return new DungeonDefinition.Eligibility(1, 6, true, measure, policy);
    }
}
