package dev.buizz.cobbleventure.bootstrap;

import static dev.buizz.cobbleventure.bootstrap.WorldPlanModels.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deserializes authored world JSON into immutable planning models. */
final class WorldPlanParser {
    private WorldPlanParser() {}

    static HexGrid grid(JsonObject root) {
        JsonObject grid = root.getAsJsonObject("grid");
        return new HexGrid(
            grid.get("tile_radius_blocks").getAsInt(),
            blockPoint(grid.getAsJsonObject("origin"))
        );
    }

    static List<HexSettlement> settlements(
        JsonObject root, Map<String, Integer> townRadii,
        Map<String, BoundaryProfile> profiles
    ) {
        boolean usesPlacedTiles = root.get("schema_version").getAsInt() >= 2;
        List<HexSettlement> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("settlements")) {
            JsonObject value = element.getAsJsonObject();
            String settlement = required(value, "settlement");
            Integer townRadius = townRadii.get(settlement);
            if (townRadius == null) {
                throw new IllegalStateException(
                    "Hex world references missing settlement: " + settlement
                );
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            List<SurroundingRegion> surroundings = new ArrayList<>();
            for (JsonElement regionElement : usesPlacedTiles
                ? List.<JsonElement>of() : value.getAsJsonArray("surroundings")) {
                JsonObject region = regionElement.getAsJsonObject();
                String boundary = required(region, "boundary_profile");
                requireBoundary(profiles, boundary);
                surroundings.add(new SurroundingRegion(
                    required(region, "id"), required(region, "biome"),
                    region.get("tile_count").getAsInt(),
                    required(region, "preferred_direction"), required(region, "growth"),
                    region.get("influence_radius_blocks").getAsDouble(),
                    region.get("edge_noise").getAsDouble(), boundary,
                    terrainProfile(region), optional(region, "access_requirement")
                ));
            }
            String boundary = value.has("boundary_profile")
                ? required(value, "boundary_profile")
                : "cobbleventure:boundary/dense_tree_line";
            requireBoundary(profiles, boundary);
            List<HexCoord> customFootprint = coordinates(value, "town_footprint_cells");
            result.add(new HexSettlement(
                settlement,
                new HexCoord(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                townRadius,
                value.has("town_footprint_shape")
                    ? required(value, "town_footprint_shape") : "line_q",
                customFootprint, required(value, "town_biome"), List.copyOf(surroundings),
                boundary, terrainProfile(value), optional(value, "access_requirement")
            ));
        }
        return List.copyOf(result);
    }

    static List<HexConnection> connections(
        JsonObject root, Map<String, BoundaryProfile> profiles
    ) {
        List<HexConnection> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("connections")) {
            JsonObject value = element.getAsJsonObject();
            String id = required(value, "id");
            String boundary = value.has("boundary_profile")
                ? required(value, "boundary_profile")
                : "cobbleventure:boundary/dense_tree_line";
            requireBoundary(profiles, boundary);
            result.add(new HexConnection(
                id, value.has("display_name") ? required(value, "display_name") : id,
                optional(value, "from"), optional(value, "to"),
                value.has("route_biome") ? required(value, "route_biome") : "minecraft:plains",
                value.has("width_cells") ? value.get("width_cells").getAsInt() : 1,
                value.has("pathfinding") ? required(value, "pathfinding") : "explicit",
                value.has("detour_cells") ? value.get("detour_cells").getAsInt() : 0,
                value.get("corridor_width_blocks").getAsDouble(),
                value.has("edge_noise") ? value.get("edge_noise").getAsDouble() : 0.0D,
                boundary,
                value.has("terrain_profile")
                    ? terrainProfile(value) : new TerrainProfile(0, 0, 96.0D, 0),
                required(value, "surface_style"), logBridgeLayout(value),
                optional(value, "access_requirement"),
                coordinates(value, "cells"), coordinates(value, "encounter_cells"),
                optionalPoint(value, "from_town_road"),
                optionalPoint(value, "to_town_road"),
                routePokemonSpawns(value), routeNpcPlacements(value),
                regionalTrainerPopulation(value, "automatic_npc_placement", "count")
            ));
        }
        return List.copyOf(result);
    }

    private static CobbleventureBootstrap.Point optionalPoint(
        JsonObject parent, String field
    ) {
        if (!parent.has(field) || !parent.get(field).isJsonObject()) {
            return null;
        }
        JsonObject point = parent.getAsJsonObject(field);
        return new CobbleventureBootstrap.Point(
            point.get("x").getAsInt(), point.get("z").getAsInt()
        );
    }

