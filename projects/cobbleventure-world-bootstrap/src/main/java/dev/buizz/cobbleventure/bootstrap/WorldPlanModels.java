package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable models shared by world planning, generation, and runtime sampling. */
final class WorldPlanModels {
    private WorldPlanModels() {}

    record HexCoord(int q, int r) {
        private static final List<HexCoord> DIRECTIONS = List.of(
            new HexCoord(1, 0), new HexCoord(1, -1), new HexCoord(0, -1),
            new HexCoord(-1, 0), new HexCoord(-1, 1), new HexCoord(0, 1)
        );

        HexCoord plus(HexCoord other) { return new HexCoord(q + other.q, r + other.r); }
        HexCoord scale(int amount) { return new HexCoord(q * amount, r * amount); }
        int distance(HexCoord other) {
            int deltaQ = q - other.q;
            int deltaR = r - other.r;
            int deltaS = -q - r + other.q + other.r;
            return (Math.abs(deltaQ) + Math.abs(deltaR) + Math.abs(deltaS)) / 2;
        }
        List<HexCoord> neighbors() { return DIRECTIONS.stream().map(this::plus).toList(); }
        @Override public String toString() { return q + "," + r; }
    }

    record HexGrid(int radius, CobbleventureBootstrap.BlockPoint origin) {
        CobbleventureBootstrap.Point worldCenter(HexCoord cell) {
            return HexGeometry.worldCenter(radius, origin, cell);
        }
        HexCoord worldToHex(double x, double z) {
            return HexGeometry.worldToHex(radius, origin, x, z);
        }
        HexBounds bounds(Set<HexCoord> cells) {
            return HexGeometry.bounds(radius, origin, cells);
        }
    }

    record HexBounds(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(CobbleventureBootstrap.Point point) {
            return point.x() >= minX && point.x() <= maxX
                && point.z() >= minZ && point.z() <= maxZ;
        }
    }

    record SurroundingRegion(
        String id, String biome, int tileCount, String preferredDirection,
        String growth, double influenceRadiusBlocks, double edgeNoise,
        String boundaryProfile, TerrainProfile terrainProfile, String accessRequirement
    ) {}

    record HexSettlement(
        String settlement, HexCoord anchor, int townRadiusCells,
        String townFootprintShape, List<HexCoord> customFootprint, String townBiome,
        List<SurroundingRegion> surroundings, String boundaryProfile,
        TerrainProfile terrainProfile, String accessRequirement
    ) {}

    record PlacedTile(
        HexCoord coordinate, String biome, String boundaryProfile,
        TerrainProfile terrainProfile, String accessRequirement
    ) {}

    record HexConnection(
        String id, String displayName, String from, String to, String routeBiome, int widthCells,
        String pathfinding, int detourCells, double corridorWidthBlocks,
        double edgeNoise, String boundaryProfile, TerrainProfile terrainProfile,
        String surfaceStyle, LogBridgeLayout logBridgeLayout,
        String accessRequirement, List<HexCoord> cells,
        RoutePokemonSpawns pokemonSpawns, List<RouteNpcPlacement> npcPlacements,
        RegionalTrainerPopulation trainerPopulation
    ) {}

    record LogBridgeLayout(String pattern, double detourBlocks) {
        static LogBridgeLayout straight() {
            return new LogBridgeLayout("straight", 18.0D);
        }
    }

    record RouteNpcPlacement(
        String id, String npc, int progressPercent, String side,
        double offsetBlocks, String facing, double spawnChance, String respawnPolicy,
        String triggerOverride
    ) {}

    record RegionalTrainerPopulation(
        boolean enabled, int count, String triggerOverride, List<String> candidates,
        Map<String, String> trainerTriggerOverrides
    ) {
        static RegionalTrainerPopulation disabled() {
            return new RegionalTrainerPopulation(
                false, 0, "proximity", List.of(), Map.of()
            );
        }

        String triggerFor(String trainerId) {
            return trainerTriggerOverrides.getOrDefault(trainerId, triggerOverride);
        }
    }

    record RoutePokemonAddition(
        String species, int minLevel, int maxLevel, boolean spawnAsEvolved,
        int weight
    ) {}

