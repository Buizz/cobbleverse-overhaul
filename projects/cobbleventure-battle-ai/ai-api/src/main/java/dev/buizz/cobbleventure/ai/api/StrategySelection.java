package dev.buizz.cobbleventure.ai.api;

import java.util.HashSet;
import java.util.List;

public record StrategySelection(
        List<StrategyCandidate> candidates,
        String selectedStrategyId,
        long strategySeed
) {
    private static final double PROBABILITY_TOLERANCE = 1.0e-9;

    public StrategySelection {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        if (candidates.stream().anyMatch(candidate -> candidate == null)) {
            throw new IllegalArgumentException("candidates must not contain null");
        }
        if (selectedStrategyId == null || selectedStrategyId.isBlank()) {
            throw new IllegalArgumentException("selectedStrategyId must not be blank");
        }

        var ids = new HashSet<String>();
        if (candidates.stream().anyMatch(candidate -> !ids.add(candidate.strategyId()))) {
            throw new IllegalArgumentException("candidate strategy ids must be unique");
        }
        if (!ids.contains(selectedStrategyId)) {
            throw new IllegalArgumentException("selectedStrategyId must identify a candidate");
        }

        double probabilitySum = candidates.stream()
                .mapToDouble(StrategyCandidate::probability)
                .sum();
        if (Math.abs(1.0 - probabilitySum) > PROBABILITY_TOLERANCE) {
            throw new IllegalArgumentException("candidate probabilities must sum to 1");
        }
    }
}
