package dev.buizz.cobbleventure.adventure;

import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

/** World-owned queries consumed by the platform-neutral adventure rules. */
public interface AdventureWorldContext {
    record WildSpawnAddition(ResourceLocation species, int minLevel, int maxLevel) {}

    record WildSpawnRule(
        boolean inheritBiome,
        Set<ResourceLocation> excludedSpecies,
        List<WildSpawnAddition> additions
    ) {
        public WildSpawnRule {
            excludedSpecies = Set.copyOf(excludedSpecies);
            additions = List.copyOf(additions);
        }
    }

    Integer averageWildSpawnLevel(ServerLevel level, double x, double z);

    WildSpawnRule wildSpawnRule(ServerLevel level, double x, double z);

    String authoredWeatherAt(ServerPlayer player);
}
