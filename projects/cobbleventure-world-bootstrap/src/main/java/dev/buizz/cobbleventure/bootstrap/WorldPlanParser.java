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
            String boundary = value.has("boundary_profile")
                ? required(value, "boundary_profile")
                : "cobbleventure:boundary/dense_tree_line";
            requireBoundary(profiles, boundary);
            result.add(new HexConnection(
                required(value, "id"), optional(value, "from"), optional(value, "to"),
                value.has("route_biome") ? required(value, "route_biome") : "minecraft:plains",
                value.has("width_cells") ? value.get("width_cells").getAsInt() : 1,
                value.has("pathfinding") ? required(value, "pathfinding") : "explicit",
                value.has("detour_cells") ? value.get("detour_cells").getAsInt() : 0,
                value.get("corridor_width_blocks").getAsDouble(),
                value.has("edge_noise") ? value.get("edge_noise").getAsDouble() : 0.0D,
                boundary,
                value.has("terrain_profile")
                    ? terrainProfile(value) : new TerrainProfile(0, 0, 96.0D, 0),
                required(value, "surface_style"), optional(value, "access_requirement"),
                coordinates(value, "cells")
            ));
        }
        return List.copyOf(result);
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
            JsonObject pokemonCenter = value.getAsJsonObject("pokemon_center");
            result.add(new CaveEntrancePlan(
                required(value, "id"), required(value, "cave"),
                required(value, "entrance"), coordinate(value.getAsJsonObject("anchor")),
                required(value, "facing"), required(value, "structure"),
                required(pokemonCenter, "structure"),
                coordinate(pokemonCenter.getAsJsonObject("offset")), null, null,
                NaturalCaveGenerator.Settings.defaults()
            ));
        }
        return List.copyOf(result);
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
        if (!Set.of("high_forest", "ocean", "desert", "stone_mountain", "snow_mountain")
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
