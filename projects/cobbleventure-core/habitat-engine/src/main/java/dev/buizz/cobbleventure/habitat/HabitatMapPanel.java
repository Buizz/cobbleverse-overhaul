package dev.buizz.cobbleventure.habitat;

import dev.buizz.cobbleventure.habitat.CobblemonSpawnRuleCatalog.CobblemonSpawnRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 지도에서 한 지역/바이옴을 선택했을 때 오른쪽 패널에 표시할 서버 계산 결과. */
public record HabitatMapPanel(
    GeneratedHabitatZone zone,
    List<SpawnEntry> pokemon
) {
    public record SpawnEntry(
        int dexNumber,
        String pokemonId,
        Map<String, String> displayName,
        String habitatRarity,
        EncounterCandidate.MatchReason matchReason,
        boolean naturalSpawnSupported,
        Set<String> buckets,
        Set<String> levelRanges,
        Set<String> positionTypes,
        Map<String, Double> baseWeightByBucket,
        Map<String, Double> baseBucketSharePercent,
        List<CobblemonSpawnRule> originalRules
    ) {
    }
}
