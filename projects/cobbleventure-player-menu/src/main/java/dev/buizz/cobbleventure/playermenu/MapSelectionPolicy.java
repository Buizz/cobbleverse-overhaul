package dev.buizz.cobbleventure.playermenu;

import java.util.Objects;
import java.util.Set;

/** Pure V1 policy for choosing a stable settlement destination from the world map. */
final class MapSelectionPolicy {
    record Decision(boolean accepted, String settlementId, String message) {
        Decision {
            if (accepted != (settlementId != null)) {
                throw new IllegalArgumentException(
                    "승인된 map selection에만 settlement ID가 필요합니다."
                );
            }
            Objects.requireNonNull(message, "message");
        }
    }

    private MapSelectionPolicy() {}

    static Decision select(
        String settlementId, Set<String> visited, boolean privileged
    ) {
        Objects.requireNonNull(visited, "visited");
        if (settlementId == null) {
            return new Decision(false, null, "마을을 선택해야 합니다.");
        }
        if (!privileged && !visited.contains(settlementId)) {
            return new Decision(false, null, "방문한 마을만 선택할 수 있습니다.");
        }
        return new Decision(true, settlementId, "목적지를 선택했습니다.");
    }
}