    record PokemonLevelOverride(String species, int minLevel, int maxLevel) {}

    record RoutePokemonPool(
        boolean inheritBiome, Set<String> excludedSpecies,
        List<RoutePokemonAddition> additions,
        Map<String, PokemonLevelOverride> levelOverrides,
        boolean enabled, double triggerChance
    ) {
        static RoutePokemonPool inherited() {
            return new RoutePokemonPool(
                true, Set.of(), List.of(), Map.of(), true, 1.0D
            );
        }
    }

    record RoutePokemonSpawns(
        boolean inheritBiome, Set<String> excludedSpecies,
        List<RoutePokemonAddition> additions,
        Map<String, PokemonLevelOverride> levelOverrides,
        Map<String, RoutePokemonPool> encounterPools
    ) {
        static RoutePokemonSpawns inherited() {
            return new RoutePokemonSpawns(
                true, Set.of(), List.of(), Map.of(), Map.of()
            );
        }

        RoutePokemonPool pool(String method) {
            if (method == null || method.equals("land")) {
                return new RoutePokemonPool(
                    inheritBiome, excludedSpecies, additions, levelOverrides,
                    true, 1.0D
                );
            }
            return encounterPools.get(method);
        }
    }

    record CellPlan(
        String biome, String boundaryProfile, String kind, String owner,
        double influenceRadius, double edgeNoise, TerrainProfile terrainProfile,
        String accessRequirement, String surfaceStyle
    ) {}

    record ConnectionPath(
        String id, String displayName, String from, String to, String biome, String boundaryProfile,
        double corridorWidthBlocks, double edgeNoise, TerrainProfile terrainProfile,
        String surfaceStyle, String accessRequirement, List<HexCoord> cells,
        List<CobbleventureBootstrap.Point> centerline, RouteBounds bounds,
        RoutePokemonSpawns pokemonSpawns, List<RouteNpcPlacement> npcPlacements,
        RegionalTrainerPopulation trainerPopulation
    ) {}

    record TerrainProfile(
        int baseHeightOffset, int heightVariation, double noiseScaleBlocks,
        int connectionHeight
    ) {}

    record RouteBounds(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(double x, double z, double margin) {
            return x >= minX - margin && x <= maxX + margin
                && z >= minZ - margin && z <= maxZ + margin;
        }
    }

    record WarpedPoint(double x, double z) {}

    record TerrainSample(
        String biome, String boundaryProfile, String kind, String owner,
        TerrainProfile terrainProfile, String accessRequirement, String surfaceStyle
    ) {}

    record TerrainSamplePoint(
        CobbleventureBootstrap.Point point, TerrainSample sample
    ) {}

    record TreeProfile(
        String log, String leaves, int spacing, int minHeight, int maxHeight
    ) {}

    record BoundaryProfile(
        String id, String type, int width, int height, int foundationDepth,
        String collision, String coreBlock, List<String> surfaceBlocks, TreeProfile tree
    ) {}

    record HexWorldPlan(
        HexGrid grid, long seed, Map<HexCoord, CellPlan> cells,
        List<ConnectionPath> paths, Map<String, HexSettlement> settlements,
        Map<String, BoundaryProfile> boundaryProfiles, String defaultEmptyTerrain,
        Map<HexCoord, String> emptyTerrainTiles,
        Map<HexCoord, EnvironmentOverride> environmentOverrides,
        Map<HexCoord, Integer> levelOverrides, List<CaveEntrancePlan> caveEntrances,
        List<WorldGateSystem.Gate> gates
    ) {}

    record EnvironmentOverride(String temperature, String humidity, String weather) {}

    record CaveEntrancePlan(
        String id, String cave, String entrance,
        String surfaceTransition, String undergroundModule, String undergroundConnector,
        HexCoord anchor, String facing,
        String structure, Map<String, String> structureVariants,
        boolean pokemonCenterEnabled, String pokemonCenterStructure,
        HexCoord pokemonCenterOffset,
        CobbleventureBootstrap.BlockPoint destination,
        CobbleventureBootstrap.BlockPoint portalAnchor,
        NaturalCaveGenerator.Settings generationSettings
    ) {}

    record PathNode(HexCoord cell, int cost, int score) {}
}
