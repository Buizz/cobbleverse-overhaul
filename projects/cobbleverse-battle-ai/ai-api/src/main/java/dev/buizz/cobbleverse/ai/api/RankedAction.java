package dev.buizz.cobbleverse.ai.api;

import java.util.List;
import java.util.Objects;

public record RankedAction(
        BattleAction action,
        double utility,
        List<String> reasons
) {
    public RankedAction {
        Objects.requireNonNull(action, "action");
        if (!Double.isFinite(utility)) {
            throw new IllegalArgumentException("utility must be finite");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
