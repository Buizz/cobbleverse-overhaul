package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.adventure.AdventureWorldContext;
import dev.buizz.cobbleventure.playermenu.MapContent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/** Keeps runtime wild spawns and spawn-list integrations on the authored map pool. */
public final class HabitatSpawnRules {
    private HabitatSpawnRules() {}

    public static Set<ResourceLocation> allowedSpecies(
        ServerLevel level, double x, double z
    ) {
        return allowedSpecies(
            level, x, Double.NaN, z,
            CobbleventureBootstrap.wildSpawnRule(level, x, z)
        );
    }

    public static Set<ResourceLocation> allowedSpecies(
        ServerLevel level, double x, double y, double z
    ) {
        return allowedSpecies(
            level, x, y, z,
            CobbleventureBootstrap.wildSpawnRule(
                level, x, z, encounterMethod(level, x, y, z)
            )
        );
    }

    private static Set<ResourceLocation> allowedSpecies(
        ServerLevel level, double x, double y, double z,
        AdventureWorldContext.WildSpawnRule routeRule
    ) {
        // Dungeon runs use their authored pursuit pool. Blocking Cobblemon's
        // independent biome spawner prevents unrelated dimension-biome species
        // from appearing beside those encounters.
        if (DungeonSystem.ownsRandomEncountersAt(level, x, z)) {
            return Set.of();
        }
        // Some overworld structures are only entrances/facades for authored dungeon
        // encounters. Their dedicated cell must not inherit its terrain biome pool.
        if (CobbleventureBootstrap.suppressesNaturalSpawns(level, x, z)) {
            return Set.of();
        }
        // Authored cave/forest encounters are spawned only by PursuitEncounterSystem.
        // An empty natural pool prevents Cobblemon's independent spawner from duplicating them.
        if (CobbleventureBootstrap.authoredEncounterWeights(level, x, z) != null) {
            return Set.of();
        }
        // Log-bridge decks are traversal only. Water below keeps its ocean pool,
        // while the authored land route pool is reserved for real grass patches.
        if (Double.isFinite(y)
            && CobbleventureBootstrap.isLogBridgeDeckSpawn(level, x, y, z)) {
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
                return applyRouteRule(Set.of(), routeRule);
            }
            LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
            for (MapContent.Pokemon pokemon : content.spawnBiome(tile).pokemon()) {
                ResourceLocation id = ResourceLocation.tryParse(pokemon.id());
                if (id != null) {
                    result.add(id);
                }
            }
            return applyRouteRule(result, routeRule);
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
        Map<ResourceLocation, Integer> dungeon =
            DungeonSystem.randomEncounterWeightsAt(level, x, z);
        if (dungeon != null) {
            return dungeon;
        }
        Map<ResourceLocation, Integer> authored =
            CobbleventureBootstrap.authoredEncounterWeights(level, x, z);
        if (authored != null) {
            return authored;
        }
        return exclusiveRouteWeights(
            CobbleventureBootstrap.wildSpawnRule(level, x, z)
        );
    }

    private static AdventureWorldContext.WildEncounterMethod encounterMethod(
        ServerLevel level, double x, double y, double z
    ) {
        return Double.isFinite(y)
            && level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER)
            ? AdventureWorldContext.WildEncounterMethod.SURF
            : AdventureWorldContext.WildEncounterMethod.LAND;
    }

    public static Map<ResourceLocation, Integer> authoredEncounterWeights(
        ServerLevel level, double x, double y, double z
    ) {
        Map<ResourceLocation, Integer> dungeon =
            DungeonSystem.randomEncounterWeightsAt(level, x, z);
        if (dungeon != null) {
            return dungeon;
        }
        if (CobbleventureBootstrap.isLogBridgeDeckSpawn(level, x, y, z)) {
            return Map.of();
        }
        Map<ResourceLocation, Integer> authored =
            CobbleventureBootstrap.authoredEncounterWeights(level, x, z);
        if (authored != null) return authored;
        return exclusiveRouteWeights(
            CobbleventureBootstrap.wildSpawnRule(
                level, x, z, encounterMethod(level, x, y, z)
            )
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

    static boolean isLogBridgeDeckHeight(int deckY, double spawnY) {
        int feetY = (int) Math.floor(spawnY);
        return feetY >= deckY && feetY <= deckY + 1;
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