    private static LogBridgeLayout logBridgeLayout(JsonObject connection) {
        if (!connection.has("log_bridge_layout")
            || !connection.get("log_bridge_layout").isJsonObject()) {
            return LogBridgeLayout.straight();
        }
        JsonObject value = connection.getAsJsonObject("log_bridge_layout");
        return new LogBridgeLayout(
            value.has("pattern") ? required(value, "pattern") : "straight",
            value.has("detour_blocks")
                ? value.get("detour_blocks").getAsDouble() : 18.0D
        );
    }

    private static List<RouteNpcPlacement> routeNpcPlacements(JsonObject connection) {
        if (!connection.has("npc_placements")) return List.of();
        List<RouteNpcPlacement> placements = new ArrayList<>();
        for (JsonElement element : connection.getAsJsonArray("npc_placements")) {
            JsonObject value = element.getAsJsonObject();
            placements.add(new RouteNpcPlacement(
                required(value, "id"), required(value, "npc"),
                value.get("progress_percent").getAsInt(), required(value, "side"),
                value.get("offset_blocks").getAsDouble(), required(value, "facing"),
                value.get("spawn_chance").getAsDouble(), required(value, "respawn_policy"),
                value.has("trigger_override") ? required(value, "trigger_override") : "proximity"
            ));
        }
        return List.copyOf(placements);
    }

    private static RegionalTrainerPopulation regionalTrainerPopulation(
        JsonObject parent, String field, String countField
    ) {
        if (!parent.has(field)) return RegionalTrainerPopulation.disabled();
        JsonObject value = parent.getAsJsonObject(field);
        List<String> candidates = new ArrayList<>();
        if (parent.has("automatic_npc_candidates")) {
            for (JsonElement element : parent.getAsJsonArray("automatic_npc_candidates")) {
                candidates.add(element.getAsString());
            }
        }
        Map<String, String> trainerTriggerOverrides = new LinkedHashMap<>();
        if (value.has("trainer_trigger_overrides")) {
            for (Map.Entry<String, JsonElement> entry
                : value.getAsJsonObject("trainer_trigger_overrides").entrySet()) {
                trainerTriggerOverrides.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return new RegionalTrainerPopulation(
            value.has("enabled") && value.get("enabled").getAsBoolean(),
            value.has(countField) ? value.get(countField).getAsInt() : 0,
            value.has("trigger_override") ? value.get("trigger_override").getAsString() : "proximity",
            List.copyOf(candidates), Map.copyOf(trainerTriggerOverrides)
        );
    }

    private static RoutePokemonSpawns routePokemonSpawns(JsonObject connection) {
        if (!connection.has("pokemon_spawns")) {
            return RoutePokemonSpawns.inherited();
        }
        JsonObject value = connection.getAsJsonObject("pokemon_spawns");
        RoutePokemonPool land = routePokemonPool(value, true);
        Map<String, RoutePokemonPool> encounterPools = new LinkedHashMap<>();
        if (value.has("encounter_pools")
            && value.get("encounter_pools").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                : value.getAsJsonObject("encounter_pools").entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    encounterPools.put(
                        entry.getKey(), routePokemonPool(
                            entry.getValue().getAsJsonObject(), false
                        )
                    );
                }
            }
        }
        return new RoutePokemonSpawns(
            land.inheritBiome(), land.excludedSpecies(), land.additions(),
            land.levelOverrides(), Map.copyOf(encounterPools)
        );
    }

    private static RoutePokemonPool routePokemonPool(
        JsonObject value, boolean legacyLandPool
    ) {
        Set<String> excluded = new java.util.LinkedHashSet<>();
        for (JsonElement element : value.has("excluded_species")
            ? value.getAsJsonArray("excluded_species") : List.<JsonElement>of()) {
            excluded.add(element.getAsString());
        }
        List<RoutePokemonAddition> additions = new ArrayList<>();
        for (JsonElement element : value.has("additions")
            ? value.getAsJsonArray("additions") : List.<JsonElement>of()) {
            JsonObject addition = element.getAsJsonObject();
            additions.add(new RoutePokemonAddition(
                required(addition, "species"), addition.get("min_level").getAsInt(),
                addition.get("max_level").getAsInt(),
                addition.has("spawn_as_evolved")
                    && addition.get("spawn_as_evolved").getAsBoolean(),
                addition.has("weight") ? addition.get("weight").getAsInt() : 1
            ));
        }
        Map<String, PokemonLevelOverride> levelOverrides = new java.util.LinkedHashMap<>();
        if (value.has("level_overrides")) {
            for (JsonElement element : value.getAsJsonArray("level_overrides")) {
                JsonObject override = element.getAsJsonObject();
                PokemonLevelOverride parsed = new PokemonLevelOverride(
                    required(override, "species"), override.get("min_level").getAsInt(),
                    override.get("max_level").getAsInt()
                );
                levelOverrides.put(parsed.species(), parsed);
            }
        }
        return new RoutePokemonPool(
            !value.has("inherit_biome") || value.get("inherit_biome").getAsBoolean(),
            Set.copyOf(excluded), List.copyOf(additions), Map.copyOf(levelOverrides),
            legacyLandPool || !value.has("enabled") || value.get("enabled").getAsBoolean(),
            value.has("trigger_chance")
                ? value.get("trigger_chance").getAsDouble() : 1.0D
        );
    }

