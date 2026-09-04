package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;
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
    Plan plan,
    Terrain terrain,
    Layout layout,
    Topology topology,
    Progression progression,
    SpatialLayout spatialLayout,
    Vertical vertical,
    NpcPlacement npcPlacement,
    List<Encounter> encounters,
    RandomEncounters randomEncounters,
    Support support,
    List<Objective> objectives,
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
        List<PlayerConditions.Condition> entryConditions = new ArrayList<>();
        if (eligibility.has("conditions")) {
            for (JsonElement element : eligibility.getAsJsonArray("conditions")) {
                entryConditions.add(PlayerConditions.parse(element.getAsJsonObject()));
            }
        }
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
        BlockPos entryPosition = terrain.has("entry_position")
            ? blockPosition(terrain, "entry_position") : null;
        BlockPos exitPosition = terrain.has("exit_position")
            ? blockPosition(terrain, "exit_position") : null;
        String piecePool = terrain.has("piece_pool")
            ? resourceId(terrain, "piece_pool") : null;
        String caveGenerator = terrain.has("cave_generator")
            ? enumValue(terrain, "cave_generator", List.of("minecraft_worldgen")) : null;
        BlockPos terrainBounds = terrain.has("bounds")
            ? positiveBlockPosition(terrain, "bounds") : null;
        NaturalCaveGenerator.Settings caveSettings =
            (terrainMode.equals("procedural_cave") || terrainMode.equals("hybrid"))
                ? NaturalCaveGenerator.settings(terrain) : null;
        if (terrainMode.equals("nbt_pieces")
            && (piecePool == null || terrainBounds == null)) {
            throw new IllegalStateException(
                "nbt_pieces dungeon requires terrain.piece_pool and terrain.bounds: " + id
            );
        }
        if ((terrainMode.equals("procedural_cave") || terrainMode.equals("hybrid"))
            && (caveGenerator == null || terrainBounds == null)) {
            throw new IllegalStateException(
                terrainMode + " dungeon requires terrain.cave_generator and terrain.bounds: "
                    + id
            );
        }
        if (terrainMode.equals("hybrid") && piecePool == null) {
            throw new IllegalStateException(
                "hybrid dungeon requires terrain.piece_pool: " + id
            );
        }

        Plan plan = defaultPlan(terrainMode);
        if (root.has("plan")) {
            JsonObject configuredPlan = requiredObject(root, "plan");
            List<String> planIds = new ArrayList<>();
            if (configuredPlan.has("plan_ids")) {
                for (JsonElement element : requiredArray(configuredPlan, "plan_ids")) {
                    String planId = element.getAsString();
                    if (ResourceLocation.tryParse(planId) == null) {
                        throw new IllegalStateException("Invalid dungeon plan ID: " + planId);
                    }
                    planIds.add(planId);
                }
            }
            int generationTimeoutMs = configuredPlan.has("generation_timeout_ms")
                ? requiredInt(configuredPlan, "generation_timeout_ms") : 1000;
            int maxAttempts = configuredPlan.has("max_attempts")
                ? requiredInt(configuredPlan, "max_attempts") : 32;
            if (generationTimeoutMs < 1 || generationTimeoutMs > 60_000
                || maxAttempts < 1 || maxAttempts > 1000) {
                throw new IllegalStateException("Invalid dungeon plan limits: " + id);
            }
            plan = new Plan(
                enumValue(configuredPlan, "mode", List.of(
                    "authored", "runtime", "authored_pool"
                )),
                List.copyOf(planIds),
                enumValue(configuredPlan, "seed_policy", List.of(
                    "fixed", "random_per_run", "daily", "weekly", "match", "player"
                )),
                enumValue(configuredPlan, "fallback", List.of(
                    "reject_entry", "use_last_valid", "use_fallback_plan"
                )),
                generationTimeoutMs,
                maxAttempts
            );
            if (plan.mode().equals("authored") && plan.planIds().size() != 1) {
                throw new IllegalStateException(
                    "authored dungeon requires exactly one plan.plan_ids entry: " + id
                );
            }
            if (plan.mode().equals("authored_pool") && plan.planIds().isEmpty()) {
                throw new IllegalStateException(
                    "authored_pool dungeon requires plan.plan_ids: " + id
                );
            }
            if (plan.mode().equals("runtime") && !plan.planIds().isEmpty()) {
                throw new IllegalStateException(
                    "runtime dungeon cannot declare plan.plan_ids: " + id
                );
            }
        }

        Layout layout = null;
        if (root.has("layout")) {
            JsonObject configuredLayout = requiredObject(root, "layout");
            IntRange criticalPath = integerRange(
                configuredLayout, "critical_path_rooms", 3, 256
            );
            IntRange branchCount = integerRange(
                configuredLayout, "branch_count", 0, 128
            );
            IntRange branchDepth = integerRange(
                configuredLayout, "branch_depth", 1, 64
            );
            double loopChance = configuredLayout.has("loop_chance")
                ? configuredLayout.get("loop_chance").getAsDouble() : 0.0D;
            if (loopChance < 0.0D || loopChance > 1.0D) {
                throw new IllegalStateException("Invalid dungeon layout loop_chance: " + id);
            }
            String verticalDirection = configuredLayout.has("vertical_direction")
                ? enumValue(configuredLayout, "vertical_direction", List.of(
                    "flat", "ascending", "descending", "mixed"
                )) : "mixed";
            IntRange floorChanges = configuredLayout.has("floor_changes")
                ? integerRange(configuredLayout, "floor_changes", 0, 256)
                : new IntRange(0, 256);
            layout = new Layout(
                enumValue(configuredLayout, "mode", List.of(
                    "fixed", "critical_path_branches", "maze", "rooms_and_corridors"
                )),
                criticalPath, branchCount, branchDepth, loopChance,
                verticalDirection, floorChanges
            );
        }
        Topology topology;
        if (root.has("topology")) {
            JsonObject configured = requiredObject(root, "topology");
            double loopChance = configured.has("loop_chance")
                ? configured.get("loop_chance").getAsDouble() : 0.0D;
            if (loopChance < 0.0D || loopChance > 1.0D) {
                throw new IllegalStateException(
                    "Invalid dungeon topology loop_chance: " + id
                );
            }
            String topologyMode = enumValue(configured, "mode", List.of(
                    "authored", "corridor_spine", "hub_and_spokes",
                    "room_network", "chamber_maze", "natural_network"
                ));
            ChamberGrid chamberGrid = null;
            if (configured.has("chamber_grid")) {
                JsonArray grid = requiredArray(configured, "chamber_grid");
                if (grid.size() != 2) {
                    throw new IllegalStateException(
                        "Dungeon chamber_grid must contain width and depth: " + id
                    );
                }
                int width = grid.get(0).getAsInt();
                int depth = grid.get(1).getAsInt();
                if (width < 3 || width > 64 || depth < 3 || depth > 64) {
                    throw new IllegalStateException(
                        "Dungeon chamber_grid must be between 3 and 64 cells: " + id
                    );
                }
                chamberGrid = new ChamberGrid(width, depth);
            }
            if (topologyMode.equals("chamber_maze") && chamberGrid == null) {
                throw new IllegalStateException(
                    "chamber_maze requires chamber_grid: " + id
                );
            }
            topology = new Topology(
                topologyMode,
                integerRange(configured, "critical_path_rooms", 3, 256),
                integerRange(configured, "branch_count", 0, 128),
                integerRange(configured, "branch_depth", 1, 64),
                loopChance,
                chamberGrid
            );
        } else if (layout != null) {
            topology = new Topology(
                switch (layout.mode()) {
                    case "fixed" -> "authored";
                    case "rooms_and_corridors" -> "legacy_rooms_and_corridors";
                    case "maze" -> "legacy_maze";
                    default -> "corridor_spine";
                },
                layout.criticalPathRooms(), layout.branchCount(),
                layout.branchDepth(), layout.loopChance()
            );
        } else {
            topology = new Topology(
                terrainMode.equals("procedural_cave") ? "natural_network" : "authored",
                new IntRange(3, 3), new IntRange(0, 0),
                new IntRange(1, 1), 0.0D
            );
        }

        Progression progression;
        if (root.has("progression")) {
            JsonObject configured = requiredObject(root, "progression");
            String pattern = enumValue(configured, "pattern", List.of(
                "linear", "branching", "cyclic", "parallel_gate",
                "key_lock", "shortcut_loop"
            ));
            int requiredTargets = configured.has("required_targets")
                ? requiredInt(configured, "required_targets") : 2;
            if (requiredTargets < 1 || requiredTargets > 16) {
                throw new IllegalStateException(
                    "Dungeon progression required_targets must be between 1 and 16: " + id
                );
            }
            progression = new Progression(pattern, requiredTargets);
        } else {
            String pattern = topology.loopChance() > 0.0D ? "cyclic"
                : topology.branchCount().maximum() > 0 ? "branching" : "linear";
            progression = new Progression(pattern, 2);
        }

        SpatialLayout spatialLayout;
        if (root.has("spatial_layout")) {
            JsonObject configured = requiredObject(root, "spatial_layout");
            spatialLayout = new SpatialLayout(enumValue(
                configured, "algorithm", List.of(
                    "grid_walk", "socket_accretion", "scatter_graph",
                    "bsp_floor", "hub_and_spokes", "authored"
                )
            ));
        } else {
            spatialLayout = new SpatialLayout(switch (topology.mode()) {
                case "authored" -> "authored";
                case "room_network", "chamber_maze" -> "socket_accretion";
                case "hub_and_spokes" -> "hub_and_spokes";
                case "natural_network" -> "scatter_graph";
                default -> "grid_walk";
            });
        }

        Vertical vertical;
        if (root.has("vertical")) {
            JsonObject configured = requiredObject(root, "vertical");
            String mode = enumValue(configured, "mode", List.of(
                "flat", "continuous", "discrete_floors", "authored"
            ));
            String direction = configured.has("direction")
                ? enumValue(configured, "direction", List.of(
                    "ascending", "descending", "mixed"
                )) : "mixed";
            IntRange floorCount = configured.has("floor_count")
                ? integerRange(configured, "floor_count", 1, 257)
                : new IntRange(1, 1);
            int floorHeight = configured.has("floor_height")
                ? requiredInt(configured, "floor_height") : 8;
            IntRange connections = configured.has("connections_per_floor")
                ? integerRange(configured, "connections_per_floor", 1, 16)
                : new IntRange(1, 1);
            if (floorHeight < 4 || floorHeight > 64) {
                throw new IllegalStateException(
                    "Invalid dungeon vertical floor_height: " + id
                );
            }
            if (mode.equals("discrete_floors")
                && (!configured.has("floor_count")
                    || !configured.has("floor_height")
                    || !configured.has("connections_per_floor"))) {
                throw new IllegalStateException(
                    "discrete_floors requires floor_count, floor_height and connections_per_floor: "
                        + id
                );
            }
            vertical = new Vertical(
                mode, direction, floorCount, floorHeight, connections
            );
        } else if (layout != null) {
            vertical = new Vertical(
                layout.verticalDirection().equals("flat") ? "flat" : "continuous",
                layout.verticalDirection().equals("flat")
                    ? "mixed" : layout.verticalDirection(),
                new IntRange(
                    layout.floorChanges().minimum() + 1,
                    layout.floorChanges().maximum() + 1
                ),
                8, new IntRange(1, 1)
            );
        } else {
            vertical = new Vertical(
                "authored", "mixed", new IntRange(1, 1), 8,
                new IntRange(1, 1)
            );
        }

        if (layout == null && root.has("topology")) {
            layout = new Layout(
                switch (topology.mode()) {
                    case "authored" -> "fixed";
                    case "room_network" -> "rooms_and_corridors";
                    case "legacy_maze", "chamber_maze" -> "maze";
                    default -> "critical_path_branches";
                },
                topology.criticalPathRooms(), topology.branchCount(),
                topology.branchDepth(), topology.loopChance(),
                vertical.mode().equals("flat") ? "flat" : vertical.direction(),
                new IntRange(
                    Math.max(0, vertical.floorCount().minimum() - 1),
                    Math.max(0, vertical.floorCount().maximum() - 1)
                )
            );
        }
        if (terrainMode.equals("nbt_pieces") && layout == null) {
            throw new IllegalStateException(
                terrainMode + " dungeon requires topology or layout settings: " + id
            );
        }
        if (terrainMode.equals("nbt_pieces") && plan.mode().equals("runtime")
            && Set.of("authored", "chamber_maze", "natural_network")
                .contains(topology.mode())) {
            throw new IllegalStateException(
                "Dungeon topology requires a dedicated construction planner: "
                    + id + " -> " + topology.mode()
            );
        }
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
            String encounterKind = encounter.has("kind")
                ? enumValue(encounter, "kind", List.of("trainer", "wild_pokemon"))
                : "trainer";
            List<String> npcs = new ArrayList<>();
            for (JsonElement npc : encounter.has("npcs")
                ? encounter.getAsJsonArray("npcs") : List.<JsonElement>of()) {
                String npcId = npc.getAsString();
                if (ResourceLocation.tryParse(npcId) == null) {
                    throw new IllegalStateException(
                        "Dungeon encounter NPC must be a resource ID: " + npcId
                    );
                }
                npcs.add(npcId);
            }
            int maximumActors = multiplayerMode.equals("cooperative") ? 2 : 1;
            List<TrainerActor> trainers = new ArrayList<>();
            Set<String> trainerActorIds = new HashSet<>();
            for (JsonElement trainerElement : encounter.has("trainers")
                ? encounter.getAsJsonArray("trainers") : List.<JsonElement>of()) {
                JsonObject trainer = trainerElement.getAsJsonObject();
                String actorId = requiredString(trainer, "id");
                if (!actorId.matches("[a-z0-9_.-]+") || !trainerActorIds.add(actorId)) {
                    throw new IllegalStateException(
                        "Invalid or duplicate dungeon trainer actor ID: "
                            + id + " -> " + encounterId + " -> " + actorId
                    );
                }
                String trainerClass = resourceId(trainer, "trainer_class");
                if (!trainerClass.contains(":trainer_class/")) {
                    throw new IllegalStateException(
                        "Dungeon trainer actor class must use namespace:trainer_class/path: "
                            + trainerClass
                    );
                }
                String battle = resourceId(trainer, "battle");
                if (!battle.contains(":battle/")) {
                    throw new IllegalStateException(
                        "Dungeon trainer actor battle must use namespace:battle/path: "
                            + battle
                    );
                }
                trainers.add(new TrainerActor(
                    actorId,
                    localized(requiredObject(trainer, "display_name"), "ko_kr", "en_us"),
                    trainerClass,
                    battle
                ));
            }
            if (!trainers.isEmpty() && (!npcs.isEmpty()
                || encounter.has("opponents") || encounter.has("trainer_generation"))) {
                throw new IllegalStateException(
                    "Dungeon-owned trainers cannot mix legacy NPC or opponent fields: "
                        + id + " -> " + encounterId
                );
            }
            int actorCount = trainers.isEmpty() ? npcs.size() : trainers.size();
            if (encounterKind.equals("trainer")
                && (actorCount < 1 || actorCount > maximumActors)) {
                throw new IllegalStateException(
                    "Dungeon " + multiplayerMode + " encounter requires 1.."
                        + maximumActors + " trainer actor(s): " + id + " -> " + encounterId
                );
            }
            List<String> opponents = new ArrayList<>();
            for (JsonElement opponent : encounter.has("opponents")
                ? encounter.getAsJsonArray("opponents") : List.<JsonElement>of()) {
                String battleId = opponent.getAsString();
                if (ResourceLocation.tryParse(battleId) == null) {
                    throw new IllegalStateException(
                        "Dungeon encounter opponent must be a resource ID: " + battleId
                    );
                }
                opponents.add(battleId);
            }
            if (!trainers.isEmpty()) {
                trainers.forEach(trainer -> opponents.add(trainer.battle()));
            }
            GeneratedTrainer generatedTrainer = null;
            if (encounterKind.equals("trainer") && encounter.has("trainer_generation")) {
                if (!opponents.isEmpty()) {
                    throw new IllegalStateException(
                        "Generated dungeon trainer cannot define battle presets: "
                            + id + " -> " + encounterId
                    );
                }
                JsonObject generation = requiredObject(encounter, "trainer_generation");
                List<WeightedSpecies> pokemonPool = new ArrayList<>();
                Set<String> poolSpecies = new HashSet<>();
                for (JsonElement poolElement : requiredArray(generation, "pokemon_pool")) {
                    JsonObject poolEntry = poolElement.getAsJsonObject();
                    int weight = requiredInt(poolEntry, "weight");
                    if (weight < 1 || weight > 1000) {
                        throw new IllegalStateException(
                            "Generated dungeon Pokemon weight must be 1..1000: "
                                + id + " -> " + encounterId
                        );
                    }
                    String species = resourceId(poolEntry, "species");
                    if (!poolSpecies.add(species)) {
                        throw new IllegalStateException(
                            "Generated dungeon Pokemon pool contains duplicate species: "
                                + id + " -> " + encounterId + " -> " + species
                        );
                    }
                    pokemonPool.add(new WeightedSpecies(species, weight));
                }
                if (pokemonPool.isEmpty()) {
                    throw new IllegalStateException(
                        "Generated dungeon trainer Pokemon pool is empty: "
                            + id + " -> " + encounterId
                    );
                }
                IntRange teamSize = integerRange(generation, "team_size", 1, 6);
                if (!requiredBoolean(generation, "allow_duplicates")
                    && teamSize.maximum() > pokemonPool.size()) {
                    throw new IllegalStateException(
                        "Generated dungeon trainer team exceeds its unique Pokemon pool: "
                            + id + " -> " + encounterId
                    );
                }
                generatedTrainer = new GeneratedTrainer(
                    List.copyOf(pokemonPool), teamSize,
                    requiredBoolean(generation, "allow_duplicates"),
                    nonEmptyStrings(generation, "battle_start_lines"),
                    nonEmptyStrings(generation, "battle_end_lines")
                );
            }
            if (encounterKind.equals("trainer") && generatedTrainer == null
                && (opponents.isEmpty() || opponents.size() > maximumActors)) {
                throw new IllegalStateException(
                    "Dungeon " + multiplayerMode + " encounter requires 1.."
                        + maximumActors + " opponent(s): "
                        + id + " -> " + requiredString(encounter, "id")
                );
            }
            WildPokemon wildPokemon = null;
            if (encounterKind.equals("wild_pokemon")) {
                if (!npcs.isEmpty() || !trainers.isEmpty() || !opponents.isEmpty()) {
                    throw new IllegalStateException(
                        "Wild dungeon encounter cannot define NPC opponents: "
                            + id + " -> " + encounterId
                    );
                }
                if (!multiplayerMode.equals("solo")) {
                    throw new IllegalStateException(
                        "Wild dungeon encounter currently requires solo mode: "
                            + id + " -> " + encounterId
                    );
                }
                JsonObject pokemon = requiredObject(encounter, "pokemon");
                int level = requiredInt(pokemon, "level");
                if (level < internalMin || level > internalMax) {
                    throw new IllegalStateException(
                        "Dungeon wild boss level is outside the internal range: "
                            + id + " -> " + encounterId
                    );
                }
                wildPokemon = new WildPokemon(
                    resourceId(pokemon, "species"), level,
                    requiredBoolean(pokemon, "catchable")
                );
            } else if (encounter.has("pokemon")) {
                throw new IllegalStateException(
                    "Trainer dungeon encounter cannot define pokemon: "
                        + id + " -> " + encounterId
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
            List<String> runStateKeys = new ArrayList<>();
            if (encounter.has("run_state_keys")) {
                for (JsonElement stateKey : requiredArray(encounter, "run_state_keys")) {
                    String key = stateKey.getAsString();
                    if (ResourceLocation.tryParse(key) == null || !runStateKeys.add(key)) {
                        throw new IllegalStateException(
                            "Invalid dungeon encounter run state key: " + id + " -> "
                                + encounterId + " -> " + key
                        );
                    }
                }
            }
            EncounterTrigger trigger = null;
            if (encounter.has("trigger")) {
                if (!encounterKind.equals("trainer") || trainers.isEmpty()) {
                    throw new IllegalStateException(
                        "Dungeon-owned trigger requires dungeon-owned trainer actors: "
                            + id + " -> " + encounterId
                    );
                }
                JsonObject triggerValue = requiredObject(encounter, "trigger");
                String triggerType = enumValue(
                    triggerValue, "type", List.of("proximity")
                );
                int leader = requiredInt(triggerValue, "leader");
                if (leader < 0 || leader >= trainers.size()) {
                    throw new IllegalStateException(
                        "Dungeon encounter trigger leader is outside NPC list: "
                            + id + " -> " + encounterId
                    );
                }
                double range = requiredDouble(triggerValue, "range");
                double warningOffset = requiredDouble(
                    triggerValue, "warning_offset"
                );
                if (range <= 0.0D || warningOffset < 0.0D) {
                    throw new IllegalStateException(
                        "Dungeon encounter trigger ranges are invalid: "
                            + id + " -> " + encounterId
                    );
                }
                String warningTrack = requiredString(
                    triggerValue, "warning_track"
                );
                if (!warningTrack.matches("[A-Za-z0-9._-]+")) {
                    throw new IllegalStateException(
                        "Dungeon encounter warning track is invalid: " + warningTrack
                    );
                }
                trigger = new EncounterTrigger(
                    triggerType, leader, range, warningOffset, warningTrack,
                    nonEmptyStrings(triggerValue, "start_lines"),
                    nonEmptyStrings(triggerValue, "win_lines"),
                    nonEmptyStrings(triggerValue, "loss_lines")
                );
            }
            encounters.add(new Encounter(
                encounterId,
                localized(requiredObject(encounter, "display_name"), "ko_kr", "en_us"),
                encounterKind,
                List.copyOf(npcs),
                List.copyOf(trainers),
                List.copyOf(opponents),
                generatedTrainer,
                wildPokemon,
                List.copyOf(requirements),
                List.copyOf(runStateKeys),
                trigger,
                encounter.has("position") ? blockPosition(encounter, "position") : null,
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
            BlockPos position = station.has("position")
                ? blockPosition(station, "position") : null;
            if (!healingStationIds.add(stationId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon healing station ID: " + id + " -> " + stationId
                );
            }
            if (position != null && !healingStationPositions.add(position)) {
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
        List<Checkpoint> checkpoints = new ArrayList<>();
        Set<String> checkpointIds = new HashSet<>();
        for (JsonElement element : support.has("checkpoints")
            ? support.getAsJsonArray("checkpoints") : List.<JsonElement>of()) {
            JsonObject checkpoint = element.getAsJsonObject();
            String checkpointId = requiredString(checkpoint, "id");
            if (!checkpointIds.add(checkpointId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon checkpoint ID: " + id + " -> " + checkpointId
                );
            }
            int activationRadius = checkpoint.has("activation_radius")
                ? requiredInt(checkpoint, "activation_radius") : 2;
            if (activationRadius < 1 || activationRadius > 8) {
                throw new IllegalStateException(
                    "Invalid dungeon checkpoint activation_radius: " + id
                );
            }
            checkpoints.add(new Checkpoint(
                checkpointId, blockPosition(checkpoint, "position"), activationRadius
            ));
        }
        List<Gate> gates = new ArrayList<>();
        List<Objective> objectives = new ArrayList<>();
        Set<String> objectiveIds = new HashSet<>();
        JsonArray objectiveValues = root.has("objectives")
            ? requiredArray(root, "objectives") : new JsonArray();
        for (JsonElement element : objectiveValues) {
            JsonObject objective = element.getAsJsonObject();
            String objectiveId = requiredString(objective, "id");
            if (!objectiveIds.add(objectiveId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon objective ID: " + id + " -> " + objectiveId
                );
            }
            String objectivePlacement = enumValue(
                objective, "placement", List.of("fixed", "marker")
            );
            if (objectivePlacement.equals("marker")
                && !Set.of(
                    "nbt_pieces", "procedural_cave", "hybrid"
                ).contains(terrainMode)) {
                throw new IllegalStateException(
                    "Automatic dungeon objective requires generated terrain: "
                        + id + " -> " + objectiveId
                );
            }
            BlockPos objectivePosition = objective.has("position")
                ? blockPosition(objective, "position") : null;
            if (objectivePlacement.equals("fixed") && objectivePosition == null) {
                throw new IllegalStateException(
                    "Fixed dungeon objective requires a position: " + id
                );
            }
            int activationRadius = requiredInt(objective, "activation_radius");
            if (activationRadius < 1 || activationRadius > 8) {
                throw new IllegalStateException(
                    "Invalid dungeon objective activation radius: " + id
                );
            }
            objectives.add(new Objective(
                objectiveId,
                enumValue(objective, "kind", List.of("switch", "investigate")),
                objectivePlacement, objectivePosition,
                resourceId(objective, "block"), activationRadius
            ));
        }
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
            String placement = gate.has("placement")
                ? enumValue(gate, "placement", List.of("fixed", "marker")) : "fixed";
            if (placement.equals("marker")
                && !Set.of(
                    "fixed_template", "nbt_pieces", "procedural_cave", "hybrid"
                ).contains(terrainMode)) {
                throw new IllegalStateException(
                    "Marker-relative dungeon gate requires marker-aware terrain: "
                        + id + " -> " + gateId
                );
            }
            if ((placement.equals("fixed")
                    && (minimum.getX() < 0 || minimum.getY() < 0
                        || minimum.getZ() < 0))
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
            List<String> legacyRequirements = new ArrayList<>();
            JsonArray legacyValues = gate.has("requires")
                ? requiredArray(gate, "requires") : new JsonArray();
            List<GateRequirement> requirements = new ArrayList<>();
            for (JsonElement requirement : legacyValues) {
                String requiredEncounter = requirement.getAsString();
                if (!encounterIds.contains(requiredEncounter)
                    || !legacyRequirements.add(requiredEncounter)) {
                    throw new IllegalStateException(
                        "Invalid dungeon gate requirement: " + id + " -> "
                            + gateId + " -> " + requiredEncounter
                    );
                }
                requirements.add(new GateRequirement(
                    "encounter", requiredEncounter, null, 1, false
                ));
            }
            JsonArray typedValues = gate.has("requirements")
                ? requiredArray(gate, "requirements") : new JsonArray();
            Set<String> requirementKeys = requirements.stream().map(
                GateRequirement::key
            ).collect(java.util.stream.Collectors.toCollection(HashSet::new));
            for (JsonElement requirement : typedValues) {
                JsonObject value = requirement.getAsJsonObject();
                String type = enumValue(
                    value, "type", List.of("encounter", "objective", "item")
                );
                GateRequirement parsed;
                if (type.equals("item")) {
                    int count = requiredInt(value, "count");
                    if (count < 1 || count > 64) {
                        throw new IllegalStateException(
                            "Invalid dungeon gate item count: " + gateId
                        );
                    }
                    parsed = new GateRequirement(
                        type, null, resourceId(value, "item"), count,
                        requiredBoolean(value, "consume")
                    );
                } else {
                    String reference = requiredString(value, "id");
                    Set<String> known = type.equals("encounter")
                        ? encounterIds : objectiveIds;
                    if (!known.contains(reference)) {
                        throw new IllegalStateException(
                            "Unknown dungeon gate " + type + " requirement: "
                                + gateId + " -> " + reference
                        );
                    }
                    parsed = new GateRequirement(type, reference, null, 1, false);
                }
                if (!requirementKeys.add(parsed.key())) {
                    throw new IllegalStateException(
                        "Duplicate dungeon gate requirement: " + gateId
                    );
                }
                requirements.add(parsed);
            }
            if (requirements.isEmpty()) {
                throw new IllegalStateException(
                    "Dungeon gate requires at least one condition: " + id
                        + " -> " + gateId
                );
            }
            gates.add(new Gate(
                gateId, placement, minimum, maximum, resourceId(gate, "block"),
                List.copyOf(legacyRequirements), List.copyOf(requirements)
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
            BlockPos position = container.has("position")
                ? blockPosition(container, "position") : null;
            if (!lootContainerIds.add(containerId)) {
                throw new IllegalStateException(
                    "Duplicate dungeon loot container ID: " + id + " -> " + containerId
                );
            }
            if (position != null && !lootContainerPositions.add(position)) {
                throw new IllegalStateException(
                    "Duplicate dungeon loot container position: " + id + " -> " + position
                );
            }
            lootContainers.add(new LootContainer(
                containerId,
                position,
                enumValue(container, "block", List.of("chest", "barrel")),
                enumValue(container, "facing", List.of("north", "south", "west", "east")),
                requiredBoolean(container, "requires_completion"),
                container.has("loot_table") ? resourceId(container, "loot_table") : null
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
        encounters.stream().map(Encounter::position).filter(java.util.Objects::nonNull)
            .forEach(reservedPositions::add);
        for (HealingStation station : healingStations) {
            if (station.position() != null
                && !reservedPositions.add(station.position())) {
                throw new IllegalStateException(
                    "Dungeon healing station overlaps a reserved position: "
                        + id + " -> " + station.id()
                );
            }
        }
        for (LootContainer container : lootContainers) {
            if (container.position() != null
                && reservedPositions.contains(container.position())) {
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
        String resumeMode = lifecycle.has("resume_mode")
            ? enumValue(lifecycle, "resume_mode", List.of(
                "full_reset", "checkpoint", "keep_until_timeout"
            ))
            : "keep_until_timeout";
        int reconnectGraceSeconds = requiredInt(lifecycle, "reconnect_grace_seconds");
        if (reconnectGraceSeconds < 0 || reconnectGraceSeconds > 3600) {
            throw new IllegalStateException(
                "Invalid dungeon reconnect_grace_seconds: " + reconnectGraceSeconds
            );
        }
        if (resumeMode.equals("checkpoint") && checkpoints.isEmpty()) {
            throw new IllegalStateException(
                "checkpoint resume mode requires support.checkpoints: " + id
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
        if (returnTrigger.equals("clear_exit") && clearExitBlock == null) {
            throw new IllegalStateException(
                "clear_exit completion requires a block: " + id
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
                requiredBoolean(entryUi, "confirm_required"),
                entryUi.has("background_texture")
                    ? resourceId(entryUi, "background_texture")
                    : "cobbleventure_bootstrap:textures/gui/dungeons/rocket_facility.png"
            ),
            new Difficulty(recommendedMin, recommendedMax, internalMin, internalMax),
            new Eligibility(
                minimumPartySize,
                maximumPartySize,
                requiredBoolean(eligibility, "require_usable_pokemon"),
                enumValue(eligibility, "level_measure", List.of("average", "highest")),
                enumValue(eligibility, "recommended_level_policy", List.of(
                    "ignore", "warn", "enforce"
                )),
                eligibility.has("condition_mode")
                    ? enumValue(eligibility, "condition_mode", List.of("all", "any"))
                    : "all",
                List.copyOf(entryConditions),
                eligibility.has("locked_message")
                    ? requiredString(eligibility, "locked_message")
                    : "아직 이 던전에 입장할 수 없습니다."
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
            plan,
            new Terrain(
                terrainMode, template, entryPosition, exitPosition,
                piecePool, caveGenerator, terrainBounds, caveSettings
            ),
            layout,
            topology,
            progression,
            spatialLayout,
            vertical,
            npcPlacement(root, encounters, id),
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
            new Support(List.copyOf(healingStations), List.copyOf(checkpoints)),
            List.copyOf(objectives), List.copyOf(gates),
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
                resumeMode,
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

    private static Plan defaultPlan(String terrainMode) {
        return terrainMode.equals("fixed_template")
            ? new Plan("authored", List.of(), "fixed", "reject_entry", 1000, 1)
            : new Plan("runtime", List.of(), "random_per_run", "reject_entry", 1000, 32);
    }

    private static NpcPlacement npcPlacement(
        JsonObject root, List<Encounter> encounters, String dungeonId
    ) {
        int actorDemand = encounters.stream()
            .filter(encounter -> encounter.kind().equals("trainer"))
            .mapToInt(Encounter::actorCount)
            .sum();
        if (!root.has("npc_placement")) {
            return new NpcPlacement(false, "from_encounters", actorDemand, 4.0D, 2);
        }
        JsonObject value = requiredObject(root, "npc_placement");
        String capacityMode = enumValue(
            value, "capacity_mode", List.of("fixed", "from_encounters")
        );
        if (capacityMode.equals("from_encounters") && value.has("required_slots")) {
            throw new IllegalStateException(
                "from_encounters dungeon NPC placement cannot declare required_slots: "
                    + dungeonId
            );
        }
        int requiredSlots = capacityMode.equals("fixed")
            ? requiredInt(value, "required_slots") : actorDemand;
        if (requiredSlots < actorDemand || requiredSlots > 256) {
            throw new IllegalStateException(
                "Dungeon NPC placement requires at least " + actorDemand
                    + " slots but configured " + requiredSlots + ": " + dungeonId
            );
        }
        double minimumSpacing = requiredDouble(value, "minimum_spacing");
        int maximumPerRoom = requiredInt(value, "maximum_per_room");
        if (minimumSpacing < 0.0D || minimumSpacing > 32.0D
            || maximumPerRoom < 1 || maximumPerRoom > 16) {
            throw new IllegalStateException(
                "Invalid dungeon NPC placement limits: " + dungeonId
            );
        }
        return new NpcPlacement(
            true, capacityMode, requiredSlots, minimumSpacing, maximumPerRoom
        );
    }

    private static IntRange integerRange(
        JsonObject value, String key, int allowedMinimum, int allowedMaximum
    ) {
        JsonArray range = requiredArray(value, key);
        if (range.size() != 2) {
            throw new IllegalStateException("Dungeon integer range requires two values: " + key);
        }
        int minimum = range.get(0).getAsInt();
        int maximum = range.get(1).getAsInt();
        if (minimum < allowedMinimum || maximum > allowedMaximum || minimum > maximum) {
            throw new IllegalStateException(
                "Invalid dungeon " + key + " range: " + minimum + ".." + maximum
            );
        }
        return new IntRange(minimum, maximum);
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

    private static double requiredDouble(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || !value.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Dungeon number is missing: " + key);
        }
        return value.get(key).getAsDouble();
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

    private static List<String> nonEmptyStrings(JsonObject value, String key) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : requiredArray(value, key)) {
            if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
                throw new IllegalStateException(
                    "Dungeon text list contains an empty value: " + key
                );
            }
            result.add(element.getAsString());
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Dungeon text list is empty: " + key);
        }
        return List.copyOf(result);
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

    private static BlockPos positiveBlockPosition(JsonObject value, String key) {
        BlockPos position = blockPosition(value, key);
        if (position.getX() < 1 || position.getY() < 1 || position.getZ() < 1) {
            throw new IllegalStateException("Dungeon positive position is required: " + key);
        }
        return position;
    }

    record EntryUi(
        String infoMode,
        boolean confirmRequired,
        String backgroundTexture
    ) {}
    record Difficulty(int recommendedMin, int recommendedMax, int internalMin, int internalMax) {}
    record Eligibility(
        int minimumPartySize,
        int maximumPartySize,
        boolean requireUsablePokemon,
        String levelMeasure,
        String recommendedLevelPolicy,
        String conditionMode,
        List<PlayerConditions.Condition> conditions,
        String lockedMessage
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
    record Plan(
        String mode,
        List<String> planIds,
        String seedPolicy,
        String fallback,
        int generationTimeoutMs,
        int maxAttempts
    ) {}
    record Terrain(
        String mode,
        String template,
        BlockPos entryPosition,
        BlockPos exitPosition,
        String piecePool,
        String caveGenerator,
        BlockPos bounds,
        NaturalCaveGenerator.Settings caveSettings
    ) {}
    record Layout(
        String mode,
        IntRange criticalPathRooms,
        IntRange branchCount,
        IntRange branchDepth,
        double loopChance,
        String verticalDirection,
        IntRange floorChanges
    ) {}
    record Topology(
        String mode,
        IntRange criticalPathRooms,
        IntRange branchCount,
        IntRange branchDepth,
        double loopChance,
        ChamberGrid chamberGrid
    ) {
        Topology(
            String mode, IntRange criticalPathRooms, IntRange branchCount,
            IntRange branchDepth, double loopChance
        ) {
            this(
                mode, criticalPathRooms, branchCount, branchDepth,
                loopChance, null
            );
        }
    }
    record ChamberGrid(int width, int depth) {}
    record Progression(String pattern, int requiredTargets) {}
    record SpatialLayout(String algorithm) {}
    record Vertical(
        String mode,
        String direction,
        IntRange floorCount,
        int floorHeight,
        IntRange connectionsPerFloor
    ) {}
    record NpcPlacement(
        boolean enabled,
        String capacityMode,
        int requiredSlots,
        double minimumSpacing,
        int maximumPerRoom
    ) {}
    record IntRange(int minimum, int maximum) {}
    record Encounter(
        String id,
        String displayName,
        String kind,
        List<String> npcs,
        List<TrainerActor> trainers,
        List<String> opponents,
        GeneratedTrainer generatedTrainer,
        WildPokemon pokemon,
        List<String> requires,
        List<String> runStateKeys,
        EncounterTrigger trigger,
        BlockPos position,
        float yaw,
        boolean boss
    ) {
        int actorCount() {
            return trainers.isEmpty() ? npcs.size() : trainers.size();
        }
    }
    record TrainerActor(
        String id,
        String displayName,
        String trainerClass,
        String battle
    ) {}
    record EncounterTrigger(
        String type,
        int leader,
        double range,
        double warningOffset,
        String warningTrack,
        List<String> startLines,
        List<String> winLines,
        List<String> lossLines
    ) {}
    record GeneratedTrainer(
        List<WeightedSpecies> pokemonPool,
        IntRange teamSize,
        boolean allowDuplicates,
        List<String> battleStartLines,
        List<String> battleEndLines
    ) {}
    record WeightedSpecies(String species, int weight) {}
    record WildPokemon(String species, int level, boolean catchable) {}
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
    record Support(
        List<HealingStation> healingStations,
        List<Checkpoint> checkpoints
    ) {}
    record Gate(
        String id,
        String placement,
        BlockPos minimum,
        BlockPos maximum,
        String block,
        List<String> requires,
        List<GateRequirement> requirements
    ) {}
    record GateRequirement(
        String type, String reference, String item, int count, boolean consume
    ) {
        String key() {
            return type + ":" + (item == null ? reference : item);
        }
    }
    record Objective(
        String id,
        String kind,
        String placement,
        BlockPos position,
        String block,
        int activationRadius
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
    record Checkpoint(String id, BlockPos position, int activationRadius) {}
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
        boolean requiresCompletion,
        String lootTable
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
        String resumeMode,
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
