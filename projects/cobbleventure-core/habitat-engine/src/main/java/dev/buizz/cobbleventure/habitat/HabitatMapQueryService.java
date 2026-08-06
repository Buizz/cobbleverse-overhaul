package dev.buizz.cobbleventure.habitat;

import dev.buizz.cobbleventure.habitat.CobblemonSpawnRuleCatalog.CobblemonSpawnRule;
import dev.buizz.cobbleventure.habitat.HabitatMapPanel.SpawnEntry;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 지도 우측 패널의 단일 조회 서비스.
 * 확률은 동적 조건 적용 전 동일 Cobblemon 버킷 안에서의 기본 가중치 비중이다.
 */
public final class HabitatMapQueryService {
    private final WorldHabitatIndex world;
    private final CobblemonSpawnRuleIndex spawnRules;

    public HabitatMapQueryService(WorldHabitatIndex world, CobblemonSpawnRuleIndex spawnRules) {
        this.world = world;
        this.spawnRules = spawnRules;
    }

    public HabitatMapPanel panelFor(String locationId) {
        GeneratedHabitatZone zone = world.requireZone(locationId);
        List<PendingEntry> pending = world.encountersAt(locationId).stream()
            .map(candidate -> pending(candidate, spawnRules.rulesFor(candidate.pokemonId())))
            .toList();
        Map<String, Double> totals = bucketTotals(pending);
        List<SpawnEntry> entries = pending.stream()
            .map(entry -> finish(entry, totals))
            .sorted(Comparator.comparingInt(SpawnEntry::dexNumber))
            .toList();
        return new HabitatMapPanel(zone, entries);
    }

    public List<GeneratedHabitatZone> visibleZones(Set<String> discoveredLocationIds) {
        Set<String> discovered = discoveredLocationIds == null ? Set.of() : discoveredLocationIds;
        return world.zones().stream()
            .filter(zone -> discovered.contains(zone.locationId()))
            .toList();
    }

    public HabitatMapPanel discoveredPanelFor(String locationId, Set<String> discoveredLocationIds) {
        Set<String> discovered = discoveredLocationIds == null ? Set.of() : discoveredLocationIds;
        if (!discovered.contains(locationId)) {
            throw new IllegalArgumentException("아직 발견하지 않은 월드 서식지입니다: " + locationId);
        }
        return panelFor(locationId);
    }

    private PendingEntry pending(EncounterCandidate candidate, List<CobblemonSpawnRule> rules) {
        Map<String, Double> weightByBucket = new LinkedHashMap<>();
        Set<String> levels = new LinkedHashSet<>();
        Set<String> positions = new LinkedHashSet<>();
        for (CobblemonSpawnRule rule : rules) {
            weightByBucket.merge(rule.bucket(), rule.weight(), Double::sum);
            if (rule.level() != null && !rule.level().isBlank()) {
                levels.add(rule.level());
            }
            if (rule.spawnablePositionType() != null && !rule.spawnablePositionType().isBlank()) {
                positions.add(rule.spawnablePositionType());
            }
        }
        return new PendingEntry(candidate, rules, weightByBucket, levels, positions);
    }

    private Map<String, Double> bucketTotals(List<PendingEntry> entries) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (PendingEntry entry : entries) {
            entry.weightByBucket().forEach((bucket, weight) -> totals.merge(bucket, weight, Double::sum));
        }
        return totals;
    }

    private SpawnEntry finish(PendingEntry pending, Map<String, Double> totals) {
        Map<String, Double> shares = new LinkedHashMap<>();
        pending.weightByBucket().forEach((bucket, weight) -> {
            double total = totals.getOrDefault(bucket, 0.0);
            if (total > 0) {
                shares.put(bucket, Math.round((weight / total * 100.0) * 100.0) / 100.0);
            }
        });
        EncounterCandidate candidate = pending.candidate();
        return new SpawnEntry(
            candidate.dexNumber(),
            candidate.pokemonId(),
            candidate.displayName(),
            candidate.rarity(),
            candidate.matchReason(),
            !pending.rules().isEmpty(),
            Set.copyOf(pending.weightByBucket().keySet()),
            Set.copyOf(pending.levels()),
            Set.copyOf(pending.positions()),
            Map.copyOf(pending.weightByBucket()),
            Map.copyOf(shares),
            pending.rules()
        );
    }

    private record PendingEntry(
        EncounterCandidate candidate,
        List<CobblemonSpawnRule> rules,
        Map<String, Double> weightByBucket,
        Set<String> levels,
        Set<String> positions
    ) {
    }
}
