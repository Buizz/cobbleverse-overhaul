package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
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
import net.minecraft.world.scores.Objective;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/** Creates isolated modular gym interiors and turns authored entrance anchors into data-driven doors. */
final class GymInteriorSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceKey<Level> INTERIORS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "gym_interiors")
    );
    private static final String INTERACTION_COOLDOWN = "cobbleventureGymDoorCooldown";
    private static final int INSTANCE_GAP = 128;
    private static final int SLOT_Y = 64;
    private static final BlockPoint DEFAULT_DOOR = new BlockPoint(12, 3, 3);
    private static final BlockPoint DEFAULT_OUTSIDE = new BlockPoint(12, 4, 1);
    private static final BlockPoint DEFAULT_ENTRY = new BlockPoint(12, 4, 5);
    private static final Map<String, GymConfig> GYMS = new LinkedHashMap<>();
    private static final Map<String, GymDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();

    private GymInteriorSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onRightClickBlock);
    }

    static void initialize(MinecraftServer server) {
        GYMS.clear();
        DEFINITIONS.clear();
        DOORS.clear();
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
        BlockPoint doorOffset = rotatePoint(
            gym.doorOffset, size.getX(), size.getZ(), exteriorRotation
        );
        BlockPoint outsideOffset = rotatePoint(
            gym.outsideOffset, size.getX(), size.getZ(), exteriorRotation
        );
        BlockPos door = origin.offset(doorOffset.x, doorOffset.y, doorOffset.z);
        BlockPos destination = gym.instanceOrigin.offset(
            gym.entryOffset.x, gym.entryOffset.y, gym.entryOffset.z
        );
        registerDoor(level, door, new DoorTarget(
            INTERIORS, destination, gym.conditions, gym.conditionMode,
            gym.lockedDialogue, gym.enterDialogue
        ));

        BlockPos exitDoor = gym.instanceOrigin.offset(
            gym.exitDoorOffset.x, gym.exitDoorOffset.y, gym.exitDoorOffset.z
        );
        BlockPos outside = origin.offset(
            outsideOffset.x, outsideOffset.y, outsideOffset.z
        );
        registerDoor(level.getServer().getLevel(INTERIORS), exitDoor, new DoorTarget(
            level.dimension(), outside, List.of(), "all", List.of(), List.of()
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
                requiredString(module, "id"), structure, modulePosition, moduleRotation
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
        return new GymDefinition(
            requiredString(gym, "id"), localizedName(gym.getAsJsonObject("display_name")),
            requiredString(gym, "theme"), requiredString(exterior, "structure"),
            clearVariable(leaderTrainerId), List.copyOf(modules), List.copyOf(staffMembers),
            accessPolicy(interior)
        );
    }

    private static AccessPolicy accessPolicy(JsonObject interior) {
        if (interior != null && interior.has("connections")) {
            for (JsonElement element : interior.getAsJsonArray("connections")) {
                JsonObject connection = element.getAsJsonObject();
                if (!requiredString(connection, "from").startsWith("exterior:")) {
                    continue;
                }
                List<Condition> conditions = new ArrayList<>();
                if (connection.has("conditions")) {
                    for (JsonElement condition : connection.getAsJsonArray("conditions")) {
                        conditions.add(parseCondition(condition.getAsJsonObject()));
                    }
                }
                return new AccessPolicy(
                    optionalString(connection, "condition_mode", "all"),
                    List.copyOf(conditions),
                    strings(connection, "locked_dialogue", List.of("문이 잠겨 있다.")),
                    strings(connection, "enter_dialogue", List.of())
                );
            }
        }
        return new AccessPolicy("all", List.of(), List.of("문이 잠겨 있다."), List.of());
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                variable.getBytes(StandardCharsets.UTF_8)
            );
            return "cvf_" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is unavailable", error);
        }
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
        String slug = trainerId.substring(Math.max(trainerId.lastIndexOf('/'), trainerId.lastIndexOf(':')) + 1);
        members.add(new GymStaffMember(role, "easy_npc:preset/encounter/" + slug + ".npc.snbt", offset));
    }

    private static ModuleMetadata readModuleMetadata(MinecraftServer server, String structure) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) {
            throw new IllegalStateException("Invalid gym interior structure: " + structure);
        }
        ResourceLocation metadataId = ResourceLocation.fromNamespaceAndPath(
            structureId.getNamespace(), "structure_metadata/" + structureId.getPath() + ".structure.json"
        );
        var resource = server.getResourceManager().getResource(metadataId);
        if (resource.isEmpty()) {
            return new ModuleMetadata(0, 0, Map.of());
        }
        try (Reader reader = resource.orElseThrow().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject interior = root.getAsJsonObject("interior");
            int width = interior == null ? 0 : interior.get("width").getAsInt();
            int depth = interior == null ? 0 : interior.get("depth").getAsInt();
            Map<String, BlockPoint> npcAnchors = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                if (!"npc_position".equals(optionalString(anchor, "type", ""))) {
                    continue;
                }
                String label = requiredString(anchor, "label");
                JsonArray point = anchor.getAsJsonArray("position");
                BlockPoint position = new BlockPoint(
                    point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()
                );
                if (npcAnchors.putIfAbsent(label, position) != null) {
                    throw new IllegalStateException("Duplicate NPC anchor in gym module: " + label);
                }
            }
            return new ModuleMetadata(width, depth, Map.copyOf(npcAnchors));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid gym module metadata: " + metadataId, error);
        }
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
        List<Condition> conditions = new ArrayList<>();
        if (entrance != null && entrance.has("conditions")) {
            for (JsonElement element : entrance.getAsJsonArray("conditions")) {
                conditions.add(parseCondition(element.getAsJsonObject()));
            }
        } else {
            conditions.addAll(definition.access.conditions);
        }
        return new GymConfig(
            settlementId,
            definition.displayName,
            definition.theme,
            flagObjective(definition.clearVariable),
            definition.exteriorStructure,
            definition.modules,
            point(entrance, "door_offset", DEFAULT_DOOR),
            point(entrance, "outside_offset", DEFAULT_OUTSIDE),
            direction(optionalString(entrance, "facing", "north")),
            optionalString(entrance, "condition_mode", definition.access.conditionMode),
            List.copyOf(conditions),
            strings(entrance, "locked_dialogue", definition.access.lockedDialogue),
            strings(entrance, "enter_dialogue", definition.access.enterDialogue),
            point(interior, "entry_offset", DEFAULT_ENTRY),
            point(interior, "exit_door_offset", DEFAULT_DOOR),
            definition.staff
        );
    }

    private static Condition parseCondition(JsonObject value) {
        return switch (requiredString(value, "type")) {
            case "variable" -> new VariableCondition(
                optionalString(value, "source", "scoreboard"),
                requiredString(value, "key"),
                optionalString(value, "operator", ">="),
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
                "Unsupported gym condition: " + requiredString(value, "type")
            );
        };
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
                boolean placed = template.orElseThrow().placeInWorld(
                    level, moduleOrigin, moduleOrigin,
                    new StructurePlaceSettings().setRotation(module.rotation),
                    RandomSource.create(level.getSeed() ^ moduleOrigin.asLong()), 2
                );
                if (!placed) {
                    throw new IllegalStateException("Gym interior module placement failed: " + module.structure);
                }
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
            sendDialogue(player, target.lockedDialogue);
            return;
        }
        sendDialogue(player, target.enterDialogue);
        ServerLevel destination = player.getServer().getLevel(target.dimension);
        if (destination == null) {
            player.sendSystemMessage(Component.literal("[체육관 문] 이동할 공간을 찾을 수 없습니다."));
            return;
        }
        player.teleportTo(
            destination,
            target.position.getX() + 0.5D,
            target.position.getY(),
            target.position.getZ() + 0.5D,
            player.getYRot(), player.getXRot()
        );
    }

    private static void sendDialogue(ServerPlayer player, List<String> lines) {
        for (String line : lines) {
            player.sendSystemMessage(Component.literal("[체육관 문] " + line));
        }
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
        List<Condition> conditions,
        String conditionMode,
        List<String> lockedDialogue,
        List<String> enterDialogue
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

    private record GymDefinition(
        String id, String displayName, String theme, String exteriorStructure,
        String clearVariable, List<InteriorModule> modules, List<GymStaffMember> staff,
        AccessPolicy access
    ) {}

    private record AccessPolicy(
        String conditionMode, List<Condition> conditions,
        List<String> lockedDialogue, List<String> enterDialogue
    ) {}

    record GymArrivalInfo(String displayName, String theme, boolean cleared) {}

    private record ExteriorPalette(BlockState primary, BlockState secondary, BlockState glass) {}

    private record ModuleMetadata(int width, int depth, Map<String, BlockPoint> npcAnchors) {}

    private record GymStaffMember(String role, String npcPreset, BlockPoint offset) {}

    private record InteriorModule(String id, String structure, BlockPoint position, Rotation rotation) {}

    private static final class GymConfig {
        final String settlementId;
        final String displayName;
        final String theme;
        final String clearObjective;
        final String exteriorStructure;
        final List<InteriorModule> modules;
        final BlockPoint doorOffset;
        final BlockPoint outsideOffset;
        final Direction facing;
        final String conditionMode;
        final List<Condition> conditions;
        final List<String> lockedDialogue;
        final List<String> enterDialogue;
        final BlockPoint entryOffset;
        final BlockPoint exitDoorOffset;
        final List<GymStaffMember> staff;
        BlockPos instanceOrigin;

        GymConfig(
            String settlementId, String displayName, String theme, String clearObjective,
            String exteriorStructure, List<InteriorModule> modules,
            BlockPoint doorOffset,
            BlockPoint outsideOffset, Direction facing, String conditionMode,
            List<Condition> conditions, List<String> lockedDialogue,
            List<String> enterDialogue, BlockPoint entryOffset,
            BlockPoint exitDoorOffset, List<GymStaffMember> staff
        ) {
            this.settlementId = settlementId;
            this.displayName = displayName;
            this.theme = theme;
            this.clearObjective = clearObjective;
            this.exteriorStructure = exteriorStructure;
            this.modules = modules;
            this.doorOffset = doorOffset;
            this.outsideOffset = outsideOffset;
            this.facing = facing;
            this.conditionMode = conditionMode;
            this.conditions = conditions;
            this.lockedDialogue = lockedDialogue;
            this.enterDialogue = enterDialogue;
            this.entryOffset = entryOffset;
            this.exitDoorOffset = exitDoorOffset;
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
}
