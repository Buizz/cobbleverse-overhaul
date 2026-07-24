package dev.buizz.cobbleverse.ai.api;

import java.util.List;
import java.util.Optional;

public record DecisionResult(
        Optional<RankedAction> choice,
        List<RankedAction> ranking
) {
    public DecisionResult {
        choice = choice == null ? Optional.empty() : choice;
        ranking = ranking == null ? List.of() : List.copyOf(ranking);
    }

    public static DecisionResult empty() {
        return new DecisionResult(Optional.empty(), List.of());
    }
}
