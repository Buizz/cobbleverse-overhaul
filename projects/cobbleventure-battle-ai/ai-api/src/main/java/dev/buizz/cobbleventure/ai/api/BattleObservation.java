package dev.buizz.cobbleventure.ai.api;

import java.util.List;
import java.util.Map;

public record BattleObservation(
        int turn,
        boolean forcedSwitch,
        List<ActionCandidate> candidates,
        Map<String, String> publicFacts
) {
    public BattleObservation {
        if (turn < 1) {
            throw new IllegalArgumentException("turn must be at least 1");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        publicFacts = publicFacts == null ? Map.of() : Map.copyOf(publicFacts);
    }
}
