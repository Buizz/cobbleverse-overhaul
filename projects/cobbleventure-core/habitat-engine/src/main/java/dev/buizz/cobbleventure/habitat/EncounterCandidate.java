package dev.buizz.cobbleventure.habitat;

import java.util.Map;

public record EncounterCandidate(
    int dexNumber,
    String pokemonId,
    Map<String, String> displayName,
    String rarity,
    MatchReason matchReason
) {
    public enum MatchReason {
        PRIMARY_HABITAT,
        SECONDARY_HABITAT,
        FORCED_INCLUDE,
        UNCONDITIONAL
    }
}
