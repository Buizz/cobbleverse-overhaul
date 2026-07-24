package dev.buizz.cobbleverse.ai.api;

import java.util.Map;
import java.util.Objects;

public record ActionCandidate(
        BattleAction action,
        boolean legal,
        double baseUtility,
        Map<String, Double> features
) {
    public ActionCandidate {
        Objects.requireNonNull(action, "action");
        if (!Double.isFinite(baseUtility)) {
            throw new IllegalArgumentException("baseUtility must be finite");
        }
        features = features == null ? Map.of() : Map.copyOf(features);
        if (features.values().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("feature values must be finite");
        }
    }

    public double feature(String id) {
        return features.getOrDefault(id, 0.0);
    }
}
