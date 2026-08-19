package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.UUID;

/** Prevents concurrent runs of the same trigger for one player and NPC. */
public record EventSessionKey(
    UUID playerId,
    UUID npcId,
    String scriptId,
    String triggerInstance
) {
    public EventSessionKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(npcId, "npcId");
        if (scriptId == null || scriptId.isBlank()) {
            throw new IllegalArgumentException("scriptId가 필요합니다.");
        }
        if (triggerInstance == null || triggerInstance.isBlank()) {
            throw new IllegalArgumentException("triggerInstance가 필요합니다.");
        }
    }
}
