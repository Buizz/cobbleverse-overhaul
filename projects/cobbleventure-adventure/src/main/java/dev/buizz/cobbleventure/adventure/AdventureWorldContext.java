package dev.buizz.cobbleventure.adventure;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

/** World-owned queries consumed by the platform-neutral adventure rules. */
public interface AdventureWorldContext {
    enum WildEncounterMethod {
        LAND("land"), SURF("surf"), OLD_ROD("old_rod"),
        GOOD_ROD("good_rod"), SUPER_ROD("super_rod"), HEADBUTT("headbutt");

        private final String serializedName;

        WildEncounterMethod(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }
    }

    record WildSpawnAddition(
        ResourceLocation species, boolean spawnAsEvolved, int weight
    ) {
        public WildSpawnAddition(ResourceLocation species, boolean spawnAsEvolved) {
            this(species, spawnAsEvolved, 1);
        }

        public WildSpawnAddition {
            weight = Math.max(1, weight);
        }
    }
    record WildSpawnLevelRange(int minLevel, int maxLevel, Map<Integer, Integer> levelWeights) {
        public WildSpawnLevelRange(int minLevel, int maxLevel) {
            this(minLevel, maxLevel, Map.of());
        }

        public WildSpawnLevelRange {
            // Stable order also makes each weighted roll reproducible in regression tests.
            var sorted = new java.util.TreeMap<>(levelWeights);
            for (var entry : sorted.entrySet()) {
                if (entry.getKey() < Math.max(1, minLevel)
                    || entry.getKey() > Math.min(100, maxLevel)
                    || entry.getValue() < 1 || entry.getValue() > 10000) {
                    throw new IllegalArgumentException("Invalid wild encounter level weight: " + entry);
                }
            }
            levelWeights = java.util.Collections.unmodifiableMap(sorted);
        }

        public int sample(java.util.function.IntUnaryOperator nextInt) {
            if (!levelWeights.isEmpty()) {
                int choice = nextInt.applyAsInt(levelWeights.values().stream().mapToInt(Integer::intValue).sum());
                for (var entry : levelWeights.entrySet()) {
                    choice -= entry.getValue();
                    if (choice < 0) return entry.getKey();
                }
                throw new IllegalStateException("Wild encounter random roll outside weighted range");
            }
            int minimum = Math.max(1, Math.min(100, minLevel));
            int maximum = Math.max(minimum, Math.min(100, maxLevel));
            return minimum + nextInt.applyAsInt(maximum - minimum + 1);
        }
    }

    record FacilityPosition(ResourceLocation dimension, BlockPos position) {}

    record WildSpawnRule(
        boolean inheritBiome,
        Set<ResourceLocation> excludedSpecies,
        List<WildSpawnAddition> additions,
        Map<ResourceLocation, WildSpawnLevelRange> levelOverrides,
        Map<ResourceLocation, String> timeOverrides,
        boolean enabled,
        double triggerChance
    ) {
        public WildSpawnRule(
            boolean inheritBiome,
            Set<ResourceLocation> excludedSpecies,
            List<WildSpawnAddition> additions,
            Map<ResourceLocation, WildSpawnLevelRange> levelOverrides
        ) {
            this(
                inheritBiome, excludedSpecies, additions, levelOverrides, Map.of(),
                true, 1.0D
            );
        }

        public WildSpawnRule(
            boolean inheritBiome,
            Set<ResourceLocation> excludedSpecies,
            List<WildSpawnAddition> additions,
            Map<ResourceLocation, WildSpawnLevelRange> levelOverrides,
            boolean enabled,
            double triggerChance
        ) {
            this(
                inheritBiome, excludedSpecies, additions, levelOverrides, Map.of(),
                enabled, triggerChance
            );
        }

        public WildSpawnRule {
            excludedSpecies = Set.copyOf(excludedSpecies);
            additions = List.copyOf(additions);
            levelOverrides = Map.copyOf(levelOverrides);
            timeOverrides = Map.copyOf(timeOverrides);
            triggerChance = Math.max(0.0D, Math.min(1.0D, triggerChance));
        }
    }

    Integer averageWildSpawnLevel(ServerLevel level, double x, double z);

    /**
     * Returns the authored habitat pool at the supplied position. A {@code null}
     * result leaves Cobblemon's biome pool untouched; an empty set blocks it.
     */
    default Set<ResourceLocation> allowedWildSpecies(
        ServerLevel level, double x, double z
    ) {
        return null;
    }

    /** Runtime variant that can distinguish a bridge deck from water below it. */
    default Set<ResourceLocation> allowedWildSpecies(
        ServerLevel level, double x, double y, double z
    ) {
        return allowedWildSpecies(level, x, z);
    }

    WildSpawnRule wildSpawnRule(ServerLevel level, double x, double z);

    /**
     * Returns the authored pool for a concrete encounter source. Implementations
     * may keep supporting legacy worlds by exposing only the land pool.
     */
    default WildSpawnRule wildSpawnRule(
        ServerLevel level, double x, double z, WildEncounterMethod method
    ) {
        return method == WildEncounterMethod.LAND
            ? wildSpawnRule(level, x, z) : null;
    }

    String authoredWeatherAt(ServerPlayer player);

    /** Resolves the exterior yard associated with the daycare interior containing the player. */
    default FacilityPosition daycarePaddock(ServerPlayer player) {
        return null;
    }
}
