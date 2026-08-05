package dev.buizz.cobbleventure.ai.api;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRuntimeProfileTest {
    @Test
    void mapsExpertVariantsToTheirRuntimePolicies() {
        assertEquals(AiSelectionPolicy.HEURISTIC, profile(AiDifficulty.EXPERT).selectionPolicy(0.2));
        assertEquals(AiSelectionPolicy.WIN_PROBABILITY, profile(AiDifficulty.EXPERT_WINRATE).selectionPolicy(0.2));
        assertEquals(AiSelectionPolicy.TWO_TURN_SEARCH, profile(AiDifficulty.EXPERT_SEARCH).selectionPolicy(0.2));
    }

    @Test
    void cheaterUsesCommittedActionOnlyWhenProbabilityRollSucceeds() {
        var profile = new AiRuntimeProfile(
                "cobbleventure",
                AiDifficulty.CHEATER,
                "balanced",
                OptionalDouble.of(0.35)
        );

        assertTrue(profile.mayReadCommittedOpponentAction(0.3499));
        assertFalse(profile.mayReadCommittedOpponentAction(0.35));
        assertEquals(AiSelectionPolicy.TWO_TURN_SEARCH, profile.selectionPolicy(0.9));
    }

    @Test
    void rejectsCheatProbabilityOnOtherDifficulties() {
        assertThrows(IllegalArgumentException.class, () -> new AiRuntimeProfile(
                "cobbleventure",
                AiDifficulty.EXPERT_SEARCH,
                "balanced",
                OptionalDouble.of(0.5)
        ));
    }

    private static AiRuntimeProfile profile(AiDifficulty difficulty) {
        return new AiRuntimeProfile(
                "cobbleventure",
                difficulty,
                "balanced",
                OptionalDouble.empty()
        );
    }
}
