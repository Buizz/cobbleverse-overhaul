package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.TerminalOutcome;
import dev.buizz.cobbleverse.ai.api.WinProbabilityInput;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearWinProbabilityModelTest {
    private static final double TOLERANCE = 1.0e-12;

    @Test
    void returnsExactProbabilityForTerminalStates() {
        var model = model();

        var win = model.estimate(new WinProbabilityInput(Map.of(), 0.0, TerminalOutcome.WIN));
        var loss = model.estimate(new WinProbabilityInput(Map.of(), 0.0, TerminalOutcome.LOSS));

        assertEquals(1.0, win.probability());
        assertEquals(1.0, win.confidence());
        assertEquals(0.0, loss.probability());
        assertEquals(1.0, loss.confidence());
    }

    @Test
    void neutralFeaturesProduceEvenWinProbability() {
        var estimate = model().estimate(WinProbabilityInput.ongoing(
                Map.of("alive_difference", 0.0, "hp_difference", 0.0),
                1.0
        ));

        assertEquals(0.5, estimate.probability(), TOLERANCE);
        assertEquals(1.0, estimate.confidence(), TOLERANCE);
    }

    @Test
    void strongerTeamStateRaisesEstimatedWinProbability() {
        var model = model();

        var disadvantaged = model.estimate(WinProbabilityInput.ongoing(
                Map.of("alive_difference", -1.0, "hp_difference", -0.25),
                1.0
        ));
        var advantaged = model.estimate(WinProbabilityInput.ongoing(
                Map.of("alive_difference", 1.0, "hp_difference", 0.25),
                1.0
        ));

        assertTrue(advantaged.probability() > disadvantaged.probability());
        assertEquals("alive_difference", advantaged.topFactors().getFirst().id());
    }

    @Test
    void confidenceIncludesInformationCompletenessAndFeatureCoverage() {
        var estimate = model().estimate(WinProbabilityInput.ongoing(
                Map.of("alive_difference", 1.0),
                0.8
        ));

        assertEquals(0.6, estimate.confidence(), TOLERANCE);
    }

    private static LinearWinProbabilityModel model() {
        return new LinearWinProbabilityModel(
                "heuristic-test-v1",
                0.0,
                Map.of(
                        "alive_difference", 3.0,
                        "hp_difference", 1.0
                ),
                3
        );
    }
}
