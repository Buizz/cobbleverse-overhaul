package dev.buizz.cobbleventure.ai.engine;

import dev.buizz.cobbleventure.ai.api.ActionCandidate;
import dev.buizz.cobbleventure.ai.api.ActionType;
import dev.buizz.cobbleventure.ai.api.AiDifficulty;
import dev.buizz.cobbleventure.ai.api.AiRuntimeProfile;
import dev.buizz.cobbleventure.ai.api.AiSelectionPolicy;
import dev.buizz.cobbleventure.ai.api.BattleAction;
import dev.buizz.cobbleventure.ai.api.BattleObservation;
import dev.buizz.cobbleventure.ai.api.DecisionResult;
import dev.buizz.cobbleventure.ai.api.RankedAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfiguredDecisionEngineTest {
    @Test
    void routesCheaterToCounterOrSearchUsingConfiguredProbability() {
        var router = new ConfiguredDecisionEngine(Map.of(
                AiSelectionPolicy.COMMITTED_ACTION_COUNTER, ignored -> result("counter"),
                AiSelectionPolicy.TWO_TURN_SEARCH, ignored -> result("search")
        ));
        var profile = new AiRuntimeProfile(
                "cobbleventure",
                AiDifficulty.CHEATER,
                "balanced",
                OptionalDouble.of(0.4)
        );
        var observation = new BattleObservation(1, false, List.<ActionCandidate>of(), Map.of());

        assertEquals("counter", router.decide(observation, profile, 0.2).choice().orElseThrow().action().id());
        assertEquals("search", router.decide(observation, profile, 0.8).choice().orElseThrow().action().id());
    }

    @Test
    void routesWinRateExpertToWinProbabilityEngine() {
        var router = new ConfiguredDecisionEngine(Map.of(
                AiSelectionPolicy.WIN_PROBABILITY, ignored -> result("winrate")
        ));
        var profile = new AiRuntimeProfile(
                "cobbleventure",
                AiDifficulty.EXPERT_WINRATE,
                "defensive",
                OptionalDouble.empty()
        );

        assertEquals(
                "winrate",
                router.decide(new BattleObservation(3, false, List.of(), Map.of()), profile, 0.5)
                        .choice().orElseThrow().action().id()
        );
    }

    private static DecisionResult result(String id) {
        var ranked = new RankedAction(new BattleAction(id, ActionType.MOVE), 1.0, List.of("test"));
        return new DecisionResult(Optional.of(ranked), List.of(ranked));
    }
}
