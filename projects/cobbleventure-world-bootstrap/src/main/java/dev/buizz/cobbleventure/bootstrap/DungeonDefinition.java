package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Immutable first-stage contract for data-driven dungeon definitions. */
record DungeonDefinition(
    String id,
    String displayName,
    String description,
    String preset,
    EntryUi entryUi,
    Difficulty difficulty,
    Eligibility eligibility,
    BattleRules battleRules,
    Terrain terrain,
    List<Encounter> encounters,
    RandomEncounters randomEncounters,
    Support support,
    Loot loot,
    Rewards rewards,
    Lifecycle lifecycle,
    Completion completion,
    List<Entrance> entrances
) {
    static Map<String, DungeonDefinition> loadAll(ResourceManager resources) {
        Map<String, DungeonDefinition> definitions = new LinkedHashMap<>();
        Map<String, String> entranceOwners = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files = resources.listResources(
            "dungeons", location -> location.getPath().endsWith(".json")
        );
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            DungeonDefinition definition;
            try (Reader reader = file.getValue().openAsReader()) {
                definition = parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException(
                    "Invalid dungeon definition: " + file.getKey(), error
                );
            }
            if (definitions.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate dungeon ID: " + definition.id());
            }
            for (Entrance entrance : definition.entrances()) {
                String previous = entranceOwners.putIfAbsent(
                    entrance.entranceId(), definition.id()
                );
                if (previous != null) {
                    throw new IllegalStateException(
                        "Duplicate dungeon entrance ID: " + entrance.entranceId()
                            + " (" + previous + " / " + definition.id() + ")"
                    );
                }
            }
        }
        return Map.copyOf(definitions);
    }

    static DungeonDefinition parse(JsonObject root) {
        int schemaVersion = requiredInt(root, "schema_version");
        if (schemaVersion != 1) {
            throw new IllegalStateException(
                "Unsupported dungeon schema version: " + schemaVersion
            );
        }
        String id = resourceId(root, "dungeon_id");
        JsonObject displayName = requiredObject(root, "display_name");
        JsonObject entryUi = requiredObject(root, "entry_ui");
        JsonObject difficulty = requiredObject(root, "difficulty");
        JsonObject eligibility = requiredObject(root, "eligibility");
        JsonObject battleRules = requiredObject(root, "battle");
        JsonObject terrain = requiredObject(root, "terrain");
        List<Entrance> entrances = new ArrayList<>();
        JsonArray configuredEntrances = requiredArray(root, "entrances");
        if (configuredEntrances.isEmpty()) {
            throw new IllegalStateException("Dungeon requires at least one entrance: " + id);
        }
        for (JsonElement element : configuredEntrances) {
            JsonObject entrance = element.getAsJsonObject();
            entrances.add(new Entrance(
                resourceId(entrance, "entrance_id"),
                requiredString(entrance, "destination_entry"),
                enumValue(entrance, "activation", List.of(
                    "interact", "cross", "portal", "proximity"
                )),
                enumValue(entrance, "visibility", List.of(
                    "always", "discovered", "conditioned", "hidden"
                )),
                enumValue(entrance, "return_policy", List.of(
                    "source_position", "source_safe_anchor", "configured_exit"
                ))
            ));
        }
        int recommendedMin = requiredInt(difficulty, "recommended_min");
        int recommendedMax = requiredInt(difficulty, "recommended_max");
        int internalMin = requiredInt(difficulty, "internal_min");
        int internalMax = requiredInt(difficulty, "internal_max");
        validateRange("recommended level", recommendedMin, recommendedMax);
        validateRange("internal level", internalMin, internalMax);
        int minimumPartySize = requiredInt(eligibility, "minimum_party_size");
        int maximumPartySize = requiredInt(eligibility, "maximum_party_size");
        if (minimumPartySize < 1 || maximumPartySize > 6
            || minimumPartySize > maximumPartySize) {
            throw new IllegalStateException(
                "Invalid dungeon party size range: "
                    + minimumPartySize + ".." + maximumPartySize
            );
        }
        String terrainMode = enumValue(terrain, "mode", List.of(
            "fixed_template", "nbt_pieces", "procedural_cave", "hybrid"
        ));
        String template = terrain.has("template")
            ? resourceId(terrain, "template") : null;
        if (terrainMode.equals("fixed_template") && template == null) {
            throw new IllegalStateException(
                "fixed_template dungeon requires terrain.template: " + id
            );
        }
        BlockPos entryPosition = terrainMode.equals("fixed_template")
            ? blockPosition(terrain, "entry_position") : null;
        BlockPos exitPosition = terrainMode.equals("fixed_template")
            ? blockPosition(terrain, "exit_position") : null;
        List<Encounter> encounters = new ArrayList<>();
        for (JsonElement element : requiredArray(root, "encounters")) {
            JsonObject encounter = element.getAsJsonObject();
            encounters.add(new Encounter(
                requiredString(encounter, "id"),
                resourceId(encounter, "npc"),
                blockPosition(encounter, "position"),
                encounter.has("yaw") ? encounter.get("yaw").getAsFloat() : 0.0F,
                requiredBoolean(encounter, "boss")
            ));
        }
        long bossCount = encounters.stream().filter(Encounter::boss).count();
        if (bossCount != 1L) {
            throw new IllegalStateException(
                "Dungeon requires exactly one boss encounter: " + id
            );
        }
        JsonObject randomEncounters = requiredObject(root, "random_encounters");
        int minimumDistance = requiredInt(randomEncounters, "minimum_distance");
        int maximumDistance = requiredInt(randomEncounters, "maximum_distance");
        if (minimumDistance < 1 || maximumDistance > 128
            || minimumDistance > maximumDistance) {
            throw new IllegalStateException(
                "Invalid dungeon random encounter distance: "
                    + minimumDistance + ".." + maximumDistance
            );
        }
        int maxActive = requiredInt(randomEncounters, "max_active");
        if (maxActive < 1 || maxActive > 16) {
            throw new IllegalStateException(
                "Invalid dungeon random encounter max_active: " + maxActive
            );
        }
        int spawnIntervalTicks = requiredInt(randomEncounters, "spawn_interval_ticks");
        if (spawnIntervalTicks < 20 || spawnIntervalTicks > 12000) {
            throw new IllegalStateException(
                "Invalid dungeon random encounter spawn_interval_ticks: "
                    + spawnIntervalTicks
            );
        }
        JsonObject spawnBounds = requiredObject(randomEncounters, "spawn_bounds");
        BlockPos minimumPosition = blockPosition(spawnBounds, "min");
        BlockPos maximumPosition = blockPosition(spawnBounds, "max");
        if (minimumPosition.getX() < 0 || minimumPosition.getY() < 0
            || minimumPosition.getZ() < 0
            || minimumPosition.getX() > maximumPosition.getX()
            || minimumPosition.getY() > maximumPosition.getY()
            || minimumPosition.getZ() > maximumPosition.getZ()) {
            throw new IllegalStateException(
                "Invalid dungeon random encounter spawn_bounds: " + id
            );
        }
        List<WildSpecies> wildSpecies = new ArrayList<>();
        Set<String> configuredWildSpecies = new HashSet<>();
        for (JsonElement element : requiredArray(randomEncounters, "additions")) {
            JsonObject addition = element.getAsJsonObject();
            String species = resourceId(addition, "species");
            if (!configuredWildSpecies.add(species)) {
                throw new IllegalStateException(
                    "Duplicate dungeon random encounter species: " + id + " -> " + species
                );
            }
            int minimumLevel = requiredInt(addition, "min_level");
            int maximumLevel = requiredInt(addition, "max_level");
            validateRange("wild encounter level", minimumLevel, maximumLevel);
            if (minimumLevel < internalMin || maximumLevel > internalMax) {
                throw new IllegalStateException(
                    "Dungeon random encounter level is outside the internal range: "
                        + id + " -> " + species
                );
            }
            int weight = requiredInt(addition, "weight");
            if (weight < 1 || weight > 1000) {
                throw new IllegalStateException(
                    "Invalid dungeon random encounter weight: " + id + " -> " + species
                );
            }
            wildSpecies.add(new WildSpecies(
                species,
                minimumLevel,
                maximumLevel,
                weight,
                addition.has("spawn_as_evolved")
                    && requiredBoolean(addition, "spawn_as_evolved")
            ));
        }
        boolean randomEncountersEnabled = requiredBoolean(randomEncounters, "enabled");
        if (randomEncountersEnabled && wildSpecies.isEmpty()) {
            throw new IllegalStateException(
                "Enabled dungeon random encounters require additions: " + id
            );
        }
        JsonObject support = requiredObject(root, "support");
        List<HealingStation> healingStations = new ArrayList<>();
        Set<String> healingStationIds = new HashSet<>();
        Set<BlockPos> healingStationPositions = new HashSet<>();
        for (JsonElement element : requiredArray(support, "healing_stations")) {
            JsonObject station = element.getAsJsonObject();
            String stationId = requiredString(station, "id");
            BlockPos position = blockPosition(station, "position");
            if (!healingStationIds.add(stationId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon healing station ID: " + id + " -> " + stationId
                );
            }
            if (!healingStationPositions.add(position)) {
                throw new IllegalStateException(
                    "Duplicate dungeon healing station position: " + id + " -> " + position
                );
            }
            int usesPerRun = requiredInt(station, "uses_per_run");
            if (usesPerRun < 1 || usesPerRun > 64) {
                throw new IllegalStateException(
                    "Invalid dungeon healing station uses_per_run: "
                        + id + " -> " + stationId
                );
            }
            boolean restoreHp = requiredBoolean(station, "restore_hp");
            boolean restoreStatus = requiredBoolean(station, "restore_status");
            boolean restorePp = requiredBoolean(station, "restore_pp");
            if (!restoreHp && !restoreStatus && !restorePp) {
                throw new IllegalStateException(
                    "Dungeon healing station restores nothing: " + id + " -> " + stationId
                );
            }
            healingStations.add(new HealingStation(
                stationId,
                position,
                resourceId(station, "block"),
                usesPerRun,
                restoreHp,
                restoreStatus,
                restorePp
            ));
        }
        JsonObject loot = requiredObject(root, "loot");
        List<LootContainer> lootContainers = new ArrayList<>();
        Set<String> lootContainerIds = new HashSet<>();
        Set<BlockPos> lootContainerPositions = new HashSet<>();
        for (JsonElement element : requiredArray(loot, "containers")) {
            JsonObject container = element.getAsJsonObject();
            String containerId = requiredString(container, "id");
            BlockPos position = blockPosition(container, "position");
            if (!lootContainerIds.add(containerId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon loot container ID: " + id + " -> " + containerId
                );
            }
            if (!lootContainerPositions.add(position)) {
                throw new IllegalStateException(
                    "Duplicate dungeon loot container position: " + id + " -> " + position
                );
            }
            lootContainers.add(new LootContainer(
                containerId,
                position,
                enumValue(container, "block", List.of("chest", "barrel")),
                enumValue(container, "facing", List.of("north", "south", "west", "east"))
            ));
        }
        if (lootContainers.isEmpty()) {
            throw new IllegalStateException(
                "Dungeon requires at least one loot container: " + id
            );
        }
        Set<BlockPos> reservedPositions = new HashSet<>();
        if (entryPosition != null) reservedPositions.add(entryPosition);
        if (exitPosition != null) reservedPositions.add(exitPosition);
        encounters.forEach(encounter -> reservedPositions.add(encounter.position()));
        for (HealingStation station : healingStations) {
            if (!reservedPositions.add(station.position())) {
                throw new IllegalStateException(
                    "Dungeon healing station overlaps a reserved position: "
                        + id + " -> " + station.id()
                );
            }
        }
        for (LootContainer container : lootContainers) {
            if (reservedPositions.contains(container.position())) {
                throw new IllegalStateException(
                    "Dungeon loot container overlaps a reserved position: "
                        + id + " -> " + container.id()
                );
            }
        }
        JsonObject rewards = requiredObject(root, "rewards");
        List<String> firstClearFieldMoves = new ArrayList<>();
        for (JsonElement element : requiredArray(rewards, "first_clear_field_moves")) {
            String move = element.getAsString();
            if (move.isBlank()) {
                throw new IllegalStateException(
                    "Dungeon first-clear field move is empty: " + id
                );
            }
            firstClearFieldMoves.add(move);
        }
        String repeatTable = rewards.has("repeat_table")
            ? resourceId(rewards, "repeat_table") : null;
        JsonObject lifecycle = requiredObject(root, "lifecycle");
        JsonObject completion = requiredObject(root, "completion");
        boolean repeatable = requiredBoolean(completion, "repeatable");
        if (repeatable && repeatTable == null) {
            throw new IllegalStateException(
                "Repeatable dungeon requires rewards.repeat_table: " + id
            );
        }
        return new DungeonDefinition(
            id,
            localized(displayName, "ko_kr", "en_us"),
            localized(requiredObject(root, "description"), "ko_kr", "en_us"),
            resourceId(root, "preset"),
            new EntryUi(
                enumValue(entryUi, "info_mode", List.of("exact", "summary", "mystery")),
                requiredBoolean(entryUi, "confirm_required")
            ),
            new Difficulty(recommendedMin, recommendedMax, internalMin, internalMax),
            new Eligibility(
                minimumPartySize,
                maximumPartySize,
                requiredBoolean(eligibility, "require_usable_pokemon"),
                enumValue(eligibility, "level_measure", List.of("average", "highest")),
                enumValue(eligibility, "recommended_level_policy", List.of(
                    "ignore", "warn", "enforce"
                ))
            ),
            new BattleRules(
                requiredBoolean(battleRules, "allow_flee"),
                requiredBoolean(battleRules, "allow_capture"),
                requiredBoolean(battleRules, "allow_items"),
                requiredBoolean(battleRules, "allow_escape_actions")
            ),
            new Terrain(terrainMode, template, entryPosition, exitPosition),
            List.copyOf(encounters),
            new RandomEncounters(
                randomEncountersEnabled,
                minimumDistance,
                maximumDistance,
                maxActive,
                spawnIntervalTicks,
                minimumPosition,
                maximumPosition,
                List.copyOf(wildSpecies)
            ),
            new Support(List.copyOf(healingStations)),
            new Loot(resourceId(loot, "loot_table"), List.copyOf(lootContainers)),
            new Rewards(
                resourceId(rewards, "first_clear_table"),
                repeatTable,
                List.copyOf(firstClearFieldMoves)
            ),
            new Lifecycle(
                enumValue(lifecycle, "on_wipe", List.of("reset_run")),
                enumValue(lifecycle, "wipe_return", List.of(
                    "source_entrance", "pokemon_center"
                )),
                requiredBoolean(lifecycle, "heal_on_wipe")
            ),
            new Completion(
                resourceId(completion, "victory_flag"),
                repeatable
            ),
            List.copyOf(entrances)
        );
    }

    Entrance entrance(String entranceId) {
        return entrances.stream()
            .filter(entrance -> entrance.entranceId().equals(entranceId))
            .findFirst().orElse(null);
    }

    private static void validateRange(String name, int minimum, int maximum) {
        if (minimum < 1 || maximum > 100 || minimum > maximum) {
            throw new IllegalStateException(
                "Invalid dungeon " + name + " range: " + minimum + ".." + maximum
            );
        }
    }

    private static String localized(JsonObject value, String primary, String fallback) {
        String result = value.has(primary) ? value.get(primary).getAsString()
            : value.has(fallback) ? value.get(fallback).getAsString() : null;
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Dungeon localized text is missing");
        }
        return result;
    }

    private static String enumValue(JsonObject value, String key, List<String> allowed) {
        String result = requiredString(value, key);
        if (!allowed.contains(result)) {
            throw new IllegalStateException("Invalid dungeon " + key + ": " + result);
        }
        return result;
    }

    private static String resourceId(JsonObject value, String key) {
        String result = requiredString(value, key);
        if (ResourceLocation.tryParse(result) == null) {
            throw new IllegalStateException("Invalid resource ID for " + key + ": " + result);
        }
        return result;
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Dungeon string is missing: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalStateException("Dungeon string is empty: " + key);
        }
        return result;
    }

    private static int requiredInt(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Dungeon integer is missing: " + key);
        }
        return value.get(key).getAsInt();
    }

    private static boolean requiredBoolean(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IllegalStateException("Dungeon boolean is missing: " + key);
        }
        return value.get(key).getAsBoolean();
    }

    private static JsonObject requiredObject(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) {
            throw new IllegalStateException("Dungeon object is missing: " + key);
        }
        return value.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalStateException("Dungeon array is missing: " + key);
        }
        return value.getAsJsonArray(key);
    }

    private static BlockPos blockPosition(JsonObject value, String key) {
        JsonArray position = requiredArray(value, key);
        if (position.size() != 3) {
            throw new IllegalStateException("Dungeon block position requires three values: " + key);
        }
        return new BlockPos(
            position.get(0).getAsInt(),
            position.get(1).getAsInt(),
            position.get(2).getAsInt()
        );
    }

    record EntryUi(String infoMode, boolean confirmRequired) {}
    record Difficulty(int recommendedMin, int recommendedMax, int internalMin, int internalMax) {}
    record Eligibility(
        int minimumPartySize,
        int maximumPartySize,
        boolean requireUsablePokemon,
        String levelMeasure,
        String recommendedLevelPolicy
    ) {}
    record BattleRules(
        boolean allowFlee,
        boolean allowCapture,
        boolean allowItems,
        boolean allowEscapeActions
    ) {}
    record Terrain(
        String mode,
        String template,
        BlockPos entryPosition,
        BlockPos exitPosition
    ) {}
    record Encounter(String id, String npc, BlockPos position, float yaw, boolean boss) {}
    record RandomEncounters(
        boolean enabled,
        int minimumDistance,
        int maximumDistance,
        int maxActive,
        int spawnIntervalTicks,
        BlockPos minimumPosition,
        BlockPos maximumPosition,
        List<WildSpecies> additions
    ) {}
    record WildSpecies(
        String species,
        int minLevel,
        int maxLevel,
        int weight,
        boolean spawnAsEvolved
    ) {}
    record Support(List<HealingStation> healingStations) {}
    record HealingStation(
        String id,
        BlockPos position,
        String block,
        int usesPerRun,
        boolean restoreHp,
        boolean restoreStatus,
        boolean restorePp
    ) {}
    record Loot(String lootTable, List<LootContainer> containers) {}
    record LootContainer(String id, BlockPos position, String block, String facing) {}
    record Rewards(
        String firstClearTable,
        String repeatTable,
        List<String> firstClearFieldMoves
    ) {}
    record Lifecycle(String onWipe, String wipeReturn, boolean healOnWipe) {}
    record Completion(String victoryFlag, boolean repeatable) {}
    record Entrance(
        String entranceId,
        String destinationEntry,
        String activation,
        String visibility,
        String returnPolicy
    ) {}
}
