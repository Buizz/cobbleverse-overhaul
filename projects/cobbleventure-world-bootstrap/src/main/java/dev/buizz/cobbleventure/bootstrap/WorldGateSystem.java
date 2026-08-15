package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexGrid;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import org.slf4j.Logger;

/** Places condition-aware gate objects declared on the hex world map. */
final class WorldGateSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DENY_COOLDOWN = "cobbleventureGateDenyCooldown";
    private static final Map<UUID, Vec3> LAST_POSITIONS = new HashMap<>();

    private WorldGateSystem() {
    }

    static List<Gate> parse(JsonArray objects) {
        List<Gate> gates = new ArrayList<>();
        for (JsonElement element : objects) {
            JsonObject value = element.getAsJsonObject();
            if (!"gate".equals(requiredString(value, "type"))) {
                continue;
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            JsonObject properties = value.getAsJsonObject("properties");
            List<Condition> conditions = new ArrayList<>();
            if (properties.has("conditions")) {
                for (JsonElement conditionElement : properties.getAsJsonArray("conditions")) {
                    conditions.add(parseCondition(conditionElement.getAsJsonObject()));
                }
            }
            gates.add(new Gate(
                requiredString(value, "id"),
                new HexCoord(
                    anchor.get("q").getAsInt(), anchor.get("r").getAsInt()
                ),
                nullableString(value, "resource"),
                value.has("rotation") ? value.get("rotation").getAsInt() : 0,
                optionalString(properties, "facing", "north"),
                optionalString(properties, "gate_mode", "classic"),
                optionalBoolean(properties, "building_enabled", true),
                optionalString(properties, "surrounding_type", "wall"),
                optionalString(properties, "wall_block", "minecraft:stone_bricks"),
                optionalString(properties, "tree_log", "minecraft:oak_log"),
                optionalString(properties, "tree_leaves", "minecraft:oak_leaves"),
                optionalInt(properties, "wall_thickness", 5),
                optionalInt(properties, "wall_height", 7),
                optionalInt(properties, "opening_width", 7),
                optionalInt(properties, "barrier_height", 24),
                optionalString(properties, "condition_mode", "all"),
                List.copyOf(conditions),
                optionalString(properties, "deny_message", "아직 이 관문을 통과할 수 없습니다."),
                nullableString(properties, "npc"),
                nullableString(properties, "destination_forest"),
                nullableString(properties, "destination_entrance")
            ));
        }
        return List.copyOf(gates);
    }

    private static Condition parseCondition(JsonObject value) {
        String type = requiredString(value, "type");
        return switch (type) {
            case "variable" -> new VariableCondition(
                optionalString(value, "source", "scoreboard"),
                requiredString(value, "key"),
                optionalString(value, "operator", ">="),
                value.get("value").getAsDouble()
            );
            case "item" -> new ItemCondition(
                requiredString(value, "item"), optionalInt(value, "count", 1),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            case "pokemon" -> new PokemonCondition(
                requiredString(value, "species"),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            default -> throw new IllegalStateException("Unsupported gate condition: " + type);
        };
    }

    static void placeAll(
        ServerLevel level, HexGrid grid, List<Gate> gates
    ) {
        for (Gate gate : gates) {
            place(level, grid, gate);
        }
    }

    private static void place(
        ServerLevel level, HexGrid grid, Gate gate
    ) {
        if (gate.gateMode().equals("system_only")) {
            return;
        }
        CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
        int centerY = groundY(level, center.x(), center.z());
        BlockPos marker = new BlockPos(
            center.x(), grid.origin().y() - 16, center.z()
        );
        if (level.getBlockState(marker).is(Blocks.RESPAWN_ANCHOR)) {
            return;
        }
        boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
        int halfLength = Math.max(16, grid.radius() - 3);
        int halfThickness = gate.wallThickness() / 2;
        int halfOpening = gate.openingWidth() / 2;
        if (gate.gateMode().equals("classic") && gate.surroundingType().equals("wall")) {
            placeWallSurroundings(level, gate, center, horizontal, halfLength, halfThickness, halfOpening);
        } else if (gate.gateMode().equals("classic") && gate.surroundingType().equals("trees")) {
            placeTreeSurroundings(level, gate, center, horizontal, halfLength, halfThickness, halfOpening);
        }
        if (gate.gateMode().equals("classic") && gate.buildingEnabled()) {
            placeStructure(level, gate, center, centerY);
        }
        if (gate.npc() != null) {
            spawnNpc(level, gate, center, centerY);
        }
        level.setBlock(marker, Blocks.RESPAWN_ANCHOR.defaultBlockState(), 2);
        LOGGER.info(
            "World gate generated: id={}, anchor={}, facing={}, building={}, surroundings={}",
            gate.id(), gate.anchor(), gate.facing(), gate.buildingEnabled(), gate.surroundingType()
        );
    }

    private static void placeWallSurroundings(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, int halfLength, int halfThickness, int halfOpening
    ) {
        BlockState wall = blockState(gate.wallBlock());
        for (int along = -halfLength; along <= halfLength; along++) {
            for (int across = -halfThickness; across <= halfThickness; across++) {
                int x = center.x() + (horizontal ? along : across);
                int z = center.z() + (horizontal ? across : along);
                int groundY = groundY(level, x, z);
                boolean opening = Math.abs(along) <= halfOpening;
                for (int height = 1; height <= gate.wallHeight(); height++) {
                    level.setBlock(new BlockPos(x, groundY + height, z),
                        opening ? Blocks.AIR.defaultBlockState() : wall, 2);
                }
                placeOverheadBarrier(level, x, z, groundY, gate.wallHeight(), gate.barrierHeight());
            }
        }
    }

    private static void placeTreeSurroundings(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, int halfLength, int halfThickness, int halfOpening
    ) {
        BlockState log = blockState(gate.treeLog());
        BlockState leaves = blockState(gate.treeLeaves());
        for (int along = -halfLength; along <= halfLength; along++) {
            for (int across = -halfThickness; across <= halfThickness; across++) {
                int x = center.x() + (horizontal ? along : across);
                int z = center.z() + (horizontal ? across : along);
                int groundY = groundY(level, x, z);
                boolean opening = Math.abs(along) <= halfOpening;
                for (int height = 1; height <= gate.wallHeight(); height++) {
                    BlockState state = opening ? Blocks.AIR.defaultBlockState() : leaves;
                    if (!opening && across == 0 && Math.floorMod(along, 4) == 0
                        && height < gate.wallHeight()) {
                        state = log;
                    }
                    level.setBlock(new BlockPos(x, groundY + height, z), state, 2);
                }
                placeOverheadBarrier(level, x, z, groundY, gate.wallHeight(), gate.barrierHeight());
            }
        }
    }

    private static void placeOverheadBarrier(
        ServerLevel level, int x, int z, int groundY, int visibleHeight, int barrierHeight
    ) {
        for (int height = visibleHeight + 1; height <= barrierHeight; height++) {
            level.setBlock(new BlockPos(x, groundY + height, z), Blocks.BARRIER.defaultBlockState(), 2);
        }
    }

    private static void placeStructure(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center, int groundY
    ) {
        if (gate.structure() == null) {
            LOGGER.error("Gate building is enabled but structure is missing: gate={}", gate.id());
            return;
        }
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        var template = structureId == null
            ? java.util.Optional.<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            LOGGER.error("Gate structure is missing: gate={}, structure={}", gate.id(), gate.structure());
            return;
        }
        Rotation rotation = rotation(gate.rotation());
        Vec3i size = template.orElseThrow().getSize(rotation);
        BlockPos origin = new BlockPos(
            center.x() - size.getX() / 2,
            groundY + 1,
            center.z() - size.getZ() / 2
        );
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        if (!template.orElseThrow().placeInWorld(
            level, origin, origin, settings,
            RandomSource.create(level.getSeed() ^ origin.asLong()), 2
        )) {
            LOGGER.error("Gate structure placement failed: gate={}, origin={}", gate.id(), origin);
        }
    }

    private static void spawnNpc(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center, int groundY
    ) {
        String command = "easy_npc preset import_new data " + gate.npc() + " "
            + center.x() + " " + (groundY + 1) + " " + center.z();
        try {
            int result = level.getServer().getCommands().getDispatcher().execute(
                command,
                level.getServer().createCommandSourceStack()
                    .withLevel(level).withPermission(4).withSuppressedOutput()
            );
            if (result == 0) {
                LOGGER.warn("Gate NPC command returned no result: gate={}, npc={}", gate.id(), gate.npc());
            }
        } catch (CommandSyntaxException error) {
            LOGGER.error("Gate NPC placement failed: gate={}, npc={}", gate.id(), gate.npc(), error);
        }
    }

    static void tick(
        ServerPlayer player, HexGrid grid,
        List<Gate> gates, long gameTime
    ) {
        Vec3 previous = LAST_POSITIONS.put(player.getUUID(), player.position());
        if (previous == null || player.isSpectator()) {
            return;
        }
        for (Gate gate : gates) {
            if (gate.allows(player)) {
                continue;
            }
            if (gate.gateMode().equals("npc_only")) {
                continue;
            }
            if (gate.gateMode().equals("system_only")) {
                if (grid.worldToHex(player.getX(), player.getZ()).equals(gate.anchor())) {
                    rejectFromSystemGate(player, grid, gate, previous, gameTime);
                    return;
                }
                continue;
            }
            CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
            boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
            double normal = horizontal ? player.getZ() - center.z() : player.getX() - center.x();
            double previousNormal = horizontal ? previous.z - center.z() : previous.x - center.x();
            double lateral = horizontal ? player.getX() - center.x() : player.getZ() - center.z();
            double limit = gate.wallThickness() / 2.0D + 1.25D;
            boolean crossed = normal * previousNormal <= 0.0D
                && Math.abs(normal - previousNormal) < 12.0D;
            if (Math.abs(lateral) > gate.openingWidth() / 2.0D + 1.5D
                || (!crossed && Math.abs(normal) > limit)) {
                continue;
            }
            double side = previousNormal == 0.0D
                ? (gate.facing().equals("north") || gate.facing().equals("west") ? -1.0D : 1.0D)
                : Math.signum(previousNormal);
            double safeNormal = side * (limit + 0.75D);
            double x = horizontal ? player.getX() : center.x() + safeNormal;
            double z = horizontal ? center.z() + safeNormal : player.getZ();
            player.teleportTo(player.serverLevel(), x, player.getY(), z, player.getYRot(), player.getXRot());
            LAST_POSITIONS.put(player.getUUID(), new Vec3(x, player.getY(), z));
            showDenyMessage(player, gate, gameTime);
            return;
        }
    }

    private static void rejectFromSystemGate(
        ServerPlayer player, HexGrid grid,
        Gate gate, Vec3 previous, long gameTime
    ) {
        double x = previous.x;
        double y = previous.y;
        double z = previous.z;
        if (grid.worldToHex(previous.x, previous.z).equals(gate.anchor())) {
            CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
            double dx = player.getX() - center.x();
            double dz = player.getZ() - center.z();
            double length = Math.hypot(dx, dz);
            if (length < 0.01D) {
                dx = 0.0D;
                dz = -1.0D;
                length = 1.0D;
            }
            double distance = grid.radius() + 3.0D;
            x = center.x() + dx / length * distance;
            z = center.z() + dz / length * distance;
            y = groundY(player.serverLevel(), (int) Math.floor(x), (int) Math.floor(z)) + 1.0D;
        }
        player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot());
        LAST_POSITIONS.put(player.getUUID(), new Vec3(x, y, z));
        showDenyMessage(player, gate, gameTime);
    }

    private static void showDenyMessage(ServerPlayer player, Gate gate, long gameTime) {
        if (player.getPersistentData().getLong(DENY_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(DENY_COOLDOWN, gameTime + 40L);
            player.sendSystemMessage(Component.literal(gate.denyMessage()), true);
        }
    }

    static void forget(ServerPlayer player) {
        LAST_POSITIONS.remove(player.getUUID());
    }

    static int teleportToGate(
        ServerLevel level,
        Iterable<? extends Entity> targets,
        HexGrid grid,
        List<Gate> gates,
        String gateId,
        String side
    ) {
        Gate gate = gates.stream().filter(value -> value.id().equals(gateId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown world gate: " + gateId));
        CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
        double distance = gate.wallThickness() / 2.0D + 3.0D;
        double directionX = switch (gate.facing()) {
            case "east" -> 1.0D;
            case "west" -> -1.0D;
            default -> 0.0D;
        };
        double directionZ = switch (gate.facing()) {
            case "south" -> 1.0D;
            case "north" -> -1.0D;
            default -> 0.0D;
        };
        double sign = side.equals("back") ? -1.0D : side.equals("center") ? 0.0D : 1.0D;
        int x = (int) Math.round(center.x() + directionX * distance * sign);
        int z = (int) Math.round(center.z() + directionZ * distance * sign);
        int y = groundY(level, x, z) + 1;
        int moved = 0;
        for (Entity target : targets) {
            target.teleportTo(x + 0.5D, y, z + 0.5D);
            if (target instanceof ServerPlayer player) {
                LAST_POSITIONS.put(player.getUUID(), player.position());
            }
            moved++;
        }
        return moved;
    }

    private static int groundY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static BlockState blockState(String id) {
        ResourceLocation resource = ResourceLocation.tryParse(id);
        if (resource == null || !BuiltInRegistries.BLOCK.containsKey(resource)) {
            throw new IllegalStateException("Unknown gate wall block: " + id);
        }
        return BuiltInRegistries.BLOCK.get(resource).defaultBlockState();
    }

    private static Rotation rotation(int value) {
        return switch (Math.floorMod(value, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || value.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("Gate field is required: " + key);
        }
        return value.get(key).getAsString();
    }

    private static String optionalString(JsonObject value, String key, String fallback) {
        return value.has(key) ? value.get(key).getAsString() : fallback;
    }

    private static String nullableString(JsonObject value, String key) {
        return value.has(key) && !value.get(key).getAsString().isBlank()
            ? value.get(key).getAsString() : null;
    }

    private static int optionalInt(JsonObject value, String key, int fallback) {
        return value.has(key) ? value.get(key).getAsInt() : fallback;
    }

    private static boolean optionalBoolean(JsonObject value, String key, boolean fallback) {
        return value.has(key) ? value.get(key).getAsBoolean() : fallback;
    }

    sealed interface Condition permits VariableCondition, ItemCondition, PokemonCondition {
        boolean matches(ServerPlayer player);
    }

    record VariableCondition(String source, String key, String operator, double value)
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

    record ItemCondition(String item, int count, boolean negate) implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            ResourceLocation id = ResourceLocation.tryParse(item);
            Item required = id == null ? null : BuiltInRegistries.ITEM.get(id);
            boolean present = required != null && player.getInventory().countItem(required) >= count;
            return negate != present;
        }
    }

    record PokemonCondition(String species, boolean negate) implements Condition {
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

    record Gate(
        String id,
        HexCoord anchor,
        String structure,
        int rotation,
        String facing,
        String gateMode,
        boolean buildingEnabled,
        String surroundingType,
        String wallBlock,
        String treeLog,
        String treeLeaves,
        int wallThickness,
        int wallHeight,
        int openingWidth,
        int barrierHeight,
        String conditionMode,
        List<Condition> conditions,
        String denyMessage,
        String npc,
        String destinationForest,
        String destinationEntrance
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
}
