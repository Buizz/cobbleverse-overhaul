package dev.buizz.cobbleventure.habitat;

import java.util.Map;
import java.util.Set;

/**
 * 월드 생성기가 확정한 실제 서식지 위치. 지도 UI와 게임 어댑터가 함께 사용하는 스냅샷이다.
 */
public record GeneratedHabitatZone(
    String locationId,
    String settlementId,
    String zoneId,
    String biomeId,
    String profileId,
    Map<String, String> displayName,
    int centerX,
    int centerZ,
    int radiusBlocks,
    SpawnSettings settings,
    Set<String> unconditionalSpawns
) {
    public GeneratedHabitatZone {
        displayName = displayName == null ? Map.of() : Map.copyOf(displayName);
        unconditionalSpawns = unconditionalSpawns == null ? Set.of() : Set.copyOf(unconditionalSpawns);
    }
}
