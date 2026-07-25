package dev.buizz.cobbleverse.ai.api;

import java.util.List;

public interface StrategySelector {
    StrategySelection select(
            List<StrategyEvaluation> evaluations,
            int topK,
            double temperature,
            long strategySeed
    );
}
