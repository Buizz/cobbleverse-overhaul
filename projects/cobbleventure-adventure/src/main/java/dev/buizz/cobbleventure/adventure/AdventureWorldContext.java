package dev.buizz.cobbleventure.adventure;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
    record WildSpawnLevelRange(int minLevel, int maxLevel) {}

    record WildSpawnRule(
        boolean inheritBiome,
        Set<ResourceLocation> excludedSpecies,
        List<WildSpawnAddition> additions,
        Map<ResourceLocation, WildSpawnLevelRange> levelOverrides,
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
                inheritBiome, excludedSpecies, additions, levelOverrides,
                true, 1.0D
            );
        }

        public WildSpawnRule {
            excludedSpecies = Set.copyOf(excludedSpecies);
            additions = List.copyOf(additions);
            levelOverrides = Map.copyOf(levelOverrides);
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
}