    static List<PlacedTile> tiles(
        JsonObject root, Map<String, BoundaryProfile> profiles
    ) {
        if (!root.has("tiles")) return List.of();
        List<PlacedTile> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("tiles")) {
            JsonObject value = element.getAsJsonObject();
            String boundary = required(value, "boundary_profile");
            requireBoundary(profiles, boundary);
            result.add(new PlacedTile(
                coordinate(value), required(value, "biome"), boundary,
                terrainProfile(value), optional(value, "access_requirement")
            ));
        }
        return List.copyOf(result);
    }

    static List<CaveEntrancePlan> caveEntrances(JsonObject root) {
        if (!root.has("cave_entrances")) return List.of();
        List<CaveEntrancePlan> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("cave_entrances")) {
            JsonObject value = element.getAsJsonObject();
            JsonObject pokemonCenter = value.has("pokemon_center")
                ? value.getAsJsonObject("pokemon_center") : null;
            boolean pokemonCenterEnabled = value.has("pokemon_center_enabled")
                ? value.get("pokemon_center_enabled").getAsBoolean()
                : pokemonCenter != null;
            Map<String, String> structureVariants = new LinkedHashMap<>();
            if (value.has("structure_variants")) {
                for (Map.Entry<String, JsonElement> variant
                    : value.getAsJsonObject("structure_variants").entrySet()) {
                    structureVariants.put(variant.getKey(), variant.getValue().getAsString());
                }
            }
            boolean undergroundRoad = value.has("underground_road");
            result.add(new CaveEntrancePlan(
                required(value, "id"), required(value, undergroundRoad ? "underground_road" : "cave"),
                undergroundRoad ? null : required(value, "entrance"),
                required(value, "transition"),
                undergroundRoad ? required(value, "underground_module") : null,
                undergroundRoad ? required(value, "underground_connector") : null,
                coordinate(value.getAsJsonObject("anchor")),
                required(value, "facing"), required(value, "structure"),
                Map.copyOf(structureVariants),
                pokemonCenterEnabled,
                pokemonCenterStructure(pokemonCenter),
                pokemonCenter == null ? new HexCoord(0, 1)
                    : coordinate(pokemonCenter.getAsJsonObject("offset")), null, null,
                NaturalCaveGenerator.Settings.defaults()
            ));
        }
        return List.copyOf(result);
    }

    private static String pokemonCenterStructure(JsonObject pokemonCenter) {
        String configured = pokemonCenter == null
            ? "cobbleventure:facilities/pokemon_center"
            : required(pokemonCenter, "structure");
        return configured.equals("bca:default/one_off/pokecenter")
            || configured.equals("cobbleventure:facility/pokemon_center_small")
            ? "cobbleventure:facilities/pokemon_center"
            : configured;
    }

    static EmptyTerrain emptyTerrain(JsonObject root) {
        String defaultType = "high_forest";
        Map<HexCoord, String> tiles = new LinkedHashMap<>();
        if (root.has("empty_terrain")) {
            JsonObject empty = root.getAsJsonObject("empty_terrain");
            defaultType = empty.has("default_type")
                ? required(empty, "default_type") : defaultType;
            if (empty.has("tiles")) for (JsonElement element : empty.getAsJsonArray("tiles")) {
                JsonObject value = element.getAsJsonObject();
                HexCoord coordinate = coordinate(value);
                if (tiles.putIfAbsent(coordinate, required(value, "type")) != null) {
                    throw new IllegalStateException("Duplicate empty terrain tile: " + coordinate);
                }
            }
        }
        requireEmptyTerrain(defaultType);
        tiles.values().forEach(WorldPlanParser::requireEmptyTerrain);
        return new EmptyTerrain(defaultType, Map.copyOf(tiles));
    }

    static Map<HexCoord, EnvironmentOverride> environmentOverrides(JsonObject root) {
        if (!root.has("environment_overrides")) return Map.of();
        Map<HexCoord, EnvironmentOverride> result = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("environment_overrides")) {
            JsonObject value = element.getAsJsonObject();
            HexCoord coordinate = coordinate(value);
            EnvironmentOverride override = new EnvironmentOverride(
                optional(value, "temperature"), optional(value, "humidity"),
                optional(value, "weather")
            );
            if (override.weather() != null) requireWeather(override.weather());
            if (result.putIfAbsent(coordinate, override) != null) {
                throw new IllegalStateException("Duplicate environment override: " + coordinate);
            }
        }
        return Map.copyOf(result);
    }

    static Map<HexCoord, Integer> levelOverrides(JsonObject root) {
        if (!root.has("level_overrides")) return Map.of();
        Map<HexCoord, Integer> result = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("level_overrides")) {
            JsonObject value = element.getAsJsonObject();
            HexCoord coordinate = coordinate(value);
            int average = value.get("average_level").getAsInt();
            if (average < 1 || average > 100) {
                throw new IllegalStateException(
                    "Average Pokemon level must be between 1 and 100: " + coordinate
                );
            }
            if (result.putIfAbsent(coordinate, average) != null) {
                throw new IllegalStateException("Duplicate Pokemon level override: " + coordinate);
            }
        }
        return Map.copyOf(result);
    }

    static Map<String, BoundaryProfile> boundaryProfiles(JsonObject root) {
        Map<String, BoundaryProfile> profiles = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("profiles")) {
            JsonObject value = element.getAsJsonObject();
            String id = required(value, "id");
            List<String> surfaceBlocks = new ArrayList<>();
            for (JsonElement block : value.getAsJsonArray("surface_blocks")) {
                surfaceBlocks.add(block.getAsString());
            }
            TreeProfile tree = null;
            if (value.has("tree")) {
                JsonObject treeJson = value.getAsJsonObject("tree");
                tree = new TreeProfile(
                    required(treeJson, "log"), required(treeJson, "leaves"),
                    treeJson.get("spacing").getAsInt(),
                    treeJson.get("min_height").getAsInt(),
                    treeJson.get("max_height").getAsInt()
                );
            }
            BoundaryProfile profile = new BoundaryProfile(
                id, required(value, "type"), value.get("width").getAsInt(),
                value.get("height").getAsInt(), value.get("foundation_depth").getAsInt(),
                required(value, "collision"), required(value, "core_block"),
                List.copyOf(surfaceBlocks), tree
            );
            if (profiles.putIfAbsent(id, profile) != null) {
                throw new IllegalStateException("Duplicate boundary profile: " + id);
            }
        }
        return Map.copyOf(profiles);
    }

    private static List<HexCoord> coordinates(JsonObject object, String key) {
        if (!object.has(key)) return List.of();
        List<HexCoord> result = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            result.add(coordinate(element.getAsJsonObject()));
        }
        return List.copyOf(result);
    }

    private static HexCoord coordinate(JsonObject value) {
        return new HexCoord(value.get("q").getAsInt(), value.get("r").getAsInt());
    }

    private static CobbleventureBootstrap.BlockPoint blockPoint(JsonObject value) {
        return new CobbleventureBootstrap.BlockPoint(
            value.get("x").getAsInt(), value.get("y").getAsInt(),
            value.get("z").getAsInt()
        );
    }

    private static TerrainProfile terrainProfile(JsonObject value) {
        JsonObject terrain = value.getAsJsonObject("terrain_profile");
        return new TerrainProfile(
            terrain.get("base_height_offset").getAsInt(),
            terrain.get("height_variation").getAsInt(),
            terrain.get("noise_scale_blocks").getAsDouble(),
            terrain.has("connection_height")
                ? terrain.get("connection_height").getAsInt() : 0
        );
    }

    private static String required(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing or invalid string field: " + key);
        }
        return value.get(key).getAsString();
    }

    private static String optional(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive()
            ? value.get(key).getAsString() : null;
    }

    private static void requireBoundary(Map<String, BoundaryProfile> profiles, String id) {
        if (!profiles.containsKey(id)) {
            throw new IllegalStateException("Missing boundary profile: " + id);
        }
    }

    private static void requireEmptyTerrain(String type) {
        if (!Set.of(
            "high_forest", "dense_forest", "ocean", "deep_ocean", "desert", "stone_mountain",
            "red_rock_mountain", "snow_mountain"
        )
            .contains(type)) {
            throw new IllegalStateException("Unsupported empty terrain type: " + type);
        }
    }

    private static void requireWeather(String weather) {
        if (!Set.of("clear", "rain", "thunder", "snow", "fog").contains(weather)) {
            throw new IllegalStateException("Unsupported local weather: " + weather);
        }
    }

    record EmptyTerrain(String defaultType, Map<HexCoord, String> tiles) {}
}
