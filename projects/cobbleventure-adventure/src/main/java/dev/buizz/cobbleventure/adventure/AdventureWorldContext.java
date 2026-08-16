package dev.buizz.cobbleventure.adventure;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

/** World-owned queries consumed by the platform-neutral adventure rules. */
public interface AdventureWorldContext {
    record WildSpawnAddition(ResourceLocation species, boolean spawnAsEvolved) {}
    record WildSpawnLevelRange(int minLevel, int maxLevel) {}

    record WildSpawnRule(
        boolean inheritBiome,
        Set<ResourceLocation> excludedSpecies,
        List<WildSpawnAddition> additions,
        Map<ResourceLocation, WildSpawnLevelRange> levelOverrides
    ) {
        public WildSpawnRule {
            excludedSpecies = Set.copyOf(excludedSpecies);
            additions = List.copyOf(additions);
            levelOverrides = Map.copyOf(levelOverrides);
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

    String authoredWeatherAt(ServerPlayer player);
}
