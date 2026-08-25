package dev.buizz.cobbleventure.bootstrap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Run-scoped, server-authoritative ownership state for direct dungeon loot claims. */
final class DungeonLootClaims {
    private final Map<String, Set<UUID>> claims = new HashMap<>();

    ClaimResult claim(String ownership, String containerId, UUID playerId) {
        if (ownership.equals("run_shared")) {
            throw new IllegalArgumentException("run_shared containers use their inventory");
        }
        if (!ownership.equals("per_player") && !ownership.equals("first_claim")) {
            throw new IllegalArgumentException("Unknown dungeon loot ownership: " + ownership);
        }
        Set<UUID> owners = claims.computeIfAbsent(
            containerId, ignored -> new HashSet<>()
        );
        boolean alreadyClaimed = ownership.equals("per_player")
            ? owners.contains(playerId) : !owners.isEmpty();
        if (alreadyClaimed) return ClaimResult.ALREADY_CLAIMED;
        owners.add(playerId);
        return ClaimResult.CLAIMED;
    }

    void release(String containerId, UUID playerId) {
        Set<UUID> owners = claims.get(containerId);
        if (owners == null) return;
        owners.remove(playerId);
        if (owners.isEmpty()) claims.remove(containerId);
    }

    enum ClaimResult {
        CLAIMED,
        ALREADY_CLAIMED
    }
}
