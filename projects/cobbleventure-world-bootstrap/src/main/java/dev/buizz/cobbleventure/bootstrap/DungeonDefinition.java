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
    Multiplayer multiplayer,
    Match match,
    BattleRules battleRules,
    Terrain terrain,
    List<Encounter> encounters,
    RandomEncounters randomEncounters,
    Support support,
    List<Gate> gates,
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
        JsonObject multiplayer = requiredObject(root, "multiplayer");
        JsonObject match = requiredObject(root, "match");
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
        String multiplayerMode = enumValue(multiplayer, "mode", List.of(
            "solo", "cooperative", "independent"
        ));
        int minimumPlayers = requiredInt(multiplayer, "min_size");
        int maximumPlayers = requiredInt(multiplayer, "max_size");
        if (minimumPlayers < 1 || maximumPlayers > 4
            || minimumPlayers > maximumPlayers) {
            throw new IllegalStateException(
                "Invalid dungeon multiplayer size range: "
                    + minimumPlayers + ".." + maximumPlayers
            );
        }
        int requiredPlayers = requiredInt(match, "required_players");
        if (requiredPlayers < minimumPlayers || requiredPlayers > maximumPlayers) {
            throw new IllegalStateException(
                "Dungeon match size is outside the multiplayer range: " + id
            );
        }
        if (multiplayerMode.equals("solo") && requiredPlayers != 1) {
            throw new IllegalStateException(
                "Solo dungeon match requires exactly one player: " + id
            );
        }
        Tether tether = null;
        String battleJoin = multiplayer.has("battle_join")
            ? enumValue(multiplayer, "battle_join", List.of(
                "summon_all", "require_nearby", "initiator_only"
            ))
            : "initiator_only";
        if (multiplayerMode.equals("cooperative")) {
            JsonObject configuredTether = requiredObject(multiplayer, "tether");
            int warningDistance = requiredInt(configuredTether, "warn_distance");
            int maximumDistance = requiredInt(configuredTether, "max_distance");
            if (warningDistance < 1 || maximumDistance > 256
                || warningDistance >= maximumDistance) {
                throw new IllegalStateException(
                    "Invalid dungeon cooperative tether range: "
                        + warningDistance + ".." + maximumDistance
                );
            }
            tether = new Tether(
                warningDistance,
                maximumDistance,
                enumValue(configuredTether, "on_exceed", List.of(
                    "return_to_partner"
                ))
            );
        }
        int timeoutSeconds = requiredInt(match, "timeout_seconds");
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalStateException(
                "Invalid dungeon match timeout_seconds: " + timeoutSeconds
            );
        }
        int stayRadius = requiredInt(match, "stay_radius");
        if (stayRadius < 1 || stayRadius > 64) {
            throw new IllegalStateException(
                "Invalid dungeon match stay_radius: " + stayRadius
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
        Set<String> encounterIds = new HashSet<>();
        for (JsonElement element : requiredArray(root, "encounters")) {
            JsonObject encounter = element.getAsJsonObject();
            String encounterId = requiredString(encounter, "id");
            if (!encounterIds.add(encounterId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon encounter ID: " + id + " -> " + encounterId
                );
            }
            List<String> npcs = new ArrayList<>();
            for (JsonElement npc : requiredArray(encounter, "npcs")) {
                String npcId = npc.getAsString();
                if (ResourceLocation.tryParse(npcId) == null) {
                    throw new IllegalStateException(
                        "Dungeon encounter NPC must be a resource ID: " + npcId
                    );
                }
                npcs.add(npcId);
            }
            if (npcs.size() != 2) {
                throw new IllegalStateException(
                    "Dungeon cooperative encounter requires exactly two NPCs: "
                        + id + " -> " + requiredString(encounter, "id")
                );
            }
            List<String> opponents = new ArrayList<>();
            for (JsonElement opponent : requiredArray(encounter, "opponents")) {
                String battleId = opponent.getAsString();
                if (ResourceLocation.tryParse(battleId) == null) {
                    throw new IllegalStateException(
                        "Dungeon encounter opponent must be a resource ID: " + battleId
                    );
                }
                opponents.add(battleId);
            }
            if (opponents.size() != 2) {
                throw new IllegalStateException(
                    "Dungeon cooperative encounter requires exactly two opponents: "
                        + id + " -> " + requiredString(encounter, "id")
                );
            }
            List<String> requirements = new ArrayList<>();
            for (JsonElement requirement : requiredArray(encounter, "requires")) {
                String requiredEncounter = requirement.getAsString();
                if (requiredEncounter.isBlank()
                    || !requirements.add(requiredEncounter)) {
                    throw new IllegalStateException(
                        "Invalid dungeon encounter requirement: " + id + " -> "
                            + encounterId + " -> " + requiredEncounter
                    );
                }
            }
            encounters.add(new Encounter(
                encounterId,
                List.copyOf(npcs),
                List.copyOf(opponents),
                List.copyOf(requirements),
                blockPosition(encounter, "position"),
                encounter.has("yaw") ? encounter.get("yaw").getAsFloat() : 0.0F,
                requiredBoolean(encounter, "boss")
            ));
        }
        Map<String, List<String>> encounterRequirements = new LinkedHashMap<>();
        encounters.forEach(encounter ->
            encounterRequirements.put(encounter.id(), encounter.requires())
        );
        DungeonEncounterRequirements.validate(id, encounterRequirements);
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
        List<Gate> gates = new ArrayList<>();
        Set<String> gateIds = new HashSet<>();
        for (JsonElement element : requiredArray(root, "gates")) {
            JsonObject gate = element.getAsJsonObject();
            String gateId = requiredString(gate, "id");
            if (!gateIds.add(gateId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon gate ID: " + id + " -> " + gateId
                );
            }
            BlockPos minimum = blockPosition(gate, "min");
            BlockPos maximum = blockPosition(gate, "max");
            if (minimum.getX() < 0 || minimum.getY() < 0 || minimum.getZ() < 0
                || minimum.getX() > maximum.getX()
                || minimum.getY() > maximum.getY()
                || minimum.getZ() > maximum.getZ()
                || (long)(maximum.getX() - minimum.getX() + 1)
                    * (maximum.getY() - minimum.getY() + 1)
                    * (maximum.getZ() - minimum.getZ() + 1) > 256L) {
                throw new IllegalStateException(
                    "Invalid dungeon gate bounds: " + id + " -> " + gateId
                );
            }
            List<String> requirements = new ArrayList<>();
            for (JsonElement requirement : requiredArray(gate, "requires")) {
                String requiredEncounter = requirement.getAsString();
                if (!encounterIds.contains(requiredEncounter)
                    || !requirements.add(requiredEncounter)) {
                    throw new IllegalStateException(
                        "Invalid dungeon gate requirement: " + id + " -> "
                            + gateId + " -> " + requiredEncounter
                    );
                }
            }
            if (requirements.isEmpty()) {
                throw new IllegalStateException(
                    "Dungeon gate requires at least one encounter: " + id
                        + " -> " + gateId
                );
            }
            gates.add(new Gate(
                gateId, minimum, maximum, resourceId(gate, "block"),
                List.copyOf(requirements)
            ));
        }
        JsonObject loot = requiredObject(root, "loot");
        String lootOwnership = enumValue(loot, "ownership", List.of(
            "per_player", "run_shared", "first_claim"
        ));
        String lootOnFailure = enumValue(loot, "on_failure", List.of(
            "keep_collected", "remove_run_loot", "grant_on_clear_only"
        ));
        if (lootOwnership.equals("run_shared")
            && !lootOnFailure.equals("keep_collected")) {
            throw new IllegalStateException(
                "run_shared dungeon loot requires keep_collected: " + id
            );
        }
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
                enumValue(container, "facing", List.of("north", "south", "west", "east")),
                requiredBoolean(container, "requires_completion")
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
        int reconnectGraceSeconds = requiredInt(lifecycle, "reconnect_grace_seconds");
        if (reconnectGraceSeconds < 0 || reconnectGraceSeconds > 3600) {
            throw new IllegalStateException(
                "Invalid dungeon reconnect_grace_seconds: " + reconnectGraceSeconds
            );
        }
        JsonObject completion = requiredObject(root, "completion");
        boolean repeatable = requiredBoolean(completion, "repeatable");
        String returnTrigger = enumValue(completion, "return_trigger", List.of(
            "automatic", "clear_exit"
        ));
        BlockPos clearExitPosition = completion.has("clear_exit_position")
            ? blockPosition(completion, "clear_exit_position") : null;
        String clearExitBlock = completion.has("clear_exit_block")
            ? resourceId(completion, "clear_exit_block") : null;
        if (returnTrigger.equals("clear_exit")
            && (clearExitPosition == null || clearExitBlock == null)) {
            throw new IllegalStateException(
                "clear_exit completion requires a position and block: " + id
            );
        }
        if (clearExitPosition != null
            && (clearExitPosition.getX() < 0 || clearExitPosition.getY() < 0
                || clearExitPosition.getZ() < 0)) {
            throw new IllegalStateException(
                "Dungeon clear exit position cannot be negative: " + id
            );
        }
        if (clearExitPosition != null
            && (reservedPositions.contains(clearExitPosition)
                || lootContainerPositions.contains(clearExitPosition))) {
            throw new IllegalStateException(
                "Dungeon clear exit overlaps a reserved position: " + id
            );
        }
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
            new Multiplayer(
                multiplayerMode, minimumPlayers, maximumPlayers, battleJoin, tether
            ),
            new Match(
                requiredPlayers,
                enumValue(match, "scope", List.of("same_entrance")),
                timeoutSeconds,
                enumValue(match, "on_timeout", List.of("cancel", "keep_waiting")),
                stayRadius
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
            List.copyOf(gates),
            new Loot(
                resourceId(loot, "loot_table"), lootOwnership, lootOnFailure,
                List.copyOf(lootContainers)
            ),
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
                requiredBoolean(lifecycle, "heal_on_wipe"),
                reconnectGraceSeconds
            ),
            new Completion(
                resourceId(completion, "victory_flag"),
                repeatable,
                returnTrigger,
                clearExitPosition,
                clearExitBlock
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
    record Multiplayer(
        String mode,
        int minSize,
        int maxSize,
        String battleJoin,
        Tether tether
    ) {}
    record Tether(int warnDistance, int maxDistance, String onExceed) {}
    record Match(
        int requiredPlayers,
        String scope,
        int timeoutSeconds,
        String onTimeout,
        int stayRadius
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
    record Encounter(
        String id,
        List<String> npcs,
        List<String> opponents,
        List<String> requires,
        BlockPos position,
        float yaw,
        boolean boss
    ) {}
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
    record Gate(
        String id,
        BlockPos minimum,
        BlockPos maximum,
        String block,
        List<String> requires
    ) {}
    record HealingStation(
        String id,
        BlockPos position,
        String block,
        int usesPerRun,
        boolean restoreHp,
        boolean restoreStatus,
        boolean restorePp
    ) {}
    record Loot(
        String lootTable,
        String ownership,
        String onFailure,
        List<LootContainer> containers
    ) {}
    record LootContainer(
        String id,
        BlockPos position,
        String block,
        String facing,
        boolean requiresCompletion
    ) {}
    record Rewards(
        String firstClearTable,
        String repeatTable,
        List<String> firstClearFieldMoves
    ) {}
    record Lifecycle(
        String onWipe,
        String wipeReturn,
        boolean healOnWipe,
        int reconnectGraceSeconds
    ) {}
    record Completion(
        String victoryFlag,
        boolean repeatable,
        String returnTrigger,
        BlockPos clearExitPosition,
        String clearExitBlock
    ) {}
    record Entrance(
        String entranceId,
        String destinationEntry,
        String activation,
        String visibility,
        String returnPolicy
    ) {}
}
