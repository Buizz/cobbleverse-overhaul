package dev.buizz.cobbleventure.habitat;

import java.util.Map;

public record PokemonLocation(
    String locationId,
    String settlementId,
    String zoneId,
    String biomeId,
    Map<String, String> displayName,
    int centerX,
    int centerZ,
    int radiusBlocks,
    String rarity,
    EncounterCandidate.MatchReason matchReason
) {
}
