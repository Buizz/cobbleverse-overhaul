package dev.buizz.cobbleventure.ai.api;

import java.util.Map;

public record StrategyArchetype(
        String id,
        Map<StrategyAxis, Double> hints
) {
    public StrategyArchetype {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        hints = hints == null ? Map.of() : Map.copyOf(hints);
        if (hints.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getValue() == null
                        || !Double.isFinite(entry.getValue())
                        || entry.getValue() < 0.0
                        || entry.getValue() > 1.0)) {
            throw new IllegalArgumentException("strategy hints must be between 0 and 1");
        }
    }

    public double hint(StrategyAxis axis) {
        return hints.getOrDefault(axis, 0.5);
    }
}
