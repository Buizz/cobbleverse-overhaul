package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.ActionCandidate;
import dev.buizz.cobbleverse.ai.api.ActionType;
import dev.buizz.cobbleverse.ai.api.BattleAction;
import dev.buizz.cobbleverse.ai.api.BattleObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedDecisionEngineTest {
    @Test
    void selectsHighestUtilityAndKeepsReasons() {
        var engine = new RuleBasedDecisionEngine(List.of(
                new WeightedFeatureRule("ko_probability", 100.0)
        ));
        var observation = new BattleObservation(
                1,
                false,
                List.of(
                        candidate("move:tackle", ActionType.MOVE, 10.0, 0.0),
                        candidate("move:thunderbolt", ActionType.MOVE, 5.0, 0.8)
                ),
                Map.of()
        );

        var result = engine.decide(observation);

        assertEquals("move:thunderbolt", result.choice().orElseThrow().action().id());
        assertEquals(85.0, result.choice().orElseThrow().utility());
        assertEquals(2, result.choice().orElseThrow().reasons().size());
    }

    @Test
    void forcedSwitchRejectsOtherActionTypes() {
        var engine = new RuleBasedDecisionEngine(List.of());
        var observation = new BattleObservation(
                3,
                true,
                List.of(
                        candidate("move:protect", ActionType.MOVE, 100.0, 0.0),
                        candidate("switch:slot-2", ActionType.SWITCH, 1.0, 0.0)
                ),
                Map.of()
        );

        assertEquals(
                "switch:slot-2",
                engine.decide(observation).choice().orElseThrow().action().id()
        );
    }

    @Test
    void returnsEmptyDecisionWhenNoLegalCandidateExists() {
        var engine = new RuleBasedDecisionEngine(List.of());
        var illegal = new ActionCandidate(
                new BattleAction("move:disabled", ActionType.MOVE),
                false,
                10.0,
                Map.of()
        );

        var result = engine.decide(new BattleObservation(1, false, List.of(illegal), Map.of()));

        assertTrue(result.choice().isEmpty());
        assertTrue(result.ranking().isEmpty());
    }

    @Test
    void resolvesTiesDeterministicallyByActionId() {
        var engine = new RuleBasedDecisionEngine(List.of());
        var observation = new BattleObservation(
                1,
                false,
                List.of(
                        candidate("move:z", ActionType.MOVE, 10.0, 0.0),
                        candidate("move:a", ActionType.MOVE, 10.0, 0.0)
                ),
                Map.of()
        );

        assertEquals("move:a", engine.decide(observation).choice().orElseThrow().action().id());
    }

    private static ActionCandidate candidate(
            String id,
            ActionType type,
            double baseUtility,
            double koProbability
    ) {
        return new ActionCandidate(
                new BattleAction(id, type),
                true,
                baseUtility,
                Map.of("ko_probability", koProbability)
        );
    }
}
