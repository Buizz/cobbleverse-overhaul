package dev.buizz.cobbleventure.ai.api;

import java.util.Objects;

public record BattleAction(String id, ActionType type) {
    public BattleAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("action id must not be blank");
        }
        Objects.requireNonNull(type, "type");
    }
}
