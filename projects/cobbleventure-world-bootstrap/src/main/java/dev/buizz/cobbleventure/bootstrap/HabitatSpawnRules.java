package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.adventure.AdventureWorldContext;
import dev.buizz.cobbleventure.playermenu.MapContent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Keeps runtime wild spawns and spawn-list integrations on the authored map pool. */
public final class HabitatSpawnRules {
    private HabitatSpawnRules() {}

    public static Set<ResourceLocation> allowedSpecies(
        ServerLevel level, double x, double z
    ) {
        // Authored cave/forest encounters are spawned only by PursuitEncounterSystem.
        // An empty natural pool prevents Cobblemon's independent spawner from duplicating them.
        if (CobbleventureBootstrap.authoredEncounterWeights(level, x, z) != null) {
            return Set.of();
        }
        String dimension = level.dimension().location().toString();
        for (MapContent content : MapContent.all()) {
            if (!dimension.equals(content.dimension())) {
                continue;
            }
            MapContent.Hex hex = content.worldToHex(x, z);
            MapContent.BiomeTile tile = content.tileAt(hex.q(), hex.r());
            if (tile == null) {
                return applyRouteRule(
                    Set.of(),
                    CobbleventureBootstrap.wildSpawnRule(level, x, z)
                );
            }
            LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
            for (MapContent.Pokemon pokemon : content.biome(tile).pokemon()) {
                ResourceLocation id = ResourceLocation.tryParse(pokemon.id());
                if (id != null) {
                    result.add(id);
                }
            }
            return applyRouteRule(
                result,
                CobbleventureBootstrap.wildSpawnRule(level, x, z)
            );
        }
        return null;
    }

    static Set<ResourceLocation> applyRouteRule(
        Set<ResourceLocation> biomeSpecies,
        AdventureWorldContext.WildSpawnRule rule
    ) {
        if (rule == null) {
            return Set.copyOf(biomeSpecies);
        }
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        if (rule.inheritBiome()) {
            result.addAll(biomeSpecies);
            result.removeAll(rule.excludedSpecies());
        }
        for (AdventureWorldContext.WildSpawnAddition addition : rule.additions()) {
            result.add(addition.species());
        }
        return Set.copyOf(result);
    }

    public static Map<ResourceLocation, Integer> authoredEncounterWeights(
        ServerLevel level, double x, double z
    ) {
        Map<ResourceLocation, Integer> authored =
            CobbleventureBootstrap.authoredEncounterWeights(level, x, z);
        if (authored != null) {
            return authored;
        }
        return exclusiveRouteWeights(
            CobbleventureBootstrap.wildSpawnRule(level, x, z)
        );
    }

    static Map<ResourceLocation, Integer> exclusiveRouteWeights(
        AdventureWorldContext.WildSpawnRule rule
    ) {
        if (rule == null || rule.inheritBiome()) {
            return null;
        }
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (AdventureWorldContext.WildSpawnAddition addition : rule.additions()) {
            result.merge(addition.species(), addition.weight(), Integer::sum);
        }
        return Map.copyOf(result);
    }

    public static boolean allowsSpawnDetail(
        Set<ResourceLocation> allowedSpecies, String spawnDetailId
    ) {
        if (allowedSpecies == null) {
            return true;
        }
        String path = spawnDetailId;
        int namespaceSeparator = path.indexOf(':');
        if (namespaceSeparator >= 0) {
            path = path.substring(namespaceSeparator + 1);
        }
        for (ResourceLocation species : allowedSpecies) {
            String speciesPath = species.getPath();
            if (path.equals(speciesPath)
                || path.startsWith(speciesPath + "-")
                || path.startsWith(speciesPath + "_")) {
                return true;
            }
        }
        return false;
    }
}
