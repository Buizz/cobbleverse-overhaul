package dev.buizz.cobbleverse.ai.api;

public record StrategyEvaluation(
        String strategyId,
        int battleCount,
        double winRate,
        double winRateLowerBound,
        double selectionScore
) {
    public StrategyEvaluation {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (battleCount < 0) {
            throw new IllegalArgumentException("battleCount must not be negative");
        }
        requireProbability(winRate, "winRate");
        requireProbability(winRateLowerBound, "winRateLowerBound");
        if (winRateLowerBound > winRate) {
            throw new IllegalArgumentException("winRateLowerBound must not exceed winRate");
        }
        if (!Double.isFinite(selectionScore)) {
            throw new IllegalArgumentException("selectionScore must be finite");
        }
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
