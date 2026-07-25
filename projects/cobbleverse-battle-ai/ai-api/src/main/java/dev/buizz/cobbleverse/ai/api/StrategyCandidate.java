package dev.buizz.cobbleverse.ai.api;

public record StrategyCandidate(
        String strategyId,
        double selectionScore,
        double probability
) {
    public StrategyCandidate {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (!Double.isFinite(selectionScore)) {
            throw new IllegalArgumentException("selectionScore must be finite");
        }
        if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be between 0 and 1");
        }
    }
}
