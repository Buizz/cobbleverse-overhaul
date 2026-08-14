package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/** Applies builder-authored anchors and building NPC assignments after template placement. */
final class BuildingRuntimeSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_building_runtime";
    private static final String INTERACTION_COOLDOWN = "cobbleventureBuildingDoorCooldown";
    private static final int SLOT_SPACING = 512;
    private static final int SLOT_Y = 64;
    private static final ResourceKey<Level> INTERIORS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "building_interiors")
    );
    private static final Map<String, StructureMetadata> METADATA = new LinkedHashMap<>();
    private static final Map<String, BuildingSettings> SETTINGS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();

    private BuildingRuntimeSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(BuildingRuntimeSystem::onRightClickBlock);
    }

    static void initialize(MinecraftServer server) {
        METADATA.clear();
        SETTINGS.clear();
        DOORS.clear();
        loadMetadata(server);
        loadSettings(server);
        if (!METADATA.isEmpty() && server.getLevel(INTERIORS) == null) {
            throw new IllegalStateException("Cobbleventure building_interiors dimension is missing");
        }
        LOGGER.info(
            "Building runtime loaded: metadata={}, configured={}",
            METADATA.size(), SETTINGS.size()
        );
    }

    static void onStructurePlaced(
        ServerLevel level, String structure, CobbleventureBootstrap.BlockPoint origin,
        String rotationName
    ) {
        StructureMetadata metadata = METADATA.get(structure);
        if (metadata == null) {
            return;
        }
        Rotation rotation = rotation(rotationName);
        String instanceKey = instanceKey(level, structure, origin.toBlockPos());
        BuildingSettings settings = SETTINGS.get(structure);
        applyFixedNpcs(
            level, metadata, origin.toBlockPos(), rotation, instanceKey,
            settings == null ? Map.of() : settings.fixedNpcs, "exterior"
        );
        if (settings != null && settings.noInteriorSpace) {
            return;
        }
        if (settings != null) {
            prepareConfiguredInteriors(
                level, structure, metadata, origin.toBlockPos(), rotation,
                instanceKey, settings
            );
        } else {
            prepareInterior(level, structure, metadata, origin.toBlockPos(), rotation, instanceKey);
        }
    }

    private static void loadMetadata(MinecraftServer server) {
        Map<ResourceLocation, Resource> resources = server.getResourceManager().listResources(
            "structure_metadata",
            location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".structure.json")
        );
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceLocation location = entry.getKey();
            String path = location.getPath()
                .substring("structure_metadata/".length())
                .replaceFirst("\\.structure\\.json$", "");
            String structure = location.getNamespace() + ":" + path;
            try (Reader reader = entry.getValue().openAsReader()) {
                METADATA.put(structure, parseMetadata(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Invalid building metadata: " + location, error);
            }
        });
    }

    private static StructureMetadata parseMetadata(JsonObject root) {
        List<Anchor> anchors = new ArrayList<>();
        if (root.has("anchors")) {
            for (JsonElement element : root.getAsJsonArray("anchors")) {
                JsonObject value = element.getAsJsonObject();
                String type = requiredString(value, "type");
                String id = value.has("label") ? value.get("label").getAsString()
                    : value.has("id") ? value.get("id").getAsString() : type;
                anchors.add(new Anchor(
                    id, type, position(value, "position", null),
                    position(value, "safe_spawn", null),
                    direction(value.has("door_facing")
                        ? value.get("door_facing").getAsString() : "north"),
                    value.has("seal_entry") && value.get("seal_entry").getAsBoolean()
                ));
            }
        }
        String interiorStructure = null;
        if (root.has("interior_structure")) {
            interiorStructure = requiredString(root, "interior_structure");
        }
        return new StructureMetadata(List.copyOf(anchors), interiorStructure);
    }

    private static void loadSettings(MinecraftServer server) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "building_settings.json"
        );
        Resource resource = server.getResourceManager().getResource(location).orElse(null);
        if (resource == null) {
            return;
        }
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject buildings = root.getAsJsonObject("buildings");
            if (buildings == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : buildings.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                Map<String, String> fixed = new LinkedHashMap<>();
                if (value.has("fixed_npcs")) {
                    for (Map.Entry<String, JsonElement> npc
                        : value.getAsJsonObject("fixed_npcs").entrySet()) {
                        fixed.put(npc.getKey(), npc.getValue().getAsString());
                    }
                }
                List<InteriorSetting> interiors = new ArrayList<>();
                if (value.has("interiors")) {
                    for (JsonElement interiorElement : value.getAsJsonArray("interiors")) {
                        JsonObject interior = interiorElement.getAsJsonObject();
                        interiors.add(new InteriorSetting(
                            requiredString(interior, "key"),
                            requiredString(interior, "structure")
                        ));
                    }
                }
                Map<String, RouteTarget> routes = new LinkedHashMap<>();
                if (value.has("door_routes")) {
                    for (Map.Entry<String, JsonElement> route
                        : value.getAsJsonObject("door_routes").entrySet()) {
                        JsonObject target = route.getValue().getAsJsonObject();
                        List<Condition> conditions = new ArrayList<>();
                        if (target.has("conditions")) {
                            for (JsonElement condition : target.getAsJsonArray("conditions")) {
                                conditions.add(parseCondition(condition.getAsJsonObject()));
                            }
                        }
                        routes.put(route.getKey(), new RouteTarget(
                            requiredString(target, "space"),
                            target.has("door") ? requiredString(target, "door")
                                : requiredString(target, "arrival"),
                            target.has("condition_mode")
                                ? target.get("condition_mode").getAsString() : "all",
                            List.copyOf(conditions),
                            strings(target, "locked_dialogue", List.of("문이 잠겨 있다.")),
                            strings(target, "enter_dialogue", List.of())
                        ));
                    }
                }
                SETTINGS.put(entry.getKey(), new BuildingSettings(
                    value.has("placement_y_offset")
                        ? value.get("placement_y_offset").getAsInt() : 0,
                    value.has("no_interior_space")
                        && value.get("no_interior_space").getAsBoolean(),
                    Map.copyOf(fixed),
                    value.has("citizen_placement_allowed")
                        ? value.get("citizen_placement_allowed").getAsBoolean()
                        : value.has("random_citizen_eligible")
                            && value.get("random_citizen_eligible").getAsBoolean(),
                    List.copyOf(interiors), Map.copyOf(routes)
                ));
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid building settings: " + location, error);
        }
    }

    private static Condition parseCondition(JsonObject value) {
        return switch (requiredString(value, "type")) {
            case "variable" -> new VariableCondition(
                value.has("source") ? value.get("source").getAsString() : "scoreboard",
                requiredString(value, "key"),
                value.has("operator") ? value.get("operator").getAsString() : ">=",
                value.get("value").getAsDouble()
            );
            case "item" -> new ItemCondition(
                requiredString(value, "item"),
                value.has("count") ? value.get("count").getAsInt() : 1,
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            case "pokemon" -> new PokemonCondition(
                requiredString(value, "species"),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            default -> throw new IllegalStateException(
                "Unsupported building door condition: " + requiredString(value, "type")
            );
        };
    }

    private static List<String> strings(
        JsonObject parent, String key, List<String> fallback
    ) {
        if (!parent.has(key)) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : parent.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    static int placementYOffset(String structure) {
        BuildingSettings settings = SETTINGS.get(structure);
        return settings == null ? 0 : settings.placementYOffset;
    }

    private static void applyFixedNpcs(
        ServerLevel level, StructureMetadata metadata,
        BlockPos origin, Rotation rotation, String instanceKey,
        Map<String, String> fixedNpcs, String spaceKey
    ) {
        if (fixedNpcs.isEmpty()) {
            return;
        }
        RuntimeData data = data(level.getServer());
        for (Anchor anchor : metadata.anchors) {
            if (!anchor.type.equals("npc_position")) {
                continue;
            }
            String scoped = spaceKey + ":" + anchor.id;
            String npc = fixedNpcs.get(scoped);
            if (npc == null && spaceKey.equals("exterior")) {
                npc = fixedNpcs.get(anchor.id);
            }
            String spawnKey = instanceKey + "|npc|" + scoped;
            if (npc == null || data.hasSpawned(spawnKey)) {
                continue;
            }
            BlockPos position = transform(origin, anchor.position, rotation);
            if (spawnNpc(level, npc, position)) {
                data.markSpawned(spawnKey);
            }
        }
    }

    private static void prepareInterior(
        ServerLevel exterior, String exteriorStructure, StructureMetadata exteriorMetadata,
        BlockPos exteriorOrigin, Rotation exteriorRotation, String instanceKey
    ) {
        Anchor entry = exteriorMetadata.first("interior_entry");
        if (entry == null) {
            return;
        }
        String interiorStructure = exteriorMetadata.interiorStructure;
        if (interiorStructure == null) {
            String name = exteriorStructure.substring(exteriorStructure.lastIndexOf('/') + 1);
            interiorStructure = "cobbleventure:interiors/" + name;
        }
        StructureMetadata interiorMetadata = METADATA.get(interiorStructure);
        if (interiorMetadata == null) {
            LOGGER.warn("Interior metadata is missing for {}", exteriorStructure);
            return;
        }
        ServerLevel interiors = exterior.getServer().getLevel(INTERIORS);
        if (interiors == null) {
            return;
        }
        ResourceLocation interiorId = ResourceLocation.tryParse(interiorStructure);
        var template = interiorId == null ? java.util.Optional
            .<StructureTemplate>empty() : interiors.getStructureManager().get(interiorId);
        if (template.isEmpty()) {
            LOGGER.warn("Interior structure is missing: {}", interiorStructure);
            return;
        }

        BlockPos interiorOrigin = instanceOrigin(instanceKey);
        RuntimeData data = data(exterior.getServer());
        String preparedKey = instanceKey + "|interior";
        if (!data.hasPrepared(preparedKey)) {
            forceChunks(interiors, interiorOrigin, template.orElseThrow().getSize());
            boolean placed = template.orElseThrow().placeInWorld(
                interiors, interiorOrigin, interiorOrigin, new StructurePlaceSettings(),
                RandomSource.create(interiors.getSeed() ^ interiorOrigin.asLong()), 2
            );
            if (!placed) {
                LOGGER.error("Interior placement failed: structure={}, instance={}", interiorStructure, instanceKey);
                return;
            }
            data.markPrepared(preparedKey);
        }
        applyFixedNpcs(
            interiors, interiorMetadata, interiorOrigin,
            Rotation.NONE, instanceKey + "|inside",
            SETTINGS.getOrDefault(exteriorStructure, BuildingSettings.EMPTY).fixedNpcs,
            "interior"
        );

        Anchor interiorSpawn = interiorMetadata.first("interior_spawn");
        Anchor exit = interiorMetadata.first("interior_exit");
        if (exit == null) {
            LOGGER.warn("Interior exit anchor is missing: {}", interiorStructure);
            return;
        }
        BlockPos entryDoor = transform(exteriorOrigin, entry.position, exteriorRotation);
        net.minecraft.core.Direction entryFacing = exteriorRotation.rotate(entry.facing);
        if (entry.sealOpening) {
            sealDoorwayOpening(exterior, entryDoor, entryFacing);
        }
        installDoorIfMissing(exterior, entryDoor, entryFacing);
        BlockPos outside = entry.safeSpawn == null ? entryDoor : transform(
            exteriorOrigin, entry.safeSpawn, exteriorRotation
        );
        BlockPos exitDoor = interiorOrigin.offset(exit.position);
        installDoorIfMissing(interiors, exitDoor, exit.facing);
        BlockPos inside = interiorSpawn != null ? interiorOrigin.offset(interiorSpawn.position)
            : exit.safeSpawn != null ? interiorOrigin.offset(exit.safeSpawn) : exitDoor;
        registerDoor(exterior, entryDoor, new DoorTarget(
            INTERIORS, inside, List.of(), "all", List.of(), List.of()
        ));
        registerDoor(interiors, exitDoor, new DoorTarget(
            exterior.dimension(), outside, List.of(), "all", List.of(), List.of()
        ));
    }

    private static void prepareConfiguredInteriors(
        ServerLevel exterior, String exteriorStructure, StructureMetadata exteriorMetadata,
        BlockPos exteriorOrigin, Rotation exteriorRotation, String instanceKey,
        BuildingSettings settings
    ) {
        ServerLevel interiorsLevel = exterior.getServer().getLevel(INTERIORS);
        if (interiorsLevel == null) {
            return;
        }
        Map<String, SpaceInstance> spaces = new LinkedHashMap<>();
        spaces.put("exterior", new SpaceInstance(
            exterior, exteriorOrigin, exteriorRotation, exteriorMetadata
        ));
        BlockPos base = instanceOrigin(instanceKey);
        RuntimeData runtime = data(exterior.getServer());
        int index = 0;
        for (InteriorSetting interior : settings.interiors) {
            StructureMetadata metadata = METADATA.get(interior.structure);
            if (metadata == null) {
                LOGGER.warn("Configured interior metadata is missing: {}", interior.structure);
                continue;
            }
            ResourceLocation structureId = ResourceLocation.tryParse(interior.structure);
            var template = structureId == null ? java.util.Optional.<StructureTemplate>empty()
                : interiorsLevel.getStructureManager().get(structureId);
            if (template.isEmpty()) {
                LOGGER.warn("Configured interior structure is missing: {}", interior.structure);
                continue;
            }
            BlockPos origin = base.offset(
                (index % 4) * 128, placementYOffset(interior.structure), (index / 4) * 128
            );
            String preparedKey = instanceKey + "|space|" + interior.key;
            if (!runtime.hasPrepared(preparedKey)) {
                forceChunks(interiorsLevel, origin, template.orElseThrow().getSize());
                boolean placed = template.orElseThrow().placeInWorld(
                    interiorsLevel, origin, origin, new StructurePlaceSettings(),
                    RandomSource.create(interiorsLevel.getSeed() ^ origin.asLong()), 2
                );
                if (!placed) {
                    LOGGER.error(
                        "Configured interior placement failed: structure={}, instance={}",
                        interior.structure, instanceKey
                    );
                    index++;
                    continue;
                }
                runtime.markPrepared(preparedKey);
            }
            spaces.put(interior.key, new SpaceInstance(
                interiorsLevel, origin, Rotation.NONE, metadata
            ));
            applyFixedNpcs(
                interiorsLevel, metadata, origin, Rotation.NONE,
                instanceKey + "|" + interior.key, settings.fixedNpcs, interior.key
            );
            index++;
        }

        for (Map.Entry<String, RouteTarget> route : settings.routes.entrySet()) {
            int separator = route.getKey().indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String sourceSpaceKey = route.getKey().substring(0, separator);
            String sourceDoorId = route.getKey().substring(separator + 1);
            SpaceInstance sourceSpace = spaces.get(sourceSpaceKey);
            SpaceInstance targetSpace = spaces.get(route.getValue().space);
            if (sourceSpace == null || targetSpace == null) {
                LOGGER.warn("Building route references an unavailable space: {}", route.getKey());
                continue;
            }
            Anchor sourceDoor = sourceSpace.metadata.namedDoor(sourceDoorId);
            Anchor targetDoor = targetSpace.metadata.namedDoor(route.getValue().door);
            if (sourceDoor == null || targetDoor == null) {
                LOGGER.warn("Building route references a missing door: {}", route.getKey());
                continue;
            }
            BlockPos door = transform(
                sourceSpace.origin, sourceDoor.position, sourceSpace.rotation
            );
            BlockPos targetDoorPosition = transform(
                targetSpace.origin, targetDoor.position, targetSpace.rotation
            );
            BlockPos destination = transform(
                targetSpace.origin,
                targetDoor.safeSpawn == null ? targetDoor.position : targetDoor.safeSpawn,
                targetSpace.rotation
            );
            BlockPos reverseDestination = transform(
                sourceSpace.origin,
                sourceDoor.safeSpawn == null ? sourceDoor.position : sourceDoor.safeSpawn,
                sourceSpace.rotation
            );
            registerDoor(
                sourceSpace.level, door,
                new DoorTarget(
                    targetSpace.level.dimension(), destination,
                    route.getValue().conditions, route.getValue().conditionMode,
                    route.getValue().lockedDialogue, route.getValue().enterDialogue
                )
            );
            registerDoor(
                targetSpace.level, targetDoorPosition,
                new DoorTarget(
                    sourceSpace.level.dimension(), reverseDestination,
                    List.of(), "all", List.of(), List.of()
                )
            );
        }
    }

    private static boolean spawnNpc(ServerLevel level, String npcId, BlockPos position) {
        String slug = npcId.substring(Math.max(npcId.lastIndexOf('/'), npcId.lastIndexOf(':')) + 1);
        String preset = "easy_npc:preset/encounter/" + slug + ".npc.snbt";
        String command = "easy_npc preset import_new data " + preset + " "
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
                LOGGER.warn("Building NPC command returned no result: npc={}, position={}", npcId, position);
            }
            return result != 0;
        } catch (CommandSyntaxException error) {
            LOGGER.error("Building NPC placement failed: npc={}, position={}", npcId, position, error);
            return false;
        }
    }

    private static void registerDoor(ServerLevel level, BlockPos lower, DoorTarget target) {
        registerDoorBlocks(level, lower, target);
        BlockPos paired = pairedDoorPosition(level, lower);
        if (paired != null) {
            registerDoorBlocks(level, paired, target);
        }
    }

    private static void registerDoorBlocks(ServerLevel level, BlockPos lower, DoorTarget target) {
        DOORS.put(new DoorKey(level.dimension(), lower.immutable()), target);
        DOORS.put(new DoorKey(level.dimension(), lower.above().immutable()), target);
    }

    private static BlockPos pairedDoorPosition(ServerLevel level, BlockPos lower) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return null;
        }
        net.minecraft.core.Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        for (net.minecraft.core.Direction side
            : List.of(facing.getClockWise(), facing.getCounterClockWise())) {
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

    private static void installDoorIfMissing(
        ServerLevel level, BlockPos lower, net.minecraft.core.Direction facing
    ) {
        if (level.getBlockState(lower).getBlock() instanceof DoorBlock) {
            return;
        }
        if (!level.getBlockState(lower).canBeReplaced()
            || !level.getBlockState(lower.above()).canBeReplaced()) {
            LOGGER.warn("Building entrance is blocked and cannot receive a door: {}", lower);
            return;
        }
        BlockState base = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false);
        level.setBlock(
            lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3
        );
        level.setBlock(
            lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3
        );
    }

    private static void sealDoorwayOpening(
        ServerLevel level, BlockPos lower, net.minecraft.core.Direction facing
    ) {
        net.minecraft.core.Direction lateral = facing.getClockWise();
        for (int side : new int[] {-1, 1}) {
            BlockPos column = lower.relative(lateral, side);
            for (int height = 0; height <= 2; height++) {
                BlockPos position = column.above(height);
                if (level.getBlockState(position).canBeReplaced()) {
                    level.setBlock(position, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                }
            }
        }
        BlockPos header = lower.above(2);
        if (level.getBlockState(header).canBeReplaced()) {
            level.setBlock(header, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        }
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
            sendDialogue(player, target.lockedDialogue);
            return;
        }
        sendDialogue(player, target.enterDialogue);
        ServerLevel destination = player.getServer().getLevel(target.dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal("[건물 문] 이동할 공간을 찾을 수 없습니다."));
            return;
        }
        player.teleportTo(
            destination,
            target.position.getX() + 0.5D, target.position.getY(), target.position.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
    }

    private static void sendDialogue(ServerPlayer player, List<String> lines) {
        for (String line : lines) {
            player.sendSystemMessage(Component.literal("[건물 문] " + line));
        }
    }

    private static BlockPos transform(BlockPos origin, BlockPos local, Rotation rotation) {
        return origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    private static Rotation rotation(String value) {
        return switch (value) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static net.minecraft.core.Direction direction(String value) {
        return switch (value) {
            case "east" -> net.minecraft.core.Direction.EAST;
            case "south" -> net.minecraft.core.Direction.SOUTH;
            case "west" -> net.minecraft.core.Direction.WEST;
            default -> net.minecraft.core.Direction.NORTH;
        };
    }

    private static BlockPos instanceOrigin(String key) {
        int hash = key.hashCode();
        int x = Math.floorMod(hash, 4096) - 2048;
        int z = Math.floorMod(Integer.rotateLeft(hash, 13), 4096) - 2048;
        return new BlockPos(x * SLOT_SPACING, SLOT_Y, z * SLOT_SPACING);
    }

    private static String instanceKey(ServerLevel level, String structure, BlockPos origin) {
        return level.dimension().location() + "|" + structure + "|"
            + origin.getX() + "," + origin.getY() + "," + origin.getZ();
    }

    private static void forceChunks(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = origin.getX() >> 4; x <= (origin.getX() + size.getX()) >> 4; x++) {
            for (int z = origin.getZ() >> 4; z <= (origin.getZ() + size.getZ()) >> 4; z++) {
                level.getChunk(x, z);
            }
        }
    }

    private static BlockPos position(JsonObject value, String key, BlockPos fallback) {
        if (!value.has(key)) {
            return fallback;
        }
        var array = value.getAsJsonArray(key);
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("Building metadata field is required: " + key);
        }
        return value.get(key).getAsString();
    }

    private static RuntimeData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(RuntimeData::new, RuntimeData::load), DATA_FILE
        );
    }

    private record Anchor(
        String id, String type, BlockPos position, BlockPos safeSpawn,
        net.minecraft.core.Direction facing, boolean sealOpening
    ) {
    }

    private record StructureMetadata(List<Anchor> anchors, String interiorStructure) {
        Anchor first(String type) {
            return anchors.stream().filter(anchor -> anchor.type.equals(type)).findFirst().orElse(null);
        }

        Anchor namedDoor(String id) {
            return anchors.stream().filter(anchor -> anchor.id.equals(id)
                && Set.of("door", "interior_entry", "interior_exit").contains(anchor.type))
                .findFirst().orElse(null);
        }

    }

    private record InteriorSetting(String key, String structure) {
    }

    private record RouteTarget(
        String space, String door, String conditionMode, List<Condition> conditions,
        List<String> lockedDialogue, List<String> enterDialogue
    ) {
    }

    private record SpaceInstance(
        ServerLevel level, BlockPos origin, Rotation rotation, StructureMetadata metadata
    ) {
    }

    private record BuildingSettings(
        int placementYOffset, boolean noInteriorSpace,
        Map<String, String> fixedNpcs, boolean citizenPlacementAllowed,
        List<InteriorSetting> interiors, Map<String, RouteTarget> routes
    ) {
        private static final BuildingSettings EMPTY = new BuildingSettings(
            0, false, Map.of(), false, List.of(), Map.of()
        );
    }

    private record DoorKey(ResourceKey<Level> dimension, BlockPos position) {
    }

    private record DoorTarget(
        ResourceKey<Level> dimension, BlockPos position,
        List<Condition> conditions, String conditionMode,
        List<String> lockedDialogue, List<String> enterDialogue
    ) {
        boolean allows(ServerPlayer player) {
            if (conditions.isEmpty()) {
                return true;
            }
            return conditionMode.equals("any")
                ? conditions.stream().anyMatch(condition -> condition.matches(player))
                : conditions.stream().allMatch(condition -> condition.matches(player));
        }
    }

    private sealed interface Condition permits VariableCondition, ItemCondition, PokemonCondition {
        boolean matches(ServerPlayer player);
    }

    private record VariableCondition(String source, String key, String operator, double value)
        implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            double actual;
            if (source.equals("persistent_data")) {
                actual = player.getPersistentData().getDouble(key);
            } else {
                Objective objective = player.getScoreboard().getObjective(key);
                actual = objective == null ? 0.0D
                    : player.getScoreboard().getOrCreatePlayerScore(player, objective).get();
            }
            return switch (operator) {
                case "==" -> actual == value;
                case "!=" -> actual != value;
                case ">" -> actual > value;
                case "<" -> actual < value;
                case "<=" -> actual <= value;
                default -> actual >= value;
            };
        }
    }

    private record ItemCondition(String item, int count, boolean negate) implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            ResourceLocation id = ResourceLocation.tryParse(item);
            Item required = id == null ? null : BuiltInRegistries.ITEM.get(id);
            boolean present = required != null && player.getInventory().countItem(required) >= count;
            return negate != present;
        }
    }

    private record PokemonCondition(String species, boolean negate) implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            boolean present = false;
            for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                if (pokemon.getSpecies().getResourceIdentifier().toString().equals(species)) {
                    present = true;
                    break;
                }
            }
            return negate != present;
        }
    }

    static final class RuntimeData extends SavedData {
        private final Set<String> spawned = new HashSet<>();
        private final Set<String> prepared = new HashSet<>();

        static RuntimeData load(CompoundTag tag, HolderLookup.Provider registries) {
            RuntimeData data = new RuntimeData();
            readSet(tag.getString("spawned"), data.spawned);
            readSet(tag.getString("prepared"), data.prepared);
            return data;
        }

        boolean hasSpawned(String key) {
            return spawned.contains(key);
        }

        void markSpawned(String key) {
            if (spawned.add(key)) {
                setDirty();
            }
        }

        boolean hasPrepared(String key) {
            return prepared.contains(key);
        }

        void markPrepared(String key) {
            if (prepared.add(key)) {
                setDirty();
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putString("spawned", String.join("\n", spawned));
            tag.putString("prepared", String.join("\n", prepared));
            return tag;
        }

        private static void readSet(String serialized, Set<String> target) {
            if (!serialized.isBlank()) {
                target.addAll(List.of(serialized.split("\\n")));
            }
        }
    }
}
