package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(CobbleventureBootstrap.MOD_ID)
public final class CobbleventureBootstrap {
    public static final String MOD_ID = "cobbleventure_bootstrap";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_world_bootstrap";
    private static final int EXPECTED_SURFACE_Y = 69;
    private static final int MAP_VERSION = 1;
    private static final String STARTER_SETTLEMENT = "cobbleventure:settlement/starter_town";
    private static final String INTEGRATION_TEST_PROPERTY = "cobbleventure.testStarterTown";
    private static final String PLAYER_STARTED = "cobbleventureGenerationOneStarted";
    private static final ResourceKey<Level> GENERATION_ONE =
        ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "generation_1")
        );
    private static final ResourceKey<net.minecraft.world.level.biome.Biome> STARTER_BIOME =
        ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "starter_plains")
        );

    public CobbleventureBootstrap(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            throw new IllegalStateException("Cobbleventure generation_1 dimension is missing");
        }

        BlockPos surface = surfacePosition(level, 0, 0);
        if (!level.getBiome(surface).is(STARTER_BIOME)) {
            throw new IllegalStateException("Cobbleventure starter_plains biome is missing at spawn");
        }
        if (surface.getY() != EXPECTED_SURFACE_Y) {
            throw new IllegalStateException(
                "Cobbleventure generation_1 surface height must be "
                    + EXPECTED_SURFACE_Y + ", but was " + surface.getY()
            );
        }
        boolean safeFoundation = true;
        for (int y = 55; y <= 64; y++) {
            safeFoundation &= level.getBlockState(new BlockPos(0, y, 0)).is(Blocks.BEDROCK);
        }
        if (!level.getBlockState(new BlockPos(0, 68, 0)).is(Blocks.GRASS_BLOCK)
            || !safeFoundation
            || !level.getBlockState(new BlockPos(0, 54, 0)).isAir()) {
            throw new IllegalStateException(
                "Cobbleventure generation_1 must have grass over ten bedrock layers with empty space below"
            );
        }
        LOGGER.info(
            "Cobbleventure generation_1 ready: biome={}, surfaceY={}",
            STARTER_BIOME.location(),
            surface.getY()
        );

        if (Boolean.getBoolean(INTEGRATION_TEST_PROPERTY)) {
            SettlementPlan starter = loadSettlementPlans(level).get(STARTER_SETTLEMENT);
            if (starter == null || !placeTown(level, starter)) {
                throw new IllegalStateException("Cobbleventure starter town integration placement failed");
            }
            LOGGER.info("Cobbleventure starter town integration placement succeeded at {}", starter.center());
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ServerLevel overworld = player.getServer().overworld();
        ServerLevel generationOne = player.getServer().getLevel(GENERATION_ONE);
        if (generationOne == null) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] generation_1 전용 차원을 불러오지 못했습니다."
            ));
            return;
        }

        BootstrapSavedData data = overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (!data.isComplete(MAP_VERSION)) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 전용 시작 바이옴과 마을을 준비하고 있습니다..."
            ));
            if (!initializeWorld(generationOne, player, data)) {
                return;
            }
        }

        if (!player.getPersistentData().getBoolean(PLAYER_STARTED)) {
            movePlayerToStart(player, generationOne, data.spawnPos());
        }
    }

    private static boolean initializeWorld(
        ServerLevel level,
        ServerPlayer firstPlayer,
        BootstrapSavedData data
    ) {
        Map<String, SettlementPlan> settlements;
        try {
            settlements = loadSettlementPlans(level);
        } catch (RuntimeException error) {
            LOGGER.error("Settlement map data could not be loaded", error);
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 마을 지도 데이터를 읽지 못했습니다. 서버 로그를 확인하세요."
            ));
            return false;
        }
        SettlementPlan starter = settlements.get(STARTER_SETTLEMENT);
        if (starter == null) {
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 시작 마을 데이터가 없습니다."
            ));
            return false;
        }
        try {
            drawSettlementMap(level, settlements);
        } catch (RuntimeException error) {
            LOGGER.error("Settlement map drawing failed", error);
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 마을 지도 생성에 실패했습니다. 서버 로그를 확인하세요."
            ));
            return false;
        }
        BlockPos spawnPos = surfacePosition(level, starter.playerSpawn().x(), starter.playerSpawn().z());
        BlockPos villagePos = surfacePosition(level, starter.structurePoint().x(), starter.structurePoint().z());
        level.setDefaultSpawnPos(spawnPos, 0.0F);
        for (SettlementPlan settlement : settlements.values()) {
            if (settlement.enabled() && !placeTown(level, settlement)) {
                firstPlayer.sendSystemMessage(Component.literal(
                    "[Cobbleventure] 마을 구조물 배치에 실패했습니다: " + settlement.id()
                ));
                return false;
            }
        }

        data.complete(spawnPos, villagePos, MAP_VERSION);
        firstPlayer.sendSystemMessage(Component.literal(
            "[Cobbleventure] 마을 데이터로 1세대 시작 지역과 연결 통로를 생성했습니다."
        ));
        return true;
    }

    private static boolean placeTown(ServerLevel level, SettlementPlan settlement) {
        BlockPos villagePos = surfacePosition(
            level, settlement.structurePoint().x(), settlement.structurePoint().z()
        );
        level.getChunk(villagePos);
        try {
            int placed = level.getServer().getCommands().getDispatcher().execute(
                "place structure " + settlement.structure() + " ~ ~ ~",
                level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(Vec3.atLowerCornerOf(villagePos))
                .withPermission(4)
                .withSuppressedOutput()
            );
            if (placed != 0) {
                return true;
            }
        } catch (CommandSyntaxException error) {
            LOGGER.error(
                "Town command failed for {} in {} at {} (biome={}): {}",
                settlement.id(),
                level.dimension().location(),
                villagePos,
                level.getBiome(villagePos).unwrapKey().map(ResourceKey::location).orElse(null),
                error.getRawMessage().getString()
            );
            return false;
        }
        LOGGER.error(
            "Town placement returned 0 for {} in {} at {} (biome={})",
            settlement.id(),
            level.dimension().location(),
            villagePos,
            level.getBiome(villagePos).unwrapKey().map(ResourceKey::location).orElse(null)
        );
        return false;
    }

    private static void loadBounds(ServerLevel level, Bounds bounds) {
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static Map<String, SettlementPlan> loadSettlementPlans(ServerLevel level) {
        Map<String, SettlementPlan> plans = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> resources = level.getServer().getResourceManager().listResources(
            "settlements",
            location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".json")
        );
        resources.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> {
                try (Reader reader = entry.getValue().openAsReader()) {
                    SettlementPlan plan = parseSettlement(JsonParser.parseReader(reader).getAsJsonObject());
                    if (plans.putIfAbsent(plan.id(), plan) != null) {
                        throw new IllegalStateException("Duplicate settlement id: " + plan.id());
                    }
                } catch (IOException | RuntimeException error) {
                    throw new IllegalStateException("Invalid settlement resource: " + entry.getKey(), error);
                }
            });
        if (plans.isEmpty()) {
            throw new IllegalStateException("No packaged settlement data was found");
        }
        return plans;
    }

    private static SettlementPlan parseSettlement(JsonObject root) {
        String id = requiredString(root, "id");
        boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
        JsonObject boundsJson = root.getAsJsonObject("bounds");
        Bounds bounds = new Bounds(
            boundsJson.get("min_x").getAsInt(),
            boundsJson.get("min_z").getAsInt(),
            boundsJson.get("max_x").getAsInt(),
            boundsJson.get("max_z").getAsInt()
        );
        JsonObject centerJson = root.getAsJsonObject("center");
        Point center = pointFrom(centerJson);
        JsonObject anchors = root.getAsJsonObject("anchors");
        Point structurePoint = anchors.has("town_square")
            ? pointFrom(anchors.getAsJsonObject("town_square"))
            : center;
        Point playerSpawn = anchors.has("player_spawn")
            ? pointFrom(anchors.getAsJsonObject("player_spawn"))
            : center;
        String structure = requiredString(root.getAsJsonObject("structure_profile"), "structure");
        JsonObject layout = root.getAsJsonObject("biome_layout");
        List<BiomeZone> zones = new ArrayList<>();
        for (JsonElement element : layout.getAsJsonArray("zones")) {
            JsonObject zone = element.getAsJsonObject();
            zones.add(new BiomeZone(
                requiredString(zone, "biome"),
                zone.get("size_blocks").getAsInt(),
                requiredString(zone, "placement"),
                zone.get("weight").getAsInt()
            ));
        }
        JsonObject boundaryJson = layout.getAsJsonObject("boundary");
        Boundary boundary = new Boundary(
            requiredString(boundaryJson, "profile"),
            boundaryJson.get("width").getAsInt(),
            boundaryJson.get("wall_height").getAsInt(),
            boundaryJson.get("wall_thickness").getAsInt()
        );
        List<Connection> connections = new ArrayList<>();
        JsonArray connectionArray = root.getAsJsonArray("connections");
        for (JsonElement element : connectionArray) {
            JsonObject connection = element.getAsJsonObject();
            JsonObject placement = connection.getAsJsonObject("placement");
            connections.add(new Connection(
                requiredString(connection, "target_settlement"),
                requiredString(placement, "mode"),
                requiredString(placement, "preferred_side"),
                placement.get("offset").getAsInt(),
                connection.get("gate_width").getAsInt(),
                connection.get("path_width").getAsInt()
            ));
        }
        return new SettlementPlan(
            id, enabled, structure, bounds, center, structurePoint, playerSpawn,
            zones, boundary, connections
        );
    }

    private static Point pointFrom(JsonObject object) {
        return new Point(object.get("x").getAsInt(), object.get("z").getAsInt());
    }

    private static String requiredString(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string field: " + key);
        }
        return object.get(key).getAsString();
    }

    private static void drawSettlementMap(
        ServerLevel level,
        Map<String, SettlementPlan> settlements
    ) {
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled()) {
                continue;
            }
            loadBounds(level, settlement.bounds());
            drawBiomeZones(level, settlement);
            drawBoundary(level, settlement, settlements);
        }
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled()) {
                continue;
            }
            for (Connection connection : settlement.connections()) {
                SettlementPlan target = settlements.get(connection.targetSettlement());
                if (target != null && target.enabled()
                    && settlement.id().compareTo(target.id()) < 0) {
                    drawConnection(level, settlement, connection, target);
                }
            }
        }
    }

    private static void drawBiomeZones(ServerLevel level, SettlementPlan settlement) {
        Bounds bounds = settlement.bounds();
        for (int minX = bounds.minX(); minX <= bounds.maxX(); minX += 16) {
            int maxX = Math.min(minX + 15, bounds.maxX());
            for (int minZ = bounds.minZ(); minZ <= bounds.maxZ(); minZ += 16) {
                int maxZ = Math.min(minZ + 15, bounds.maxZ());
                int sampleX = (minX + maxX) / 2;
                int sampleZ = (minZ + maxZ) / 2;
                BiomeZone zone = selectZone(settlement, sampleX, sampleZ);
                fillBiome(level, minX, minZ, maxX, maxZ, zone.biome());
                paintSurface(level, minX, minZ, maxX, maxZ, zone.biome());
            }
        }
    }

    private static BiomeZone selectZone(SettlementPlan settlement, int x, int z) {
        BiomeZone selected = settlement.zones().getFirst();
        double townCoreRadius = Math.min(96.0D, selected.sizeBlocks() / 2.0D);
        if (Math.hypot(x - settlement.center().x(), z - settlement.center().z()) <= townCoreRadius) {
            return selected;
        }
        double bestScore = Double.POSITIVE_INFINITY;
        for (int index = 0; index < settlement.zones().size(); index++) {
            BiomeZone zone = settlement.zones().get(index);
            Point center = zoneCenter(settlement, zone, index);
            double distance = Math.hypot(x - center.x(), z - center.z());
            double radius = Math.max(16.0D, zone.sizeBlocks() / 2.0D);
            double score = distance / radius / (0.75D + zone.weight() * 0.05D);
            if (score < bestScore) {
                bestScore = score;
                selected = zone;
            }
        }
        return selected;
    }

    private static Point zoneCenter(SettlementPlan settlement, BiomeZone zone, int index) {
        double factor = switch (zone.placement()) {
            case "center" -> 0.0D;
            case "inner" -> 0.22D;
            case "middle" -> 0.42D;
            case "outer" -> 0.68D;
            default -> 0.48D;
        };
        if (factor == 0.0D) {
            return settlement.center();
        }
        long seed = 31L * settlement.id().hashCode() + 17L * index;
        double angle = Math.floorMod(seed, 360L) * Math.PI / 180.0D;
        int halfWidth = (settlement.bounds().maxX() - settlement.bounds().minX()) / 2;
        int halfDepth = (settlement.bounds().maxZ() - settlement.bounds().minZ()) / 2;
        return new Point(
            settlement.center().x() + (int) Math.round(Math.cos(angle) * halfWidth * factor),
            settlement.center().z() + (int) Math.round(Math.sin(angle) * halfDepth * factor)
        );
    }

    private static void fillBiome(
        ServerLevel level,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String biome
    ) {
        try {
            level.getServer().getCommands().getDispatcher().execute(
                "fillbiome " + minX + " 55 " + minZ + " " + maxX + " 96 " + maxZ + " " + biome,
                level.getServer().createCommandSourceStack()
                    .withLevel(level)
                    .withPermission(4)
                    .withSuppressedOutput()
            );
        } catch (CommandSyntaxException error) {
            throw new IllegalStateException("Biome painting failed for " + biome, error);
        }
    }

    private static void paintSurface(
        ServerLevel level,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String biome
    ) {
        BlockState surface = surfaceBlock(biome);
        boolean river = biome.endsWith(":river") || biome.contains("river");
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlock(new BlockPos(x, 68, z), surface, 2);
                if (river) {
                    level.setBlock(new BlockPos(x, 69, z), Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
    }

    private static BlockState surfaceBlock(String biome) {
        if (biome.contains("river") || biome.contains("beach")) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (biome.contains("forest") || biome.contains("taiga")) {
            return Blocks.PODZOL.defaultBlockState();
        }
        if (biome.contains("desert") || biome.contains("badlands")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (biome.contains("snow") || biome.contains("ice")) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static void drawBoundary(
        ServerLevel level,
        SettlementPlan settlement,
        Map<String, SettlementPlan> settlements
    ) {
        Bounds bounds = settlement.bounds();
        Boundary boundary = settlement.boundary();
        List<Gate> gates = settlement.connections().stream()
            .map(connection -> gateFor(settlement, connection, settlements.get(connection.targetSettlement())))
            .toList();
        BlockState facing = boundary.profile().contains("greenway")
            ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
            : Blocks.STONE_BRICKS.defaultBlockState();
        for (int depth = 0; depth < boundary.wallThickness(); depth++) {
            int westX = bounds.minX() + depth;
            int eastX = bounds.maxX() - depth;
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                drawWallColumn(level, westX, z, "west", z, depth, boundary, facing, gates);
                drawWallColumn(level, eastX, z, "east", z, depth, boundary, facing, gates);
            }
            int northZ = bounds.minZ() + depth;
            int southZ = bounds.maxZ() - depth;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                drawWallColumn(level, x, northZ, "north", x, depth, boundary, facing, gates);
                drawWallColumn(level, x, southZ, "south", x, depth, boundary, facing, gates);
            }
        }
    }

    private static void drawWallColumn(
        ServerLevel level,
        int x,
        int z,
        String side,
        int axis,
        int depth,
        Boundary boundary,
        BlockState facing,
        List<Gate> gates
    ) {
        boolean opening = gates.stream().anyMatch(gate -> gate.side().equals(side)
            && Math.abs(axis - gate.axis()) <= gate.width() / 2);
        if (opening) {
            for (int y = 69; y < 69 + boundary.wallHeight(); y++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            return;
        }
        BlockState block = depth == boundary.wallThickness() / 2
            ? Blocks.BEDROCK.defaultBlockState()
            : facing;
        for (int y = 69; y < 69 + boundary.wallHeight(); y++) {
            level.setBlock(new BlockPos(x, y, z), block, 2);
        }
    }

    private static Gate gateFor(
        SettlementPlan source,
        Connection connection,
        SettlementPlan target
    ) {
        String side = connection.preferredSide();
        if (connection.mode().equals("toward_target") && target != null) {
            int deltaX = target.center().x() - source.center().x();
            int deltaZ = target.center().z() - source.center().z();
            if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
                side = deltaX >= 0 ? "east" : "west";
            } else {
                side = deltaZ >= 0 ? "south" : "north";
            }
        }
        int axis = (side.equals("east") || side.equals("west"))
            ? source.center().z() + connection.offset()
            : source.center().x() + connection.offset();
        Point point = switch (side) {
            case "west" -> new Point(source.bounds().minX(), axis);
            case "north" -> new Point(axis, source.bounds().minZ());
            case "south" -> new Point(axis, source.bounds().maxZ());
            default -> new Point(source.bounds().maxX(), axis);
        };
        return new Gate(side, axis, connection.gateWidth(), connection.pathWidth(), point);
    }

    private static void drawConnection(
        ServerLevel level,
        SettlementPlan source,
        Connection connection,
        SettlementPlan target
    ) {
        Gate start = gateFor(source, connection, target);
        Connection reverseConnection = target.connections().stream()
            .filter(candidate -> candidate.targetSettlement().equals(source.id()))
            .findFirst()
            .orElse(new Connection(source.id(), "toward_target", opposite(start.side()), 0,
                connection.gateWidth(), connection.pathWidth()));
        Gate end = gateFor(target, reverseConnection, source);
        int x = start.point().x();
        int z = start.point().z();
        int dx = Math.abs(end.point().x() - x);
        int dz = Math.abs(end.point().z() - z);
        int stepX = x < end.point().x() ? 1 : -1;
        int stepZ = z < end.point().z() ? 1 : -1;
        int error = dx - dz;
        while (true) {
            drawPathSlice(level, x, z, dx >= dz, Math.min(start.pathWidth(), end.pathWidth()));
            if (x == end.point().x() && z == end.point().z()) {
                break;
            }
            int doubled = error * 2;
            if (doubled > -dz) {
                error -= dz;
                x += stepX;
            }
            if (doubled < dx) {
                error += dx;
                z += stepZ;
            }
        }
    }

    private static void drawPathSlice(ServerLevel level, int x, int z, boolean alongX, int width) {
        level.getChunk(x >> 4, z >> 4);
        int half = width / 2;
        for (int offset = -half; offset <= half; offset++) {
            int pathX = alongX ? x : x + offset;
            int pathZ = alongX ? z + offset : z;
            level.setBlock(new BlockPos(pathX, 68, pathZ), Blocks.COBBLESTONE.defaultBlockState(), 2);
            for (int y = 69; y <= 72; y++) {
                level.setBlock(new BlockPos(pathX, y, pathZ), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int edge : new int[] {-half - 1, half + 1}) {
            int wallX = alongX ? x : x + edge;
            int wallZ = alongX ? z + edge : z;
            for (int y = 69; y <= 71; y++) {
                level.setBlock(new BlockPos(wallX, y, wallZ), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
    }

    private static String opposite(String side) {
        return switch (side) {
            case "east" -> "west";
            case "west" -> "east";
            case "north" -> "south";
            default -> "north";
        };
    }

    private static void movePlayerToStart(
        ServerPlayer player,
        ServerLevel level,
        BlockPos spawnPos
    ) {
        player.teleportTo(
            level,
            spawnPos.getX() + 0.5D,
            spawnPos.getY() + 1.0D,
            spawnPos.getZ() + 0.5D,
            0.0F,
            0.0F
        );
        player.setRespawnPosition(GENERATION_ONE, spawnPos, 0.0F, true, false);
        player.getPersistentData().putBoolean(PLAYER_STARTED, true);
    }

    private static BlockPos surfacePosition(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    record Point(int x, int z) {}

    record Bounds(int minX, int minZ, int maxX, int maxZ) {}

    record BiomeZone(String biome, int sizeBlocks, String placement, int weight) {}

    record Boundary(String profile, int width, int wallHeight, int wallThickness) {}

    record Connection(
        String targetSettlement,
        String mode,
        String preferredSide,
        int offset,
        int gateWidth,
        int pathWidth
    ) {}

    record Gate(String side, int axis, int width, int pathWidth, Point point) {}

    record SettlementPlan(
        String id,
        boolean enabled,
        String structure,
        Bounds bounds,
        Point center,
        Point structurePoint,
        Point playerSpawn,
        List<BiomeZone> zones,
        Boundary boundary,
        List<Connection> connections
    ) {}

    static final class BootstrapSavedData extends SavedData {
        private boolean complete;
        private int mapVersion;
        private BlockPos spawnPos = BlockPos.ZERO;
        private BlockPos villagePos = BlockPos.ZERO;

        static BootstrapSavedData create() {
            return new BootstrapSavedData();
        }

        static BootstrapSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
            BootstrapSavedData data = create();
            data.complete = tag.getBoolean("complete");
            data.mapVersion = tag.getInt("mapVersion");
            data.spawnPos = new BlockPos(tag.getInt("spawnX"), tag.getInt("spawnY"), tag.getInt("spawnZ"));
            data.villagePos = new BlockPos(
                tag.getInt("villageX"),
                tag.getInt("villageY"),
                tag.getInt("villageZ")
            );
            return data;
        }

        boolean isComplete(int expectedVersion) {
            return complete && mapVersion == expectedVersion;
        }

        BlockPos spawnPos() {
            return spawnPos;
        }

        void complete(BlockPos spawnPos, BlockPos villagePos, int version) {
            this.complete = true;
            this.mapVersion = version;
            this.spawnPos = spawnPos.immutable();
            this.villagePos = villagePos.immutable();
            setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("complete", complete);
            tag.putInt("mapVersion", mapVersion);
            tag.putInt("spawnX", spawnPos.getX());
            tag.putInt("spawnY", spawnPos.getY());
            tag.putInt("spawnZ", spawnPos.getZ());
            tag.putInt("villageX", villagePos.getX());
            tag.putInt("villageY", villagePos.getY());
            tag.putInt("villageZ", villagePos.getZ());
            return tag;
        }
    }
}
