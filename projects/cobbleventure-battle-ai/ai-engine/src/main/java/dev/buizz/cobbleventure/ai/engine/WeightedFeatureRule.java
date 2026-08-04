package dev.buizz.cobbleventure.ai.engine;

import dev.buizz.cobbleventure.ai.api.ActionCandidate;
import dev.buizz.cobbleventure.ai.api.BattleObservation;

import java.util.Optional;

public final class WeightedFeatureRule implements ScoringRule {
    private final String featureId;
    private final double weight;

    public WeightedFeatureRule(String featureId, double weight) {
        if (featureId == null || featureId.isBlank()) {
            throw new IllegalArgumentException("featureId must not be blank");
        }
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        this.featureId = featureId;
        this.weight = weight;
    }

    @Override
    public String id() {
        return "feature:" + featureId;
    }

    @Override
    public Optional<ScoreAdjustment> evaluate(
            BattleObservation observation,
            ActionCandidate candidate
    ) {
        double featureValue = candidate.feature(featureId);
        if (featureValue == 0.0) {
            return Optional.empty();
        }
        return Optional.of(new ScoreAdjustment(
                featureValue * weight,
                id() + "=" + featureValue
        ));
    }
}
