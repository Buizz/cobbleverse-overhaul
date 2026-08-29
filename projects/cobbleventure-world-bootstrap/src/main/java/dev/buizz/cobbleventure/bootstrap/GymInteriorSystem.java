package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;
import dev.buizz.cobbleventure.playermenu.BadgeProgressNetwork;
import dev.buizz.cobbleventure.adventure.event.ServerPlayerEventState;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Objective;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Creates isolated modular gym interiors and turns authored entrance anchors into data-driven doors. */
final class GymInteriorSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceKey<Level> INTERIORS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "gym_interiors")
    );
    private static final String INTERACTION_COOLDOWN = "cobbleventureGymDoorCooldown";
    private static final String STARTER_RECEIVED_FLAG =
        "cobbleventure:flag/story/starter_received";
    private static final int INSTANCE_GAP = 128;
    private static final int SLOT_Y = 64;
    private static final BlockPoint DEFAULT_DOOR = new BlockPoint(12, 3, 3);
    private static final BlockPoint DEFAULT_OUTSIDE = new BlockPoint(12, 4, 1);
    private static final BlockPoint DEFAULT_ENTRY = new BlockPoint(12, 4, 5);
    private static final Map<String, GymConfig> GYMS = new LinkedHashMap<>();
    private static final Map<String, GymDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();
    private static final Map<String, UUID> BLOCKING_NPCS = new HashMap<>();
    private static final Map<String, DoorTarget> BLOCKING_TARGETS = new HashMap<>();

    private GymInteriorSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onAttackEntity);
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onServerTick);
    }

    static void initialize(MinecraftServer server) {
        GYMS.clear();
        DEFINITIONS.clear();
        DOORS.clear();
        BLOCKING_NPCS.clear();
        BLOCKING_TARGETS.clear();
        loadConfigs(server);
        if (GYMS.isEmpty()) {
            return;
        }
        ServerLevel interiors = server.getLevel(INTERIORS);
        if (interiors == null) {
            throw new IllegalStateException("Cobbleventure gym_interiors dimension is missing");
        }
        int cursorX = 0;
        for (GymConfig gym : GYMS.values()) {
            gym.instanceOrigin = new BlockPos(cursorX, SLOT_Y, 0);
            placeInterior(interiors, gym);
            cursorX += Math.max(gym.interiorWidth(interiors), 32) + INSTANCE_GAP;
        }
    }

    static void prepareExterior(
        ServerLevel level, String settlementId,
        CobbleventureBootstrap.BlockPoint structureOrigin,
        String rotationName
    ) {
        GymConfig gym = GYMS.get(settlementId);
        if (gym == null || gym.instanceOrigin == null) {
            return;
        }
        BlockPos origin = structureOrigin.toBlockPos();
        sanitizeTemplate(level, gym.exteriorStructure, origin);
        applyExteriorPalette(level, gym.exteriorStructure, origin, gym.theme);
        ResourceLocation structureId = ResourceLocation.tryParse(gym.exteriorStructure);
        var template = structureId == null ? java.util.Optional
            .<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            return;
        }
        Vec3i size = template.orElseThrow().getSize();
        Rotation exteriorRotation = rotation(rotationName);
        ServerLevel interiors = level.getServer().getLevel(INTERIORS);
        if (!gym.connections.isEmpty() && interiors != null) {
            Map<String, SpaceInstance> spaces = new LinkedHashMap<>();
            spaces.put("exterior", new SpaceInstance(
                level, origin, exteriorRotation, gym.exteriorMetadata
            ));
            for (InteriorModule module : gym.modules) {
                spaces.put(module.id, new SpaceInstance(
                    interiors,
                    gym.instanceOrigin.offset(module.position.x, module.position.y, module.position.z),
                    module.rotation,
                    module.metadata
                ));
            }
            captureExteriorTarget(gym, spaces);
            for (GymConnection connection : gym.connections) {
                registerConnection(gym, spaces, connection);
            }
            return;
        }

        // Legacy settlement offsets remain as a fallback for catalogs without visual connections.
        BlockPoint doorOffset = rotatePoint(gym.doorOffset, size.getX(), size.getZ(), exteriorRotation);
        BlockPoint outsideOffset = rotatePoint(gym.outsideOffset, size.getX(), size.getZ(), exteriorRotation);
        BlockPos door = origin.offset(doorOffset.x, doorOffset.y, doorOffset.z);
        BlockPos destination = gym.instanceOrigin.offset(gym.entryOffset.x, gym.entryOffset.y, gym.entryOffset.z);
        BlockPos outside = origin.offset(outsideOffset.x, outsideOffset.y, outsideOffset.z);
        gym.exteriorDimension = level.dimension();
        gym.exteriorTarget = outside.immutable();
        registerDoor(level, door, new DoorTarget(
            INTERIORS, destination, blocker(gym, level, blockerPosition(door, outside)), gym.previousBadge,
            gym.conditions, gym.conditionMode,
            gym.lockedDialogue, gym.enterDialogue
        ));
        BlockPos exitDoor = gym.instanceOrigin.offset(
            gym.exitDoorOffset.x, gym.exitDoorOffset.y, gym.exitDoorOffset.z
        );
        registerDoor(interiors, exitDoor, new DoorTarget(
            level.dimension(), outside, null, null, List.of(), "all", List.of(), List.of()
        ));
    }

    private static void registerConnection(
        GymConfig gym, Map<String, SpaceInstance> spaces, GymConnection connection
    ) {
        SpaceInstance sourceSpace = spaces.get(connection.fromSpace);
        SpaceInstance targetSpace = spaces.get(connection.toSpace);
        if (sourceSpace == null || targetSpace == null) {
            LOGGER.warn("Gym connection references an unavailable space: {} -> {}",
                connection.fromSpace, connection.toSpace);
            return;
        }
        DoorAnchor source = sourceSpace.metadata.doorAnchors.get(connection.fromDoor);
        DoorAnchor target = targetSpace.metadata.doorAnchors.get(connection.toDoor);
        if (source == null || target == null) {
            LOGGER.warn("Gym connection references a missing door: {}:{} -> {}:{}",
                connection.fromSpace, connection.fromDoor, connection.toSpace, connection.toDoor);
            return;
        }

        BlockPos sourceDoor = sourceSpace.position(source.position);
        BlockPos targetDoor = targetSpace.position(target.position);
        BlockPos destination = targetSpace.position(target.safeSpawn);
        BlockPos reverseDestination = sourceSpace.position(source.safeSpawn);
        AccessPolicy gymAccess = new AccessPolicy(
            gym.conditionMode, gym.conditions, gym.lockedDialogue, gym.enterDialogue
        );
        AccessPolicy open = new AccessPolicy("all", List.of(), List.of(), List.of());
        AccessPolicy forwardAccess = connection.fromSpace.equals("exterior")
            ? gymAccess : connection.toSpace.equals("exterior") ? open : connection.access;
        AccessPolicy reverseAccess = connection.toSpace.equals("exterior") ? gymAccess : open;
        registerDoor(sourceSpace.level, sourceDoor, new DoorTarget(
            targetSpace.level.dimension(), destination,
            connection.fromSpace.equals("exterior")
                ? blocker(gym, sourceSpace.level, blockerPosition(sourceDoor, sourceSpace.position(source.safeSpawn))) : null,
            connection.fromSpace.equals("exterior") ? gym.previousBadge : null,
            forwardAccess.conditions, forwardAccess.conditionMode,
            forwardAccess.lockedDialogue, forwardAccess.enterDialogue
        ));
        registerDoor(targetSpace.level, targetDoor, new DoorTarget(
            sourceSpace.level.dimension(), reverseDestination,
            connection.toSpace.equals("exterior")
                ? blocker(gym, targetSpace.level, blockerPosition(targetDoor, targetSpace.position(target.safeSpawn))) : null,
            connection.toSpace.equals("exterior") ? gym.previousBadge : null,
            reverseAccess.conditions, reverseAccess.conditionMode,
            reverseAccess.lockedDialogue, reverseAccess.enterDialogue
        ));
    }

    static GymArrivalInfo arrivalInfo(String settlementId, ServerPlayer player) {
        GymConfig gym = GYMS.get(settlementId);
        if (gym == null) {
            return null;
        }
        Objective objective = player.getScoreboard().getObjective(gym.clearObjective);
        boolean cleared = objective != null
            && player.getScoreboard().getOrCreatePlayerScore(player, objective).get() > 0;
        return new GymArrivalInfo(gym.displayName, gym.theme, cleared);
    }

    static List<RadarLocationCatalog.ObjectiveLocation> radarObjectives(ServerPlayer player) {
        if (!new ServerPlayerEventState(player).flag(STARTER_RECEIVED_FLAG)) {
            return List.of();
        }
        List<GymConfig> gyms = GYMS.values().stream()
            .filter(gym -> gym.exteriorDimension != null && gym.exteriorTarget != null)
            .toList();
        List<ObjectiveProgress> progress = gyms.stream().map(gym -> {
            Objective objective = player.getScoreboard().getObjective(gym.clearObjective);
            boolean cleared = objective != null
                && player.getScoreboard().getOrCreatePlayerScore(player, objective).get() > 0;
            boolean unlocked = gym.previousBadge == null
                || BadgeProgressNetwork.hasBadge(player, gym.previousBadge);
            boolean sameDimension = player.level().dimension().equals(gym.exteriorDimension);
            double distanceSquared = sameDimension
                ? player.distanceToSqr(Vec3.atCenterOf(gym.exteriorTarget))
                : Double.POSITIVE_INFINITY;
            return new ObjectiveProgress(
                gym.settlementId, cleared, unlocked, sameDimension, distanceSquared
            );
        }).toList();
        int selected = currentObjectiveIndex(progress);
        if (selected < 0) return List.of();
        GymConfig gym = gyms.get(selected);
        BlockPos target = gym.exteriorTarget;
        return List.of(new RadarLocationCatalog.ObjectiveLocation(
            "objective/gym/" + gym.settlementId, "OBJECTIVE",
            gym.exteriorDimension.location(),
            target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
            gym.displayName + "에 도전", gym.settlementId, "PRIMARY"
        ));
    }

    static int currentObjectiveIndex(List<ObjectiveProgress> candidates) {
        int selected = -1;
        for (int index = 0; index < candidates.size(); index++) {
            ObjectiveProgress candidate = candidates.get(index);
            if (candidate.cleared || !candidate.unlocked) continue;
            if (selected < 0 || compareObjective(candidate, candidates.get(selected)) < 0) {
                selected = index;
            }
        }
        return selected;
    }

    private static int compareObjective(ObjectiveProgress left, ObjectiveProgress right) {
        int dimension = Boolean.compare(right.sameDimension, left.sameDimension);
        if (dimension != 0) return dimension;
        int distance = Double.compare(left.distanceSquared, right.distanceSquared);
        return distance != 0 ? distance : left.id.compareTo(right.id);
    }

    private static void captureExteriorTarget(
        GymConfig gym, Map<String, SpaceInstance> spaces
    ) {
        SpaceInstance exterior = spaces.get("exterior");
        if (exterior == null) return;
        for (GymConnection connection : gym.connections) {
            String doorId = connection.fromSpace.equals("exterior")
                ? connection.fromDoor
                : connection.toSpace.equals("exterior") ? connection.toDoor : null;
            DoorAnchor door = doorId == null ? null
                : exterior.metadata.doorAnchors.get(doorId);
            if (door != null) {
                gym.exteriorDimension = exterior.level.dimension();
                gym.exteriorTarget = exterior.position(door.safeSpawn).immutable();
                return;
            }
        }
    }

    private static void loadConfigs(MinecraftServer server) {
        server.getResourceManager().getResource(
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "catalogs/gyms.json")
        ).ifPresent(resource -> {
            try (Reader reader = resource.openAsReader()) {
                JsonObject catalog = JsonParser.parseReader(reader).getAsJsonObject();
                for (JsonElement element : catalog.getAsJsonArray("gyms")) {
                    JsonObject gym = element.getAsJsonObject();
                    if (gym.has("enabled") && !gym.get("enabled").getAsBoolean()) {
                        continue;
                    }
                    GymDefinition definition = parseDefinition(server, gym);
                    DEFINITIONS.put(definition.id, definition);
                }
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Invalid gym catalog", error);
            }
        });
        Map<ResourceLocation, Resource> resources = server.getResourceManager().listResources(
            "settlements",
            location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".json")
        );
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("enabled") || !root.get("enabled").getAsBoolean()) {
                    return;
                }
                JsonObject profile = root.getAsJsonObject("structure_profile");
                JsonObject gym = profile == null || !profile.has("gym")
                    ? null : profile.getAsJsonObject("gym");
                if (gym == null || !gym.get("enabled").getAsBoolean()) {
                    return;
                }
                String gymId = requiredString(gym, "gym_id");
                GymDefinition definition = DEFINITIONS.get(gymId);
                if (definition == null) {
                    throw new IllegalStateException("Unknown gym definition: " + gymId);
                }
                GymConfig config = parseConfig(requiredString(root, "id"), gym, definition);
                if (GYMS.putIfAbsent(config.settlementId, config) != null) {
                    throw new IllegalStateException("Duplicate gym settlement: " + config.settlementId);
                }
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Invalid gym configuration: " + entry.getKey(), error);
            }
        });
    }

    private static GymDefinition parseDefinition(MinecraftServer server, JsonObject gym) {
        JsonObject exterior = gym.getAsJsonObject("exterior");
        JsonObject interior = gym.getAsJsonObject("interior");
        String exteriorStructure = requiredString(exterior, "structure");
        ModuleMetadata exteriorMetadata = readModuleMetadata(server, exteriorStructure);
        List<InteriorModule> modules = new ArrayList<>();
        Map<String, BlockPoint> npcOffsets = new LinkedHashMap<>();
        for (JsonElement element : interior.getAsJsonArray("modules")) {
            JsonObject module = element.getAsJsonObject();
            JsonArray position = module.getAsJsonArray("position");
            String structure = requiredString(module, "structure");
            BlockPoint modulePosition = new BlockPoint(
                position.get(0).getAsInt(), position.get(1).getAsInt(), position.get(2).getAsInt()
            );
            Rotation moduleRotation = rotation(optionalString(module, "rotation", "none"));
            ModuleMetadata metadata = readModuleMetadata(server, structure);
            for (Map.Entry<String, BlockPoint> anchor : metadata.npcAnchors.entrySet()) {
                BlockPoint rotated = rotatePoint(
                    anchor.getValue(), metadata.width, metadata.depth, moduleRotation
                );
                BlockPoint offset = new BlockPoint(
                    modulePosition.x + rotated.x,
                    modulePosition.y + rotated.y,
                    modulePosition.z + rotated.z
                );
                if (npcOffsets.putIfAbsent(anchor.getKey(), offset) != null) {
                    throw new IllegalStateException(
                        "Gym has duplicate NPC anchor: " + requiredString(gym, "id") + "/" + anchor.getKey()
                    );
                }
            }
            modules.add(new InteriorModule(
                requiredString(module, "id"), structure, modulePosition, moduleRotation, metadata
            ));
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("Gym needs at least one interior module: " + requiredString(gym, "id"));
        }
        List<GymStaffMember> staffMembers = new ArrayList<>();
        JsonObject staff = gym.getAsJsonObject("staff");
        JsonObject leader = staff.getAsJsonObject("leader");
        String leaderTrainerId = nullableString(leader, "trainer_id");
        addStaffMember(staffMembers, npcOffsets, nullableString(leader, "trainer_id"), optionalString(leader, "anchor", "leader"), "leader");
        for (JsonElement element : staff.getAsJsonArray("trainers")) {
            JsonObject trainer = element.getAsJsonObject();
            addStaffMember(
                staffMembers, npcOffsets, requiredString(trainer, "trainer_id"),
                requiredString(trainer, "anchor"), requiredString(trainer, "id")
            );
        }
        List<GymConnection> connections = parseConnections(interior);
        GymAccess access = gymAccess(gym, interior);
        return new GymDefinition(
            requiredString(gym, "id"), localizedName(gym.getAsJsonObject("display_name")),
            requiredString(gym, "theme"), exteriorStructure, exteriorMetadata,
            clearVariable(leaderTrainerId), List.copyOf(modules), List.copyOf(staffMembers),
            connections, access.policy, access.previousBadge, access.blockingNpcPreset
        );
    }

    private static List<GymConnection> parseConnections(JsonObject interior) {
        List<GymConnection> connections = new ArrayList<>();
        if (interior == null || !interior.has("connections")) {
            return List.of();
        }
        for (JsonElement element : interior.getAsJsonArray("connections")) {
            JsonObject connection = element.getAsJsonObject();
            String[] from = endpoint(requiredString(connection, "from"));
            String[] to = endpoint(requiredString(connection, "to"));
            List<PlayerConditions.Condition> conditions = new ArrayList<>();
            if (connection.has("conditions")) {
                for (JsonElement condition : connection.getAsJsonArray("conditions")) {
                    conditions.add(PlayerConditions.parse(condition.getAsJsonObject()));
                }
            }
            connections.add(new GymConnection(
                from[0], from[1], to[0], to[1],
                new AccessPolicy(
                    optionalString(connection, "condition_mode", "all"),
                    List.copyOf(conditions),
                    strings(connection, "locked_dialogue", List.of("문이 잠겨 있다.")),
                    strings(connection, "enter_dialogue", List.of())
                )
            ));
        }
        return List.copyOf(connections);
    }

    private static String[] endpoint(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalStateException("Invalid gym connection endpoint: " + value);
        }
        return new String[] {value.substring(0, separator), value.substring(separator + 1)};
    }

    private static GymAccess gymAccess(JsonObject gym, JsonObject interior) {
        if (gym.has("access")) {
            JsonObject access = gym.getAsJsonObject("access");
            List<PlayerConditions.Condition> conditions = new ArrayList<>();
            if (access.has("conditions")) {
                for (JsonElement condition : access.getAsJsonArray("conditions")) {
                    conditions.add(PlayerConditions.parse(condition.getAsJsonObject()));
                }
            }
            String previousBadge = access.has("require_previous_gym")
                && access.get("require_previous_gym").getAsBoolean()
                ? nullableString(access, "previous_badge") : null;
            String blockingNpcPreset = null;
            if (access.has("blocking_npc")) {
                JsonObject blocker = access.getAsJsonObject("blocking_npc");
                if (blocker.has("enabled") && blocker.get("enabled").getAsBoolean()) {
                    String profile = nullableString(blocker, "npc_profile");
                    if (profile != null) {
                        String slug = profile.substring(Math.max(profile.lastIndexOf('/'), profile.lastIndexOf(':')) + 1);
                        blockingNpcPreset = "easy_npc:preset/encounter/" + slug + ".npc.snbt";
                    }
                }
            }
            return new GymAccess(
                new AccessPolicy(
                    optionalString(access, "condition_mode", "all"),
                    List.copyOf(conditions),
                    nonEmptyStrings(access, "locked_dialogue", List.of("문이 잠겨 있다.")),
                    List.of()
                ),
                previousBadge,
                blockingNpcPreset
            );
        }
        if (interior != null && interior.has("connections")) {
            for (JsonElement element : interior.getAsJsonArray("connections")) {
                JsonObject connection = element.getAsJsonObject();
                if (!requiredString(connection, "from").startsWith("exterior:")) {
                    continue;
                }
                List<PlayerConditions.Condition> conditions = new ArrayList<>();
                if (connection.has("conditions")) {
                    for (JsonElement condition : connection.getAsJsonArray("conditions")) {
                        conditions.add(PlayerConditions.parse(condition.getAsJsonObject()));
                    }
                }
                return new GymAccess(new AccessPolicy(
                    optionalString(connection, "condition_mode", "all"), List.copyOf(conditions),
                    strings(connection, "locked_dialogue", List.of("문이 잠겨 있다.")),
                    strings(connection, "enter_dialogue", List.of())
                ), null, null);
            }
        }
        return new GymAccess(
            new AccessPolicy("all", List.of(), List.of("문이 잠겨 있다."), List.of()),
            null, null
        );
    }

    private static String localizedName(JsonObject value) {
        if (value == null) return "체육관";
        if (value.has("ko_kr")) return value.get("ko_kr").getAsString();
        if (value.has("en_us")) return value.get("en_us").getAsString();
        return "체육관";
    }

    private static String clearVariable(String trainerId) {
        if (trainerId == null || trainerId.isBlank()) {
            return "cobbleventure:flag/gym/unknown/defeated";
        }
        String slug = trainerId.substring(
            Math.max(trainerId.lastIndexOf('/'), trainerId.lastIndexOf(':')) + 1
        );
        return "cobbleventure:flag/gym/kanto/" + slug + "/defeated";
    }

    private static String flagObjective(String variable) {
        return PlayerConditions.flagObjective(variable);
    }

    private static void addStaffMember(
        List<GymStaffMember> members, Map<String, BlockPoint> npcOffsets,
        String trainerId, String anchor, String role
    ) {
        if (trainerId == null) {
            return;
        }
        BlockPoint offset = npcOffsets.get(anchor);
        if (offset == null) {
            throw new IllegalStateException("Gym staff anchor does not exist: " + anchor);
        }
        members.add(new GymStaffMember(
            role, staffPreset(trainerId, role), offset
        ));
    }

    static String staffPreset(String trainerId, String role) {
        String slug = trainerId.substring(
            Math.max(trainerId.lastIndexOf('/'), trainerId.lastIndexOf(':')) + 1
        );
        String suffix = role.equals("leader") ? "__v5" : "__v5_proximity";
        return "easy_npc:preset/encounter/" + slug + suffix + ".npc.snbt";
    }

    private static ModuleMetadata readModuleMetadata(MinecraftServer server, String structure) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) {
            throw new IllegalStateException("Invalid gym interior structure: " + structure);
        }
        ResourceLocation metadataId = ResourceLocation.fromNamespaceAndPath(
            structureId.getNamespace(), "structure_metadata/" + structureId.getPath() + ".structure.json"
        );
        var template = server.getStructureManager().get(structureId);
        int templateWidth = template.map(value -> value.getSize().getX()).orElse(0);
        int templateDepth = template.map(value -> value.getSize().getZ()).orElse(0);
        var resource = server.getResourceManager().getResource(metadataId);
        if (resource.isEmpty()) {
            return new ModuleMetadata(templateWidth, templateDepth, Map.of(), Map.of());
        }
        try (Reader reader = resource.orElseThrow().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject interior = root.getAsJsonObject("interior");
            int width = interior == null ? templateWidth : interior.get("width").getAsInt();
            int depth = interior == null ? templateDepth : interior.get("depth").getAsInt();
            Map<String, BlockPoint> npcAnchors = new LinkedHashMap<>();
            Map<String, DoorAnchor> doorAnchors = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                String type = optionalString(anchor, "type", "");
                String label = optionalString(anchor, "id", optionalString(anchor, "label", ""));
                if (label.isBlank()) continue;
                JsonArray point = anchor.getAsJsonArray("position");
                BlockPoint position = new BlockPoint(
                    point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()
                );
                if ("npc_position".equals(type) && npcAnchors.putIfAbsent(label, position) != null) {
                    throw new IllegalStateException("Duplicate NPC anchor in gym module: " + label);
                }
                if ("door".equals(type)) {
                    BlockPoint safeSpawn = anchor.has("safe_spawn")
                        ? arrayPoint(anchor.getAsJsonArray("safe_spawn")) : position;
                    if (doorAnchors.putIfAbsent(label, new DoorAnchor(position, safeSpawn)) != null) {
                        throw new IllegalStateException("Duplicate door anchor in gym module: " + label);
                    }
                }
            }
            return new ModuleMetadata(
                width, depth, Map.copyOf(npcAnchors), Map.copyOf(doorAnchors)
            );
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid gym module metadata: " + metadataId, error);
        }
    }

    private static BlockPoint arrayPoint(JsonArray point) {
        return new BlockPoint(
            point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()
        );
    }

    private static BlockPoint rotatePoint(BlockPoint point, int width, int depth, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPoint(depth - 1 - point.z, point.y, point.x);
            case CLOCKWISE_180 -> new BlockPoint(width - 1 - point.x, point.y, depth - 1 - point.z);
            case COUNTERCLOCKWISE_90 -> new BlockPoint(point.z, point.y, width - 1 - point.x);
            default -> point;
        };
    }

    private static GymConfig parseConfig(String settlementId, JsonObject gym, GymDefinition definition) {
        JsonObject entrance = gym.has("entrance") ? gym.getAsJsonObject("entrance") : null;
        JsonObject interior = gym.has("interior") ? gym.getAsJsonObject("interior") : null;
        return new GymConfig(
            settlementId,
            definition.displayName,
            definition.theme,
            flagObjective(definition.clearVariable),
            definition.exteriorStructure,
            definition.exteriorMetadata,
            definition.modules,
            point(entrance, "door_offset", DEFAULT_DOOR),
            point(entrance, "outside_offset", DEFAULT_OUTSIDE),
            direction(optionalString(entrance, "facing", "north")),
            definition.previousBadge,
            definition.access.conditionMode,
            definition.access.conditions,
            definition.access.lockedDialogue,
            strings(entrance, "enter_dialogue", definition.access.enterDialogue),
            point(interior, "entry_offset", DEFAULT_ENTRY),
            point(interior, "exit_door_offset", DEFAULT_DOOR),
            definition.connections, definition.blockingNpcPreset,
            definition.staff
        );
    }

    private static void placeInterior(ServerLevel level, GymConfig gym) {
        BlockPos marker = gym.instanceOrigin.offset(0, -2, 0);
        BlockPos staffMarker = gym.instanceOrigin.offset(1, -2, 0);
        if (!level.getBlockState(marker).is(Blocks.RESPAWN_ANCHOR)) {
            for (InteriorModule module : gym.modules) {
                ResourceLocation id = ResourceLocation.tryParse(module.structure);
                var template = id == null ? java.util.Optional
                    .<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
                    : level.getStructureManager().get(id);
                if (template.isEmpty()) {
                    throw new IllegalStateException("Gym interior module is missing: " + module.structure);
                }
                BlockPos moduleOrigin = gym.instanceOrigin.offset(module.position.x, module.position.y, module.position.z);
                Vec3i size = template.orElseThrow().getSize(module.rotation);
                forceChunks(level, moduleOrigin, size);
                StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(module.rotation)
                    .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE);
                ExplicitAirPlacementProcessor.configure(template.orElseThrow(), settings);
                boolean placed = template.orElseThrow().placeInWorld(
                    level, moduleOrigin, moduleOrigin, settings,
                    RandomSource.create(level.getSeed() ^ moduleOrigin.asLong()), 2
                );
                if (!placed) {
                    throw new IllegalStateException("Gym interior module placement failed: " + module.structure);
                }
                StructurePlacementFixes.afterPlacement(
                    level, moduleOrigin, template.orElseThrow(), settings
                );
                sanitize(level, moduleOrigin, size);
            }
            level.setBlock(marker, Blocks.RESPAWN_ANCHOR.defaultBlockState(), 2);
            LOGGER.info(
                "Modular gym interior generated: settlement={}, modules={}, origin={}",
                gym.settlementId, gym.modules.size(), gym.instanceOrigin
            );
        }
        if (!gym.staff.isEmpty() && !level.getBlockState(staffMarker).is(Blocks.LODESTONE)) {
            for (GymStaffMember staff : gym.staff) {
                BlockPoint offset = staff.offset;
                spawnNpc(level, gym, staff, gym.instanceOrigin.offset(offset.x, offset.y, offset.z));
            }
            level.setBlock(staffMarker, Blocks.LODESTONE.defaultBlockState(), 2);
        }
    }

    private static void sanitizeTemplate(ServerLevel level, String structure, BlockPos origin) {
        ResourceLocation id = ResourceLocation.tryParse(structure);
        var template = id == null ? java.util.Optional
            .<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(id);
        template.ifPresent(value -> sanitize(level, origin, value.getSize()));
    }

    private static void applyExteriorPalette(
        ServerLevel level, String structure, BlockPos origin, String theme
    ) {
        ResourceLocation id = ResourceLocation.tryParse(structure);
        var template = id == null ? java.util.Optional
            .<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(id);
        if (template.isEmpty()) {
            return;
        }
        ExteriorPalette palette = exteriorPalette(theme);
        Vec3i size = template.orElseThrow().getSize();
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos position = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(position);
                    if (state.is(Blocks.LIGHT_GRAY_CONCRETE)) {
                        level.setBlock(position, palette.primary, 2);
                    } else if (state.is(Blocks.YELLOW_CONCRETE)) {
                        level.setBlock(position, palette.secondary, 2);
                    } else if (state.is(Blocks.ORANGE_STAINED_GLASS_PANE)) {
                        level.setBlock(position, palette.glass, 2);
                    }
                }
            }
        }
    }

    private static ExteriorPalette exteriorPalette(String theme) {
        return switch (theme) {
            case "fire" -> palette(Blocks.RED_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.RED_STAINED_GLASS_PANE);
            case "water" -> palette(Blocks.BLUE_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.BLUE_STAINED_GLASS_PANE);
            case "electric" -> palette(Blocks.YELLOW_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.YELLOW_STAINED_GLASS_PANE);
            case "grass", "bug" -> palette(Blocks.GREEN_CONCRETE, Blocks.LIME_CONCRETE, Blocks.GREEN_STAINED_GLASS_PANE);
            case "ice", "flying" -> palette(Blocks.LIGHT_BLUE_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE);
            case "fighting" -> palette(Blocks.RED_CONCRETE, Blocks.BROWN_CONCRETE, Blocks.RED_STAINED_GLASS_PANE);
            case "poison" -> palette(Blocks.PURPLE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.PURPLE_STAINED_GLASS_PANE);
            case "ground" -> palette(Blocks.BROWN_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.BROWN_STAINED_GLASS_PANE);
            case "psychic", "fairy" -> palette(Blocks.PINK_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.PINK_STAINED_GLASS_PANE);
            case "ghost", "dragon" -> palette(Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.PURPLE_STAINED_GLASS_PANE);
            case "dark" -> palette(Blocks.BLACK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.BLACK_STAINED_GLASS_PANE);
            case "steel" -> palette(Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_STAINED_GLASS_PANE);
            case "normal" -> palette(Blocks.WHITE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.WHITE_STAINED_GLASS_PANE);
            default -> palette(Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_STAINED_GLASS_PANE);
        };
    }

    private static ExteriorPalette palette(
        net.minecraft.world.level.block.Block primary,
        net.minecraft.world.level.block.Block secondary,
        net.minecraft.world.level.block.Block glass
    ) {
        return new ExteriorPalette(
            primary.defaultBlockState(), secondary.defaultBlockState(), glass.defaultBlockState()
        );
    }

    private static void sanitize(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(BlockTags.PRESSURE_PLATES)
                        || state.is(Blocks.COMMAND_BLOCK)
                        || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                        || state.is(Blocks.REPEATING_COMMAND_BLOCK)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void registerDoor(ServerLevel level, BlockPos lower, DoorTarget target) {
        if (level == null) {
            return;
        }
        registerDoorBlocks(level, lower, target);
        BlockPos paired = pairedDoorPosition(level, lower);
        if (paired != null) {
            registerDoorBlocks(level, paired, target);
        }
    }

    private static void registerDoorBlocks(ServerLevel level, BlockPos lower, DoorTarget target) {
        DOORS.put(new DoorKey(level.dimension(), lower.immutable()), target);
        DOORS.put(new DoorKey(level.dimension(), lower.above().immutable()), target);
        if (target.blocker != null) {
            BLOCKING_TARGETS.put(target.blocker.key, target);
        }
    }

    private static BlockPos pairedDoorPosition(ServerLevel level, BlockPos lower) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return null;
        }
        Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        for (Direction side : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
            BlockPos candidate = lower.relative(side);
            BlockState other = level.getBlockState(candidate);
            if (other.getBlock() == state.getBlock()
                && other.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && other.getValue(DoorBlock.FACING) == facing
                && other.getValue(DoorBlock.HINGE) != hinge) {
                return candidate;
            }
        }
        return null;
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DoorTarget target = DOORS.get(new DoorKey(player.level().dimension(), event.getPos()));
        if (target == null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        long gameTime = player.level().getGameTime();
        if (player.getPersistentData().getLong(INTERACTION_COOLDOWN) > gameTime) {
            return;
        }
        player.getPersistentData().putLong(INTERACTION_COOLDOWN, gameTime + 10L);
        if (!target.allows(player)) {
            ensureBlockingNpc(player.getServer(), target.blocker);
            syncBlockerVisibility(player.getServer());
            sendDialogue(player, target.lockedDialogue);
            return;
        }
        sendDialogue(player, target.enterDialogue);
        ServerLevel destination = player.getServer().getLevel(target.dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal("[체육관 문] 이동할 공간을 찾을 수 없습니다."));
            return;
        }
        ResourceKey<Level> sourceDimension = player.level().dimension();
        player.teleportTo(
            destination,
            target.position.getX() + 0.5D,
            target.position.getY(),
            target.position.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
        DoorTransitionSound.afterTeleport(player, sourceDimension, target.position);
        InteriorMusicSystem.sync(player);
    }

    private static void sendDialogue(ServerPlayer player, List<String> lines) {
        for (String line : lines) {
            player.sendSystemMessage(Component.literal("[체육관 문] " + line));
        }
    }

    static boolean isInteriorDimension(ServerLevel level) {
        return level.dimension().equals(INTERIORS);
    }

    static String interiorMusicTrack() {
        for (GymConfig gym : GYMS.values()) {
            for (InteriorModule module : gym.modules) {
                String track = BuildingRuntimeSystem.musicTrack(module.structure);
                if (track != null && !track.isBlank()) {
                    return track;
                }
            }
        }
        return null;
    }

    private static void spawnNpc(ServerLevel level, GymConfig gym, GymStaffMember staff, BlockPos position) {
        String command = "easy_npc preset import_new data " + staff.npcPreset + " "
            + position.getX() + " " + position.getY() + " " + position.getZ();
        try {
            int result = level.getServer().getCommands().getDispatcher().execute(
                command,
                level.getServer().createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(Vec3.atLowerCornerOf(position))
                    .withPermission(4)
                    .withSuppressedOutput()
            );
            if (result == 0) {
                LOGGER.warn("Gym staff NPC command returned no result: {}/{}", gym.settlementId, staff.role);
            }
        } catch (CommandSyntaxException error) {
            throw new IllegalStateException(
                "Gym staff NPC placement failed: " + gym.settlementId + "/" + staff.role, error
            );
        }
    }

    private static BlockingNpc blocker(GymConfig gym, ServerLevel level, BlockPos position) {
        if (gym.blockingNpcPreset == null) return null;
        return new BlockingNpc(
            gym.settlementId, level.dimension(), position, gym.blockingNpcPreset
        );
    }

    private static BlockPos blockerPosition(BlockPos door, BlockPos safeSpawn) {
        int outsideX = Integer.signum(safeSpawn.getX() - door.getX());
        int outsideZ = Integer.signum(safeSpawn.getZ() - door.getZ());
        return safeSpawn.offset(outsideX, 0, outsideZ);
    }

    private static void ensureBlockingNpc(MinecraftServer server, BlockingNpc blocker) {
        if (blocker == null || BLOCKING_NPCS.containsKey(blocker.key)) return;
        ServerLevel level = server.getLevel(blocker.dimension);
        if (level == null) return;
        AABB search = new AABB(blocker.position).inflate(2.0D);
        Set<UUID> before = new HashSet<>();
        for (Entity entity : level.getEntities((Entity) null, search)) before.add(entity.getUUID());
        GymConfig gym = GYMS.get(blocker.key);
        if (gym == null) return;
        spawnNpc(level, gym, new GymStaffMember("door_blocker", blocker.preset, new BlockPoint(0, 0, 0)), blocker.position);
        level.getEntities((Entity) null, search, entity -> !before.contains(entity.getUUID()))
            .stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(Vec3.atCenterOf(blocker.position))))
            .ifPresent(entity -> {
                entity.noPhysics = true;
                entity.setInvulnerable(true);
                entity.setNoGravity(true);
                entity.setDeltaMovement(Vec3.ZERO);
                BLOCKING_NPCS.put(blocker.key, entity.getUUID());
            });
    }

    private static void removeBlockingNpc(MinecraftServer server, BlockingNpc blocker) {
        UUID uuid = BLOCKING_NPCS.remove(blocker.key);
        ServerLevel level = server.getLevel(blocker.dimension);
        if (uuid == null || level == null) return;
        Entity entity = level.getEntity(uuid);
        if (entity != null) entity.discard();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0 || BLOCKING_TARGETS.isEmpty()) return;
        for (DoorTarget target : BLOCKING_TARGETS.values()) {
            BlockingNpc blocker = target.blocker;
            boolean blockedPlayerNearby = server.getPlayerList().getPlayers().stream().anyMatch(player ->
                player.level().dimension().equals(blocker.dimension)
                    && player.distanceToSqr(Vec3.atCenterOf(blocker.position)) <= 48.0D * 48.0D
                    && !target.allows(player)
            );
            if (blockedPlayerNearby) ensureBlockingNpc(server, blocker);
            else removeBlockingNpc(server, blocker);
        }
        syncBlockerVisibility(server);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncBlockerVisibility(player);
        }
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player
            && isHiddenBlocker(player, event.getTarget().getUUID())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player
            && isHiddenBlocker(player, event.getTarget().getUUID())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
            && isHiddenBlocker(player, event.getTarget().getUUID())) {
            event.setCanceled(true);
        }
    }

    private static boolean isHiddenBlocker(ServerPlayer player, UUID entityId) {
        for (Map.Entry<String, UUID> entry : BLOCKING_NPCS.entrySet()) {
            if (!entry.getValue().equals(entityId)) continue;
            DoorTarget target = BLOCKING_TARGETS.get(entry.getKey());
            return target != null && target.allows(player);
        }
        return false;
    }

    private static void syncBlockerVisibility(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncBlockerVisibility(player);
        }
    }

    private static void syncBlockerVisibility(ServerPlayer player) {
        List<UUID> hidden = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : BLOCKING_NPCS.entrySet()) {
            DoorTarget target = BLOCKING_TARGETS.get(entry.getKey());
            if (target != null && target.allows(player)) {
                hidden.add(entry.getValue());
            }
        }
        GymBlockerVisibilityNetwork.sync(player, hidden);
    }

    private static void forceChunks(ServerLevel level, BlockPos origin, Vec3i size) {
        int minChunkX = (origin.getX() >> 4) - 1;
        int maxChunkX = ((origin.getX() + size.getX()) >> 4) + 1;
        int minChunkZ = (origin.getZ() >> 4) - 1;
        int maxChunkZ = ((origin.getZ() + size.getZ()) >> 4) + 1;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static BlockPoint point(JsonObject parent, String key, BlockPoint fallback) {
        if (parent == null || !parent.has(key)) {
            return fallback;
        }
        JsonObject value = parent.getAsJsonObject(key);
        return new BlockPoint(
            value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt()
        );
    }

    private static List<String> strings(
        JsonObject parent, String key, List<String> fallback
    ) {
        if (parent == null || !parent.has(key)) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : parent.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    private static List<String> nonEmptyStrings(JsonObject parent, String key, List<String> fallback) {
        List<String> values = strings(parent, key, fallback);
        return values.isEmpty() ? fallback : values;
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("Gym field is required: " + key);
        }
        return value.get(key).getAsString();
    }

    private static String optionalString(JsonObject value, String key, String fallback) {
        return value != null && value.has(key) ? value.get(key).getAsString() : fallback;
    }

    private static String nullableString(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).getAsString().isBlank()
            ? value.get(key).getAsString() : null;
    }

    private static Direction direction(String value) {
        return switch (value) {
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private static Rotation rotation(String value) {
        return switch (value) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private record BlockPoint(int x, int y, int z) {}

    private record DoorKey(ResourceKey<Level> dimension, BlockPos position) {}

    private record DoorTarget(
        ResourceKey<Level> dimension,
        BlockPos position,
        BlockingNpc blocker,
        String requiredBadge,
        List<PlayerConditions.Condition> conditions,
        String conditionMode,
        List<String> lockedDialogue,
        List<String> enterDialogue
    ) {
        boolean allows(ServerPlayer player) {
            if (requiredBadge != null && !BadgeProgressNetwork.hasBadge(player, requiredBadge)) {
                return false;
            }
            if (conditions.isEmpty()) {
                return true;
            }
            return PlayerConditions.matches(player, conditionMode, conditions);
        }
    }

    private record BlockingNpc(
        String key, ResourceKey<Level> dimension, BlockPos position, String preset
    ) {}

    private record GymDefinition(
        String id, String displayName, String theme, String exteriorStructure,
        ModuleMetadata exteriorMetadata,
        String clearVariable, List<InteriorModule> modules, List<GymStaffMember> staff,
        List<GymConnection> connections, AccessPolicy access,
        String previousBadge, String blockingNpcPreset
    ) {}

    private record GymAccess(
        AccessPolicy policy, String previousBadge, String blockingNpcPreset
    ) {}

    private record AccessPolicy(
        String conditionMode, List<PlayerConditions.Condition> conditions,
        List<String> lockedDialogue, List<String> enterDialogue
    ) {}

    record GymArrivalInfo(String displayName, String theme, boolean cleared) {}

    record ObjectiveProgress(
        String id, boolean cleared, boolean unlocked,
        boolean sameDimension, double distanceSquared
    ) {}

    private record ExteriorPalette(BlockState primary, BlockState secondary, BlockState glass) {}

    private record DoorAnchor(BlockPoint position, BlockPoint safeSpawn) {}

    private record ModuleMetadata(
        int width, int depth, Map<String, BlockPoint> npcAnchors,
        Map<String, DoorAnchor> doorAnchors
    ) {}

    private record SpaceInstance(
        ServerLevel level, BlockPos origin, Rotation rotation, ModuleMetadata metadata
    ) {
        BlockPos position(BlockPoint local) {
            BlockPoint transformed = rotatePoint(local, metadata.width, metadata.depth, rotation);
            return origin.offset(transformed.x, transformed.y, transformed.z);
        }
    }

    private record GymConnection(
        String fromSpace, String fromDoor, String toSpace, String toDoor,
        AccessPolicy access
    ) {}

    private record GymStaffMember(String role, String npcPreset, BlockPoint offset) {}

    private record InteriorModule(
        String id, String structure, BlockPoint position, Rotation rotation,
        ModuleMetadata metadata
    ) {}

    private static final class GymConfig {
        final String settlementId;
        final String displayName;
        final String theme;
        final String clearObjective;
        final String exteriorStructure;
        final ModuleMetadata exteriorMetadata;
        final List<InteriorModule> modules;
        final BlockPoint doorOffset;
        final BlockPoint outsideOffset;
        final Direction facing;
        final String previousBadge;
        final String conditionMode;
        final List<PlayerConditions.Condition> conditions;
        final List<String> lockedDialogue;
        final List<String> enterDialogue;
        final BlockPoint entryOffset;
        final BlockPoint exitDoorOffset;
        final List<GymConnection> connections;
        final String blockingNpcPreset;
        final List<GymStaffMember> staff;
        BlockPos instanceOrigin;
        ResourceKey<Level> exteriorDimension;
        BlockPos exteriorTarget;

        GymConfig(
            String settlementId, String displayName, String theme, String clearObjective,
            String exteriorStructure, ModuleMetadata exteriorMetadata,
            List<InteriorModule> modules,
            BlockPoint doorOffset,
            BlockPoint outsideOffset, Direction facing, String previousBadge,
            String conditionMode,
            List<PlayerConditions.Condition> conditions, List<String> lockedDialogue,
            List<String> enterDialogue, BlockPoint entryOffset,
            BlockPoint exitDoorOffset, List<GymConnection> connections,
            String blockingNpcPreset,
            List<GymStaffMember> staff
        ) {
            this.settlementId = settlementId;
            this.displayName = displayName;
            this.theme = theme;
            this.clearObjective = clearObjective;
            this.exteriorStructure = exteriorStructure;
            this.exteriorMetadata = exteriorMetadata;
            this.modules = modules;
            this.doorOffset = doorOffset;
            this.outsideOffset = outsideOffset;
            this.facing = facing;
            this.previousBadge = previousBadge;
            this.conditionMode = conditionMode;
            this.conditions = conditions;
            this.lockedDialogue = lockedDialogue;
            this.enterDialogue = enterDialogue;
            this.entryOffset = entryOffset;
            this.exitDoorOffset = exitDoorOffset;
            this.connections = connections;
            this.blockingNpcPreset = blockingNpcPreset;
            this.staff = staff;
        }

        int interiorWidth(ServerLevel level) {
            int maximum = 0;
            for (InteriorModule module : modules) {
                ResourceLocation id = ResourceLocation.tryParse(module.structure);
                if (id == null) {
                    continue;
                }
                var template = level.getStructureManager().get(id);
                if (template.isPresent()) {
                    maximum = Math.max(maximum, module.position.x + template.orElseThrow().getSize(module.rotation).getX());
                }
            }
            return maximum;
        }
    }

}
