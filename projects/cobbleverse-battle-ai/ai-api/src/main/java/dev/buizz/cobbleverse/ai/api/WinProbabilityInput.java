package dev.buizz.cobbleverse.ai.api;

import java.util.Map;

public record WinProbabilityInput(
        Map<String, Double> features,
        double informationCompleteness,
        TerminalOutcome terminalOutcome
) {
    public WinProbabilityInput {
        features = features == null ? Map.of() : Map.copyOf(features);
        if (features.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || !Double.isFinite(entry.getValue()))) {
            throw new IllegalArgumentException("features must have non-blank ids and finite values");
        }
        if (!Double.isFinite(informationCompleteness)
                || informationCompleteness < 0.0
                || informationCompleteness > 1.0) {
            throw new IllegalArgumentException("informationCompleteness must be between 0 and 1");
        }
        terminalOutcome = terminalOutcome == null ? TerminalOutcome.ONGOING : terminalOutcome;
    }

    public static WinProbabilityInput ongoing(
            Map<String, Double> features,
            double informationCompleteness
    ) {
        return new WinProbabilityInput(
                features,
                informationCompleteness,
                TerminalOutcome.ONGOING
        );
    }
}
