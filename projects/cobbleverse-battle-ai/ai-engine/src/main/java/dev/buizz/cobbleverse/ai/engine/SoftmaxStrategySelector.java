package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.StrategyCandidate;
import dev.buizz.cobbleverse.ai.api.StrategyEvaluation;
import dev.buizz.cobbleverse.ai.api.StrategySelection;
import dev.buizz.cobbleverse.ai.api.StrategySelector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public final class SoftmaxStrategySelector implements StrategySelector {
    private static final Comparator<StrategyEvaluation> EVALUATION_RANKING = Comparator
            .comparingDouble(StrategyEvaluation::selectionScore)
            .reversed()
            .thenComparing(StrategyEvaluation::strategyId);

    @Override
    public StrategySelection select(
            List<StrategyEvaluation> evaluations,
            int topK,
            double temperature,
            long strategySeed
    ) {
        if (evaluations == null || evaluations.isEmpty()) {
            throw new IllegalArgumentException("evaluations must not be empty");
        }
        if (evaluations.stream().anyMatch(evaluation -> evaluation == null)) {
            throw new IllegalArgumentException("evaluations must not contain null");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be at least 1");
        }
        if (!Double.isFinite(temperature) || temperature <= 0.0) {
            throw new IllegalArgumentException("temperature must be finite and greater than 0");
        }

        var ids = new HashSet<String>();
        if (evaluations.stream().anyMatch(evaluation -> !ids.add(evaluation.strategyId()))) {
            throw new IllegalArgumentException("strategy ids must be unique");
        }

        var finalists = evaluations.stream()
                .sorted(EVALUATION_RANKING)
                .limit(topK)
                .toList();
        double maximumScore = finalists.getFirst().selectionScore();

        var weights = finalists.stream()
                .mapToDouble(evaluation ->
                        Math.exp((evaluation.selectionScore() - maximumScore) / temperature))
                .toArray();
        double weightSum = java.util.Arrays.stream(weights).sum();

        var candidates = new ArrayList<StrategyCandidate>(finalists.size());
        for (int index = 0; index < finalists.size(); index++) {
            var evaluation = finalists.get(index);
            candidates.add(new StrategyCandidate(
                    evaluation.strategyId(),
                    evaluation.selectionScore(),
                    weights[index] / weightSum
            ));
        }

        String selectedStrategyId = choose(candidates, unitDouble(strategySeed));
        return new StrategySelection(candidates, selectedStrategyId, strategySeed);
    }

    private static String choose(List<StrategyCandidate> candidates, double draw) {
        double cumulative = 0.0;
        for (var candidate : candidates) {
            cumulative += candidate.probability();
            if (draw < cumulative) {
                return candidate.strategyId();
            }
        }
        return candidates.getLast().strategyId();
    }

    private static double unitDouble(long seed) {
        long value = seed + 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
}
