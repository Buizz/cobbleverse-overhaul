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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/** Creates isolated gym interiors and turns the RGS entrance into a data-driven door. */
final class GymInteriorSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceKey<Level> INTERIORS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "gym_interiors")
    );
    private static final String INTERACTION_COOLDOWN = "cobbleventureGymDoorCooldown";
    private static final int SLOT_SPACING = 256;
    private static final int SLOT_Y = 64;
    private static final BlockPoint DEFAULT_DOOR = new BlockPoint(12, 3, 3);
    private static final BlockPoint DEFAULT_OUTSIDE = new BlockPoint(12, 4, 1);
    private static final BlockPoint DEFAULT_ENTRY = new BlockPoint(12, 4, 5);
    private static final Map<String, GymConfig> GYMS = new LinkedHashMap<>();
    private static final Map<DoorKey, DoorTarget> DOORS = new HashMap<>();

    private GymInteriorSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(GymInteriorSystem::onRightClickBlock);
    }

    static void initialize(MinecraftServer server) {
        GYMS.clear();
        DOORS.clear();
        loadConfigs(server);
        if (GYMS.isEmpty()) {
            return;
        }
        ServerLevel interiors = server.getLevel(INTERIORS);
        if (interiors == null) {
            throw new IllegalStateException("Cobbleventure gym_interiors dimension is missing");
        }
        Set<Long> occupiedSlots = new HashSet<>();
        for (GymConfig gym : GYMS.values()) {
            BlockPos origin = instanceOrigin(gym.settlementId, occupiedSlots);
            gym.instanceOrigin = origin;
            placeInterior(interiors, gym);
        }
    }

    private static BlockPos instanceOrigin(String settlementId, Set<Long> occupiedSlots) {
        int hash = settlementId.hashCode();
        int slotX = Math.floorMod(hash, 2048);
        int slotZ = Math.floorMod(Integer.rotateLeft(hash, 13), 2048);
        while (!occupiedSlots.add(((long) slotX << 32) | (slotZ & 0xffffffffL))) {
            slotX = Math.floorMod(slotX + 1, 2048);
        }
        return new BlockPos(
            (slotX - 1024) * SLOT_SPACING, SLOT_Y,
            (slotZ - 1024) * SLOT_SPACING
        );
    }

    static void prepareExterior(
        ServerLevel level, String settlementId,
        CobbleventureBootstrap.BlockPoint structureOrigin
    ) {
        GymConfig gym = GYMS.get(settlementId);
        if (gym == null || gym.instanceOrigin == null) {
            return;
        }
        BlockPos origin = structureOrigin.toBlockPos();
        sanitizeTemplate(level, gym.exteriorStructure, origin);
        BlockPos door = origin.offset(gym.doorOffset.x, gym.doorOffset.y, gym.doorOffset.z);
        installDoor(level, door, gym.facing);
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
            gym.outsideOffset.x, gym.outsideOffset.y, gym.outsideOffset.z
        );
        registerDoor(level.getServer().getLevel(INTERIORS), exitDoor, new DoorTarget(
            level.dimension(), outside, List.of(), "all", List.of(), List.of()
        ));
    }

    private static void loadConfigs(MinecraftServer server) {
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
                GymConfig config = parseConfig(requiredString(root, "id"), gym);
                if (GYMS.putIfAbsent(config.settlementId, config) != null) {
                    throw new IllegalStateException("Duplicate gym settlement: " + config.settlementId);
                }
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Invalid gym configuration: " + entry.getKey(), error);
            }
        });
    }

    private static GymConfig parseConfig(String settlementId, JsonObject gym) {
        JsonObject entrance = gym.has("entrance") ? gym.getAsJsonObject("entrance") : null;
        JsonObject interior = gym.has("interior") ? gym.getAsJsonObject("interior") : null;
        String exteriorStructure = requiredString(gym, "structure");
        String interiorStructure = interior != null && interior.has("structure")
            ? requiredString(interior, "structure") : requiredString(gym, "structure");
        List<Condition> conditions = new ArrayList<>();
        if (entrance != null && entrance.has("conditions")) {
            for (JsonElement element : entrance.getAsJsonArray("conditions")) {
                conditions.add(parseCondition(element.getAsJsonObject()));
            }
        }
        String leaderNpc = nullableString(interior, "leader_npc");
        String leaderTrainerId = nullableString(gym, "leader_trainer_id");
        if (leaderNpc == null && leaderTrainerId != null) {
            String slug = leaderTrainerId.substring(
                Math.max(leaderTrainerId.lastIndexOf('/'), leaderTrainerId.lastIndexOf(':')) + 1
            );
            leaderNpc = "easy_npc:preset/encounter/" + slug + ".npc.snbt";
        }
        return new GymConfig(
            settlementId,
            exteriorStructure,
            interiorStructure,
            point(entrance, "door_offset", DEFAULT_DOOR),
            point(entrance, "outside_offset", DEFAULT_OUTSIDE),
            direction(optionalString(entrance, "facing", "north")),
            optionalString(entrance, "condition_mode", "all"),
            List.copyOf(conditions),
            strings(entrance, "locked_dialogue", List.of("문이 잠겨 있다.")),
            strings(entrance, "enter_dialogue", List.of()),
            point(interior, "entry_offset", DEFAULT_ENTRY),
            point(interior, "exit_door_offset", DEFAULT_DOOR),
            interior != null && interior.has("leader_offset")
                ? point(interior, "leader_offset", null) : null,
            leaderNpc
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
        ResourceLocation id = ResourceLocation.tryParse(gym.interiorStructure);
        var template = id == null ? java.util.Optional
            .<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(id);
        if (template.isEmpty()) {
            throw new IllegalStateException(
                "Gym interior structure is missing: " + gym.interiorStructure
            );
        }
        Vec3i size = template.orElseThrow().getSize();
        forceChunks(level, gym.instanceOrigin, size);
        BlockPos marker = gym.instanceOrigin.offset(0, -2, 0);
        BlockPos leaderMarker = gym.instanceOrigin.offset(1, -2, 0);
        if (!level.getBlockState(marker).is(Blocks.RESPAWN_ANCHOR)) {
            boolean placed = template.orElseThrow().placeInWorld(
                level, gym.instanceOrigin, gym.instanceOrigin,
                new StructurePlaceSettings(),
                RandomSource.create(level.getSeed() ^ gym.instanceOrigin.asLong()), 2
            );
            if (!placed) {
                throw new IllegalStateException("Gym interior placement failed: " + gym.settlementId);
            }
            sanitize(level, gym.instanceOrigin, size);
            BlockPos exitDoor = gym.instanceOrigin.offset(
                gym.exitDoorOffset.x, gym.exitDoorOffset.y, gym.exitDoorOffset.z
            );
            installDoor(level, exitDoor, gym.facing);
            level.setBlock(marker, Blocks.RESPAWN_ANCHOR.defaultBlockState(), 2);
            LOGGER.info(
                "Gym interior generated: settlement={}, structure={}, origin={}",
                gym.settlementId, gym.interiorStructure, gym.instanceOrigin
            );
        } else {
            BlockPos exitDoor = gym.instanceOrigin.offset(
                gym.exitDoorOffset.x, gym.exitDoorOffset.y, gym.exitDoorOffset.z
            );
            installDoor(level, exitDoor, gym.facing);
        }
        if (gym.leaderNpc != null && !level.getBlockState(leaderMarker).is(Blocks.LODESTONE)) {
            BlockPoint offset = gym.leaderOffset == null
                ? new BlockPoint(size.getX() / 2, 3, Math.max(5, size.getZ() - 5))
                : gym.leaderOffset;
            spawnNpc(level, gym, gym.instanceOrigin.offset(offset.x, offset.y, offset.z));
            level.setBlock(leaderMarker, Blocks.LODESTONE.defaultBlockState(), 2);
        }
    }

    private static void sanitizeTemplate(ServerLevel level, String structure, BlockPos origin) {
        ResourceLocation id = ResourceLocation.tryParse(structure);
        var template = id == null ? java.util.Optional
            .<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(id);
        template.ifPresent(value -> sanitize(level, origin, value.getSize()));
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

    private static void installDoor(ServerLevel level, BlockPos lower, Direction facing) {
        BlockState base = Blocks.IRON_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false);
        level.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    private static void registerDoor(ServerLevel level, BlockPos lower, DoorTarget target) {
        if (level == null) {
            return;
        }
        DOORS.put(new DoorKey(level.dimension(), lower.immutable()), target);
        DOORS.put(new DoorKey(level.dimension(), lower.above().immutable()), target);
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

    private static void spawnNpc(ServerLevel level, GymConfig gym, BlockPos position) {
        String command = "easy_npc preset import_new data " + gym.leaderNpc + " "
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
                LOGGER.warn("Gym leader NPC command returned no result: {}", gym.settlementId);
            }
        } catch (CommandSyntaxException error) {
            throw new IllegalStateException(
                "Gym leader NPC placement failed: " + gym.settlementId, error
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

    private static final class GymConfig {
        final String settlementId;
        final String exteriorStructure;
        final String interiorStructure;
        final BlockPoint doorOffset;
        final BlockPoint outsideOffset;
        final Direction facing;
        final String conditionMode;
        final List<Condition> conditions;
        final List<String> lockedDialogue;
        final List<String> enterDialogue;
        final BlockPoint entryOffset;
        final BlockPoint exitDoorOffset;
        final BlockPoint leaderOffset;
        final String leaderNpc;
        BlockPos instanceOrigin;

        GymConfig(
            String settlementId, String exteriorStructure, String interiorStructure,
            BlockPoint doorOffset,
            BlockPoint outsideOffset, Direction facing, String conditionMode,
            List<Condition> conditions, List<String> lockedDialogue,
            List<String> enterDialogue, BlockPoint entryOffset,
            BlockPoint exitDoorOffset, BlockPoint leaderOffset, String leaderNpc
        ) {
            this.settlementId = settlementId;
            this.exteriorStructure = exteriorStructure;
            this.interiorStructure = interiorStructure;
            this.doorOffset = doorOffset;
            this.outsideOffset = outsideOffset;
            this.facing = facing;
            this.conditionMode = conditionMode;
            this.conditions = conditions;
            this.lockedDialogue = lockedDialogue;
            this.enterDialogue = enterDialogue;
            this.entryOffset = entryOffset;
            this.exitDoorOffset = exitDoorOffset;
            this.leaderOffset = leaderOffset;
            this.leaderNpc = leaderNpc;
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
