package dev.buizz.cobbleventure.ai.engine;

import dev.buizz.cobbleventure.ai.api.StrategyAxis;
import dev.buizz.cobbleventure.ai.api.StrategyEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftmaxStrategySelectorTest {
    private final SoftmaxStrategySelector selector = new SoftmaxStrategySelector();

    @Test
    void catalogContainsDistinctCompleteArchetypes() {
        var archetypes = DefaultStrategyArchetypes.all();

        assertEquals(8, archetypes.size());
        assertEquals(8, archetypes.stream().map(archetype -> archetype.id()).distinct().count());
        assertTrue(archetypes.stream().allMatch(
                archetype -> archetype.hints().size() == StrategyAxis.values().length
        ));
    }

    @Test
    void keepsOnlyThreeHighestScoringStrategies() {
        var result = selector.select(
                List.of(
                        evaluation("balanced", 0.50),
                        evaluation("offensive", 0.80),
                        evaluation("defensive", 0.60),
                        evaluation("ace_denial", 0.70)
                ),
                3,
                0.2,
                42L
        );

        assertEquals(
                List.of("offensive", "ace_denial", "defensive"),
                result.candidates().stream().map(candidate -> candidate.strategyId()).toList()
        );
    }

    @Test
    void sameSeedProducesSameSelection() {
        var evaluations = List.of(
                evaluation("balanced", 0.50),
                evaluation("offensive", 0.51),
                evaluation("defensive", 0.49)
        );

        var first = selector.select(evaluations, 3, 0.5, 991L);
        var second = selector.select(evaluations, 3, 0.5, 991L);

        assertEquals(first, second);
    }

    @Test
    void higherScoreProducesHigherProbability() {
        var result = selector.select(
                List.of(
                        evaluation("first", 0.8),
                        evaluation("second", 0.6),
                        evaluation("third", 0.4)
                ),
                3,
                0.25,
                7L
        );

        assertTrue(result.candidates().get(0).probability()
                > result.candidates().get(1).probability());
        assertTrue(result.candidates().get(1).probability()
                > result.candidates().get(2).probability());
        assertEquals(
                1.0,
                result.candidates().stream().mapToDouble(candidate -> candidate.probability()).sum(),
                1.0e-12
        );
    }

    @Test
    void rejectsDuplicateStrategyEvaluations() {
        var duplicate = List.of(
                evaluation("balanced", 0.5),
                evaluation("balanced", 0.6)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> selector.select(duplicate, 3, 0.5, 1L)
        );
    }

    private static StrategyEvaluation evaluation(String id, double score) {
        return new StrategyEvaluation(id, 100, score, Math.max(0.0, score - 0.1), score);
    }
}
