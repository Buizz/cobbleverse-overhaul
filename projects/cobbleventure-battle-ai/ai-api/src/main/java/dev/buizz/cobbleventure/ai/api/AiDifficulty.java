package dev.buizz.cobbleventure.ai.api;

import java.util.Arrays;

public enum AiDifficulty {
    NOVICE("novice", AiSelectionPolicy.HEURISTIC),
    STANDARD("standard", AiSelectionPolicy.HEURISTIC),
    ADVANCED("advanced", AiSelectionPolicy.HEURISTIC),
    EXPERT("expert", AiSelectionPolicy.HEURISTIC),
    EXPERT_WINRATE("expert_winrate", AiSelectionPolicy.WIN_PROBABILITY),
    EXPERT_SEARCH("expert_search", AiSelectionPolicy.TWO_TURN_SEARCH),
    CHEATER("cheater", AiSelectionPolicy.TWO_TURN_SEARCH);

    private final String id;
    private final AiSelectionPolicy defaultPolicy;

    AiDifficulty(String id, AiSelectionPolicy defaultPolicy) {
        this.id = id;
        this.defaultPolicy = defaultPolicy;
    }

    public String id() {
        return id;
    }

    public AiSelectionPolicy defaultPolicy() {
        return defaultPolicy;
    }

    public static AiDifficulty fromId(String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported AI difficulty: " + id));
    }
}
