package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
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
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(CobbleventureBootstrap.MOD_ID)
public final class CobbleventureBootstrap {
    public static final String MOD_ID = "cobbleventure_bootstrap";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_FILE = "cobbleventure_world_bootstrap";
    private static final int EXPECTED_SURFACE_Y = 69;
    private static final int WATER_LEVEL = 69;
    private static final int DEEP_FOUNDATION_MIN_Y = 0;
    private static final int DEEP_FOUNDATION_MAX_Y = 9;
    private static final int PREVIOUS_FOUNDATION_MIN_Y = 50;
    private static final int PREVIOUS_FOUNDATION_MAX_Y = 59;
    private static final int LEGACY_FOUNDATION_MIN_Y = 55;
    private static final int LEGACY_FOUNDATION_MAX_Y = 64;
    private static final int MAP_VERSION = 10;
    private static final int TOWN_PRELOAD_RADIUS_CHUNKS = 6;
    private static final String STARTER_SETTLEMENT = "cobbleventure:settlement/starter_town";
    private static final String INTEGRATION_TEST_PROPERTY = "cobbleventure.testStarterTown";
    private static final String HEX_WORLD_TEST_PROPERTY = "cobbleventure.testHexWorld";
    private static final String PLAYER_STARTED = "cobbleventureGenerationOneStarted";
    private static final String FACILITY_PORTAL_COOLDOWN = "cobbleventureFacilityPortalCooldown";
    private static final String FIELD_MOVE_PREFIX = "cobbleventureFieldMove.";
    private static final String FIELD_MOVE_MESSAGE_COOLDOWN = "cobbleventureFieldMoveMessageCooldown";
    private static volatile List<FacilityPortal> activeFacilityPortals = List.of();
    private static volatile HexWorldPlan activeHexWorld;
    private static final Map<UUID, Vec3> safeFieldPositions = new HashMap<>();
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
        TrainerCosmetics.register(modBus);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerTick);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onRegisterCommands);
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
        boolean deepFoundation = hasBedrockFoundation(
            level, 0, 0, DEEP_FOUNDATION_MIN_Y, DEEP_FOUNDATION_MAX_Y
        );
        boolean previousFoundation = hasBedrockFoundation(
            level, 0, 0, PREVIOUS_FOUNDATION_MIN_Y, PREVIOUS_FOUNDATION_MAX_Y
        );
        boolean legacyFoundation = hasBedrockFoundation(
            level, 0, 0, LEGACY_FOUNDATION_MIN_Y, LEGACY_FOUNDATION_MAX_Y
        );
        boolean emptyBelowFoundation = deepFoundation
            || previousFoundation
                && level.getBlockState(new BlockPos(0, PREVIOUS_FOUNDATION_MIN_Y - 1, 0)).isAir()
            || legacyFoundation
                && level.getBlockState(new BlockPos(0, LEGACY_FOUNDATION_MIN_Y - 1, 0)).isAir();
        if (!level.getBlockState(new BlockPos(0, 68, 0)).is(Blocks.GRASS_BLOCK)
            || (!deepFoundation && !previousFoundation && !legacyFoundation)
            || !emptyBelowFoundation) {
            throw new IllegalStateException(
                "Cobbleventure generation_1 must have grass over ten bedrock layers with empty space below"
            );
        }
        LOGGER.info(
            "Cobbleventure generation_1 ready: biome={}, surfaceY={}",
            STARTER_BIOME.location(),
            surface.getY()
        );

        RuntimeWorld runtime = loadRuntimeWorld(level);
        activeHexWorld = runtime.hexWorld();
        activeFacilityPortals = facilityPortals(runtime.settlements());

        if (Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY)) {
            drawHexWorld(level, runtime.hexWorld(), true);
            verifyHexWorld(level, runtime.hexWorld());
            for (SettlementPlan settlement : runtime.settlements().values()) {
                if (settlement.enabled() && !placeTown(level, settlement)) {
                    throw new IllegalStateException(
                        "Cobbleventure town placement integration test failed: " + settlement.id()
                    );
                }
            }
            LOGGER.info("Cobbleventure hex world rendering integration test succeeded");
            event.getServer().halt(false);
        }

        if (Boolean.getBoolean(INTEGRATION_TEST_PROPERTY)) {
            SettlementPlan starter = runtime.settlements().get(STARTER_SETTLEMENT);
            if (starter == null || !placeTown(level, starter)) {
                throw new IllegalStateException("Cobbleventure starter town integration placement failed");
            }
            LOGGER.info("Cobbleventure starter town integration placement succeeded at {}", starter.center());
        }
    }

    private static boolean hasBedrockFoundation(
        ServerLevel level, int x, int z, int minY, int maxY
    ) {
        for (int y = minY; y <= maxY; y++) {
            if (!level.getBlockState(new BlockPos(x, y, z)).is(Blocks.BEDROCK)) {
                return false;
            }
        }
        return true;
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
        RuntimeWorld runtime;
        try {
            runtime = loadRuntimeWorld(level);
        } catch (RuntimeException error) {
            LOGGER.error("Settlement map data could not be loaded", error);
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 마을 지도 데이터를 읽지 못했습니다. 서버 로그를 확인하세요."
            ));
            return false;
        }
        Map<String, SettlementPlan> settlements = runtime.settlements();
        SettlementPlan starter = settlements.get(STARTER_SETTLEMENT);
        if (starter == null) {
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 시작 마을 데이터가 없습니다."
            ));
            return false;
        }
        try {
            drawHexWorld(level, runtime.hexWorld(), data.hasExistingMap());
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
            if (settlement.enabled() && !placeFacilities(level, settlement)) {
                firstPlayer.sendSystemMessage(Component.literal(
                    "[Cobbleventure] 외부 시설 배치에 실패했습니다: " + settlement.id()
                ));
                return false;
            }
        }

        activeFacilityPortals = facilityPortals(settlements);

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
        preloadChunksAround(level, villagePos, TOWN_PRELOAD_RADIUS_CHUNKS);
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

    private static boolean placeFacilities(ServerLevel level, SettlementPlan settlement) {
        for (FacilityPlacement facility : settlement.facilities()) {
            BlockPoint position;
            if (facility.mode().equals("instanced_entry")) {
                position = facility.instanceOrigin();
            } else if (facility.mode().equals("direct_template")) {
                position = settlement.anchors().get(facility.anchor());
            } else {
                LOGGER.error("Unknown facility placement mode: {}", facility.mode());
                return false;
            }
            if (position == null || !placeTemplate(level, facility.structure(), position)) {
                LOGGER.error(
                    "Facility placement failed for {} / {} at {}",
                    settlement.id(), facility.id(), position
                );
                return false;
            }
        }
        return true;
    }

    private static boolean placeTemplate(ServerLevel level, String structure, BlockPoint position) {
        BlockPos blockPos = position.toBlockPos();
        preloadTemplateChunks(level, structure, blockPos);
        try {
            int placed = level.getServer().getCommands().getDispatcher().execute(
                "place template " + structure + " ~ ~ ~",
                level.getServer().createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(Vec3.atLowerCornerOf(blockPos))
                    .withPermission(4)
                    .withSuppressedOutput()
            );
            return placed != 0;
        } catch (CommandSyntaxException error) {
            LOGGER.error("Template command failed for {} at {}", structure, position, error);
            return false;
        }
    }

    private static void preloadChunksAround(ServerLevel level, BlockPos center, int radius) {
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void preloadTemplateChunks(ServerLevel level, String structure, BlockPos origin) {
        var template = level.getStructureManager().get(ResourceLocation.parse(structure));
        if (template.isEmpty()) {
            level.getChunk(origin);
            return;
        }
        var size = template.get().getSize();
        int minChunkX = Math.min(origin.getX(), origin.getX() + size.getX()) >> 4;
        int maxChunkX = Math.max(origin.getX(), origin.getX() + size.getX()) >> 4;
        int minChunkZ = Math.min(origin.getZ(), origin.getZ() + size.getZ()) >> 4;
        int maxChunkZ = Math.max(origin.getZ(), origin.getZ() + size.getZ()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            return;
        }
        long gameTime = level.getGameTime();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.serverLevel() != level) {
                continue;
            }
            if (!enforceFieldMoveAccess(player, level, gameTime)) {
                continue;
            }
            if (player.getPersistentData().getLong(FACILITY_PORTAL_COOLDOWN) > gameTime) {
                continue;
            }
            for (FacilityPortal portal : activeFacilityPortals) {
                BlockPoint destination = null;
                if (portal.entry().distanceSquared(player.position()) <= portal.radiusSquared()) {
                    destination = portal.instanceEntry();
                } else if (portal.instanceExit().distanceSquared(player.position())
                    <= portal.radiusSquared()) {
                    destination = portal.returnPoint();
                }
                if (destination != null) {
                    player.getPersistentData().putLong(FACILITY_PORTAL_COOLDOWN, gameTime + 40L);
                    player.teleportTo(
                        level,
                        destination.x() + 0.5D,
                        destination.y(),
                        destination.z() + 0.5D,
                        player.getYRot(),
                        player.getXRot()
                    );
                    break;
                }
            }
        }
    }

    private static boolean enforceFieldMoveAccess(
        ServerPlayer player, ServerLevel level, long gameTime
    ) {
        HexWorldPlan world = activeHexWorld;
        if (world == null || player.isCreative() || player.isSpectator()) {
            safeFieldPositions.put(player.getUUID(), player.position());
            return true;
        }
        TerrainSample sample = terrainAt(world, player.getX(), player.getZ());
        String requirement = sample == null ? null : sample.accessRequirement();
        if (requirement == null || hasFieldMove(player, requirement)) {
            safeFieldPositions.put(player.getUUID(), player.position());
            return true;
        }
        Vec3 safe = safeFieldPositions.get(player.getUUID());
        if (safe == null) {
            HexSettlement starter = world.settlements().get(STARTER_SETTLEMENT);
            Point center = world.grid().worldCenter(starter.anchor());
            safe = new Vec3(center.x() + 0.5D, 70.0D, center.z() + 0.5D);
        }
        player.teleportTo(
            level, safe.x(), safe.y(), safe.z(), player.getYRot(), player.getXRot()
        );
        if (player.getPersistentData().getLong(FIELD_MOVE_MESSAGE_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(FIELD_MOVE_MESSAGE_COOLDOWN, gameTime + 60L);
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 이 지역에 들어가려면 " + fieldMoveName(requirement) + " 기술이 필요합니다."
            ));
        }
        return false;
    }

    private static boolean hasFieldMove(ServerPlayer player, String requirement) {
        return player.getPersistentData().getBoolean(FIELD_MOVE_PREFIX + fieldMoveName(requirement));
    }

    private static String fieldMoveName(String requirement) {
        int separator = requirement.lastIndexOf('/');
        return separator >= 0 ? requirement.substring(separator + 1) : requirement;
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_field_move")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("grant")
                    .then(Commands.argument("move", StringArgumentType.word())
                        .executes(context -> setFieldMove(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move"), true
                        ))))
                .then(Commands.literal("revoke")
                    .then(Commands.argument("move", StringArgumentType.word())
                        .executes(context -> setFieldMove(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move"), false
                        ))))
        );
    }

    private static int setFieldMove(ServerPlayer player, String move, boolean granted) {
        player.getPersistentData().putBoolean(FIELD_MOVE_PREFIX + move, granted);
        player.sendSystemMessage(Component.literal(
            "[Cobbleventure] " + move + " 필드 기술을 " + (granted ? "해금했습니다." : "회수했습니다.")
        ));
        return 1;
    }

    private static List<FacilityPortal> facilityPortals(Map<String, SettlementPlan> settlements) {
        List<FacilityPortal> portals = new ArrayList<>();
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled()) {
                continue;
            }
            for (FacilityPlacement facility : settlement.facilities()) {
                if (!facility.mode().equals("instanced_entry")) {
                    continue;
                }
                BlockPoint entry = settlement.anchors().get(facility.entryAnchor());
                BlockPoint returnPoint = settlement.anchors().get(facility.returnAnchor());
                if (entry == null || returnPoint == null || facility.instanceOrigin() == null) {
                    throw new IllegalStateException(
                        "Facility portal references a missing anchor: " + settlement.id()
                            + " / " + facility.id()
                    );
                }
                portals.add(new FacilityPortal(
                    entry,
                    returnPoint,
                    facility.instanceOrigin().plus(facility.instanceEntryOffset()),
                    facility.instanceOrigin().plus(facility.instanceExitOffset()),
                    facility.triggerRadius() * facility.triggerRadius()
                ));
            }
        }
        return List.copyOf(portals);
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
        JsonObject centerJson = root.getAsJsonObject("center");
        Point center = pointFrom(centerJson);
        JsonObject anchors = root.getAsJsonObject("anchors");
        Map<String, BlockPoint> anchorPoints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : anchors.entrySet()) {
            anchorPoints.put(entry.getKey(), blockPointFrom(entry.getValue().getAsJsonObject()));
        }
        Point structurePoint = anchors.has("town_square")
            ? pointFrom(anchors.getAsJsonObject("town_square"))
            : center;
        Point playerSpawn = anchors.has("player_spawn")
            ? pointFrom(anchors.getAsJsonObject("player_spawn"))
            : center;
        JsonObject structureProfile = root.getAsJsonObject("structure_profile");
        String structure = requiredString(structureProfile, "structure");
        List<FacilityPlacement> facilities = new ArrayList<>();
        if (structureProfile.has("facility_placements")) {
            for (JsonElement element : structureProfile.getAsJsonArray("facility_placements")) {
                JsonObject facility = element.getAsJsonObject();
                facilities.add(new FacilityPlacement(
                    requiredString(facility, "id"),
                    requiredString(facility, "mode"),
                    requiredString(facility, "structure"),
                    optionalString(facility, "anchor"),
                    optionalString(facility, "entry_anchor"),
                    optionalString(facility, "return_anchor"),
                    optionalBlockPoint(facility, "instance_origin"),
                    optionalBlockPoint(facility, "instance_entry_offset"),
                    optionalBlockPoint(facility, "instance_exit_offset"),
                    facility.has("trigger_radius") ? facility.get("trigger_radius").getAsDouble() : 1.5D
                ));
            }
        }
        return new SettlementPlan(
            id, enabled, structure, center, structurePoint, playerSpawn,
            Map.copyOf(anchorPoints), List.copyOf(facilities)
        );
    }

    private static Point pointFrom(JsonObject object) {
        return new Point(object.get("x").getAsInt(), object.get("z").getAsInt());
    }

    private static BlockPoint blockPointFrom(JsonObject object) {
        return new BlockPoint(
            object.get("x").getAsInt(),
            object.get("y").getAsInt(),
            object.get("z").getAsInt()
        );
    }

    private static BlockPoint optionalBlockPoint(JsonObject object, String key) {
        return object.has(key) ? blockPointFrom(object.getAsJsonObject(key)) : null;
    }

    private static String optionalString(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : null;
    }

    private static String requiredString(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string field: " + key);
        }
        return object.get(key).getAsString();
    }

    private static RuntimeWorld loadRuntimeWorld(ServerLevel level) {
        Map<String, SettlementPlan> settlements = loadSettlementPlans(level);
        HexWorldPlan world = loadHexWorldPlan(level, settlements.keySet());
        return new RuntimeWorld(translateSettlements(settlements, world), world);
    }

    private static HexWorldPlan loadHexWorldPlan(ServerLevel level, Set<String> settlementIds) {
        JsonObject root = readJsonResource(level, "hex_worlds/generation_1.json");
        Map<String, BoundaryProfile> profiles = loadBoundaryProfiles(level);
        JsonObject gridJson = root.getAsJsonObject("grid");
        HexGrid grid = new HexGrid(
            gridJson.get("tile_radius_blocks").getAsInt(),
            blockPointFrom(gridJson.getAsJsonObject("origin"))
        );
        long seed = level.getSeed() ^ root.get("seed_salt").getAsLong();
        List<HexSettlement> hexSettlements = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("settlements")) {
            JsonObject value = element.getAsJsonObject();
            String settlement = requiredString(value, "settlement");
            if (!settlementIds.contains(settlement)) {
                throw new IllegalStateException("Hex world references missing settlement: " + settlement);
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            List<SurroundingRegion> surroundings = new ArrayList<>();
            for (JsonElement regionElement : value.getAsJsonArray("surroundings")) {
                JsonObject region = regionElement.getAsJsonObject();
                surroundings.add(new SurroundingRegion(
                    requiredString(region, "id"),
                    requiredString(region, "biome"),
                    region.get("tile_count").getAsInt(),
                    requiredString(region, "preferred_direction"),
                    requiredString(region, "growth"),
                    region.get("influence_radius_blocks").getAsDouble(),
                    region.get("edge_noise").getAsDouble(),
                    requiredString(region, "boundary_profile"),
                    terrainProfile(region),
                    optionalString(region, "access_requirement")
                ));
                requireBoundaryProfile(profiles, requiredString(region, "boundary_profile"));
            }
            String boundary = requiredString(value, "boundary_profile");
            requireBoundaryProfile(profiles, boundary);
            hexSettlements.add(new HexSettlement(
                settlement,
                new HexCoord(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                value.get("town_radius_cells").getAsInt(),
                requiredString(value, "town_biome"),
                List.copyOf(surroundings),
                boundary,
                terrainProfile(value),
                optionalString(value, "access_requirement")
            ));
        }
        List<HexConnection> connections = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("connections")) {
            JsonObject value = element.getAsJsonObject();
            String boundary = requiredString(value, "boundary_profile");
            requireBoundaryProfile(profiles, boundary);
            connections.add(new HexConnection(
                requiredString(value, "id"),
                requiredString(value, "from"),
                requiredString(value, "to"),
                requiredString(value, "route_biome"),
                value.get("width_cells").getAsInt(),
                requiredString(value, "pathfinding"),
                value.has("detour_cells") ? value.get("detour_cells").getAsInt() : 0,
                value.get("corridor_width_blocks").getAsDouble(),
                value.get("edge_noise").getAsDouble(),
                boundary,
                terrainProfile(value),
                requiredString(value, "surface_style"),
                optionalString(value, "access_requirement")
            ));
        }
        HexWorldPlan plan = planHexWorld(
            grid, seed, List.copyOf(hexSettlements), List.copyOf(connections), profiles
        );
        LOGGER.info(
            "Hex world planned: cells={}, settlements={}, routes={}",
            plan.cells().size(),
            plan.settlements().size(),
            plan.paths().size()
        );
        return plan;
    }

    private static JsonObject readJsonResource(ServerLevel level, String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("cobbleventure", path);
        Resource resource = level.getServer().getResourceManager().getResource(location)
            .orElseThrow(() -> new IllegalStateException("Missing packaged resource: " + location));
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid packaged resource: " + location, error);
        }
    }

    private static TerrainProfile terrainProfile(JsonObject value) {
        JsonObject terrain = value.getAsJsonObject("terrain_profile");
        return new TerrainProfile(
            terrain.get("base_height_offset").getAsInt(),
            terrain.get("height_variation").getAsInt(),
            terrain.get("noise_scale_blocks").getAsDouble()
        );
    }

    private static Map<String, BoundaryProfile> loadBoundaryProfiles(ServerLevel level) {
        JsonObject root = readJsonResource(level, "catalogs/boundary-profiles.json");
        Map<String, BoundaryProfile> profiles = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("profiles")) {
            JsonObject value = element.getAsJsonObject();
            String id = requiredString(value, "id");
            List<String> surfaceBlocks = new ArrayList<>();
            for (JsonElement block : value.getAsJsonArray("surface_blocks")) {
                surfaceBlocks.add(block.getAsString());
            }
            TreeProfile tree = null;
            if (value.has("tree")) {
                JsonObject treeJson = value.getAsJsonObject("tree");
                tree = new TreeProfile(
                    requiredString(treeJson, "log"),
                    requiredString(treeJson, "leaves"),
                    treeJson.get("spacing").getAsInt(),
                    treeJson.get("min_height").getAsInt(),
                    treeJson.get("max_height").getAsInt()
                );
            }
            BoundaryProfile profile = new BoundaryProfile(
                id,
                requiredString(value, "type"),
                value.get("width").getAsInt(),
                value.get("height").getAsInt(),
                value.get("foundation_depth").getAsInt(),
                requiredString(value, "collision"),
                requiredString(value, "core_block"),
                List.copyOf(surfaceBlocks),
                tree
            );
            if (profiles.putIfAbsent(id, profile) != null) {
                throw new IllegalStateException("Duplicate boundary profile: " + id);
            }
        }
        return Map.copyOf(profiles);
    }

    private static void requireBoundaryProfile(
        Map<String, BoundaryProfile> profiles, String id
    ) {
        if (!profiles.containsKey(id)) {
            throw new IllegalStateException("Missing boundary profile: " + id);
        }
    }

    private static HexWorldPlan planHexWorld(
        HexGrid grid,
        long seed,
        List<HexSettlement> settlements,
        List<HexConnection> connections,
        Map<String, BoundaryProfile> profiles
    ) {
        Map<HexCoord, CellPlan> cells = new LinkedHashMap<>();
        Map<HexCoord, String> townOwners = new HashMap<>();
        Map<String, HexSettlement> byId = new LinkedHashMap<>();
        for (HexSettlement settlement : settlements) {
            if (byId.putIfAbsent(settlement.settlement(), settlement) != null) {
                throw new IllegalStateException("Duplicate hex settlement: " + settlement.settlement());
            }
            for (HexCoord cell : hexRange(settlement.anchor(), settlement.townRadiusCells())) {
                String previous = townOwners.putIfAbsent(cell, settlement.settlement());
                if (previous != null) {
                    throw new IllegalStateException(
                        "Town hex footprints overlap: " + previous + " / " + settlement.settlement()
                    );
                }
                cells.put(cell, new CellPlan(
                    settlement.townBiome(), settlement.boundaryProfile(), "town", settlement.settlement(),
                    grid.radius() * 1.04D, 0.08D, settlement.terrainProfile(),
                    settlement.accessRequirement(), "natural"
                ));
            }
        }

        List<ConnectionPath> paths = new ArrayList<>();
        for (HexConnection connection : connections) {
            HexSettlement from = byId.get(connection.from());
            HexSettlement to = byId.get(connection.to());
            if (from == null || to == null) {
                throw new IllegalStateException("Connection references a missing settlement: " + connection.id());
            }
            List<HexCoord> path = findHexPath(from.anchor(), to.anchor(), townOwners, connection, seed);
            Set<HexCoord> routeCells = new HashSet<>();
            for (HexCoord center : path) {
                if (!townOwners.containsKey(center)) {
                    routeCells.add(center);
                }
                for (int radius = 1; radius < connection.widthCells(); radius++) {
                    for (HexCoord expanded : hexRange(center, radius)) {
                        if (!townOwners.containsKey(expanded)) {
                            routeCells.add(expanded);
                        }
                    }
                }
            }
            for (HexCoord cell : routeCells) {
                cells.put(cell, new CellPlan(
                    connection.routeBiome(), connection.boundaryProfile(), "route", connection.id(),
                    connection.corridorWidthBlocks() / 2.0D, connection.edgeNoise(),
                    connection.terrainProfile(), connection.accessRequirement(), connection.surfaceStyle()
                ));
            }
            paths.add(new ConnectionPath(
                connection.id(), connection.routeBiome(), connection.boundaryProfile(),
                connection.corridorWidthBlocks(), connection.edgeNoise(), connection.terrainProfile(),
                connection.surfaceStyle(), connection.accessRequirement(), List.copyOf(path)
            ));
        }

        for (HexSettlement settlement : settlements) {
            for (SurroundingRegion region : settlement.surroundings()) {
                growSurroundingRegion(cells, settlement, region, seed);
            }
        }
        return new HexWorldPlan(
            grid, seed, Map.copyOf(cells), List.copyOf(paths), Map.copyOf(byId), profiles
        );
    }

    private static List<HexCoord> findHexPath(
        HexCoord start,
        HexCoord target,
        Map<HexCoord, String> townOwners,
        HexConnection connection,
        long seed
    ) {
        PriorityQueue<PathNode> open = new PriorityQueue<>(
            Comparator.comparingInt(PathNode::score).thenComparing(node -> node.cell().toString())
        );
        Map<HexCoord, Integer> costs = new HashMap<>();
        Map<HexCoord, HexCoord> previous = new HashMap<>();
        costs.put(start, 0);
        open.add(new PathNode(start, 0, start.distance(target) * 10));
        while (!open.isEmpty()) {
            PathNode current = open.poll();
            if (current.cell().equals(target)) {
                return reconstructPath(previous, target);
            }
            if (current.cost() != costs.getOrDefault(current.cell(), Integer.MAX_VALUE)) {
                continue;
            }
            for (HexCoord next : current.cell().neighbors()) {
                String owner = townOwners.get(next);
                if (owner != null
                    && !owner.equals(connection.from())
                    && !owner.equals(connection.to())) {
                    continue;
                }
                int organic = connection.pathfinding().equals("organic")
                    ? Math.floorMod(stableHash(seed, connection.id(), next),
                        Math.max(1, connection.detourCells() * 4 + 1))
                    : 0;
                int nextCost = current.cost() + 10 + organic;
                if (nextCost >= costs.getOrDefault(next, Integer.MAX_VALUE)) {
                    continue;
                }
                costs.put(next, nextCost);
                previous.put(next, current.cell());
                open.add(new PathNode(next, nextCost, nextCost + next.distance(target) * 10));
            }
        }
        throw new IllegalStateException("No hex path found for connection: " + connection.id());
    }

    private static List<HexCoord> reconstructPath(
        Map<HexCoord, HexCoord> previous, HexCoord target
    ) {
        ArrayDeque<HexCoord> path = new ArrayDeque<>();
        HexCoord current = target;
        path.addFirst(current);
        while (previous.containsKey(current)) {
            current = previous.get(current);
            path.addFirst(current);
        }
        return List.copyOf(path);
    }

    private static void growSurroundingRegion(
        Map<HexCoord, CellPlan> cells,
        HexSettlement settlement,
        SurroundingRegion region,
        long seed
    ) {
        HexCoord direction = direction(region.preferredDirection());
        HexCoord preferred = settlement.anchor().plus(
            direction.scale(settlement.townRadiusCells() + 1)
        );
        Set<HexCoord> selected = new HashSet<>();
        Set<HexCoord> frontier = new HashSet<>();
        for (HexCoord townCell : hexRange(settlement.anchor(), settlement.townRadiusCells())) {
            frontier.addAll(townCell.neighbors());
        }
        frontier.removeAll(cells.keySet());
        while (selected.size() < region.tileCount()) {
            HexCoord best = frontier.stream()
                .min(Comparator
                    .comparingInt((HexCoord cell) -> regionScore(
                        cell, preferred, selected, region, seed, settlement.settlement()
                    ))
                    .thenComparing(HexCoord::toString))
                .orElseThrow(() -> new IllegalStateException(
                    "Could not allocate surrounding biome tiles: " + region.id()
                ));
            frontier.remove(best);
            if (cells.containsKey(best)) {
                continue;
            }
            selected.add(best);
            cells.put(best, new CellPlan(
                region.biome(), region.boundaryProfile(), "surrounding", region.id(),
                region.influenceRadiusBlocks(), region.edgeNoise(), region.terrainProfile(),
                region.accessRequirement(), "natural"
            ));
            for (HexCoord neighbor : best.neighbors()) {
                if (!cells.containsKey(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
    }

    private static int regionScore(
        HexCoord cell,
        HexCoord preferred,
        Set<HexCoord> selected,
        SurroundingRegion region,
        long seed,
        String settlement
    ) {
        int sameNeighbors = (int) cell.neighbors().stream().filter(selected::contains).count();
        int score = cell.distance(preferred) * 100;
        if (region.growth().equals("compact")) {
            score -= sameNeighbors * 45;
        } else if (region.growth().equals("linear")) {
            score -= sameNeighbors * 12;
        } else {
            score -= sameNeighbors * 28;
            score += Math.floorMod(stableHash(seed, settlement + region.id(), cell), 45);
        }
        return score;
    }

    private static HexCoord direction(String name) {
        return switch (name) {
            case "north_east" -> new HexCoord(1, -1);
            case "south_east" -> new HexCoord(0, 1);
            case "south_west" -> new HexCoord(-1, 1);
            case "west" -> new HexCoord(-1, 0);
            case "north_west" -> new HexCoord(0, -1);
            default -> new HexCoord(1, 0);
        };
    }

    private static Set<HexCoord> hexRange(HexCoord center, int radius) {
        Set<HexCoord> result = new HashSet<>();
        for (int dq = -radius; dq <= radius; dq++) {
            int minR = Math.max(-radius, -dq - radius);
            int maxR = Math.min(radius, -dq + radius);
            for (int dr = minR; dr <= maxR; dr++) {
                result.add(center.plus(new HexCoord(dq, dr)));
            }
        }
        return result;
    }

    private static int stableHash(long seed, String salt, HexCoord cell) {
        long value = seed;
        value = value * 31L + salt.hashCode();
        value = value * 31L + cell.q();
        value = value * 31L + cell.r();
        return (int) (value ^ (value >>> 32));
    }

    private static Map<String, SettlementPlan> translateSettlements(
        Map<String, SettlementPlan> settlements, HexWorldPlan world
    ) {
        Map<String, SettlementPlan> translated = new LinkedHashMap<>();
        for (SettlementPlan settlement : settlements.values()) {
            HexSettlement hex = world.settlements().get(settlement.id());
            if (hex == null) {
                throw new IllegalStateException("Settlement is missing from hex world: " + settlement.id());
            }
            Point target = world.grid().worldCenter(hex.anchor());
            int deltaX = target.x() - settlement.center().x();
            int deltaZ = target.z() - settlement.center().z();
            Map<String, BlockPoint> anchors = new LinkedHashMap<>();
            settlement.anchors().forEach((id, point) ->
                anchors.put(id, point.translate(deltaX, deltaZ))
            );
            translated.put(settlement.id(), new SettlementPlan(
                settlement.id(),
                settlement.enabled(),
                settlement.structure(),
                target,
                settlement.structurePoint().translate(deltaX, deltaZ),
                settlement.playerSpawn().translate(deltaX, deltaZ),
                Map.copyOf(anchors),
                settlement.facilities()
            ));
        }
        return Map.copyOf(translated);
    }

    private static void drawHexWorld(ServerLevel level, HexWorldPlan world, boolean cleanExisting) {
        HexBounds rawBounds = world.grid().bounds(world.cells().keySet());
        HexBounds bounds = new HexBounds(
            rawBounds.minX() - 32, rawBounds.minZ() - 32,
            rawBounds.maxX() + 32, rawBounds.maxZ() + 32
        );
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 4) {
            String runBiome = null;
            int runStart = bounds.minX();
            for (int x = bounds.minX(); x <= bounds.maxX() + 4; x += 4) {
                TerrainSample sample = x <= bounds.maxX()
                    ? terrainAt(world, x + 1.5D, z + 1.5D)
                    : null;
                String biome = sample == null ? null : sample.biome();
                if ((runBiome == null && biome == null)
                    || (runBiome != null && runBiome.equals(biome))) {
                    continue;
                }
                if (runBiome != null) {
                    fillBiome(level, runStart, z, x - 1, Math.min(z + 3, bounds.maxZ()), runBiome);
                }
                runBiome = biome;
                runStart = x;
            }
        }
        if (cleanExisting) {
            cleanupRenderedWorld(level, bounds);
        }
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample != null) {
                    paintSurface(level, world, x, z, sample);
                }
            }
        }
        drawContinuousBoundaries(level, world, bounds);
        drawHexRoads(level, world);
        decorateNaturalTerrain(level, world, bounds);
    }

    private static void cleanupRenderedWorld(ServerLevel level, HexBounds bounds) {
        List<Entity> staleEntities = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ServerPlayer)
                && entity.getX() >= bounds.minX() && entity.getX() <= bounds.maxX()
                && entity.getZ() >= bounds.minZ() && entity.getZ() <= bounds.maxZ()) {
                staleEntities.add(entity);
            }
        }
        staleEntities.forEach(Entity::discard);

        int removedBlockEntities = 0;
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos position : new ArrayList<>(chunk.getBlockEntitiesPos())) {
                    if (position.getX() < bounds.minX() || position.getX() > bounds.maxX()
                        || position.getZ() < bounds.minZ() || position.getZ() > bounds.maxZ()) {
                        continue;
                    }
                    chunk.getBlockEntity(position);
                    chunk.removeBlockEntity(position);
                    removedBlockEntities++;
                }
            }
        }
        LOGGER.info(
            "Cleaned previous Cobbleventure map before upgrade: entities={}, blockEntities={}",
            staleEntities.size(), removedBlockEntities
        );
    }

    private static void verifyHexWorld(ServerLevel level, HexWorldPlan world) {
        Map<String, List<TerrainSamplePoint>> samples = new LinkedHashMap<>();
        for (Map.Entry<HexCoord, CellPlan> entry : world.cells().entrySet()) {
            Point center = world.grid().worldCenter(entry.getKey());
            List<Point> probes = List.of(
                center,
                center.translate(16, 0),
                center.translate(-16, 0),
                center.translate(0, 16),
                center.translate(0, -16)
            );
            for (Point probe : probes) {
                TerrainSample sample = terrainAt(world, probe.x() + 0.5D, probe.z() + 0.5D);
                if (sample != null) {
                    samples.computeIfAbsent(
                        sample.kind() + ":" + sample.owner(), key -> new ArrayList<>()
                    ).add(new TerrainSamplePoint(probe, sample));
                }
            }
        }
        Set<String> expectedOwners = new HashSet<>();
        for (CellPlan cell : world.cells().values()) {
            expectedOwners.add(cell.kind() + ":" + cell.owner());
        }
        for (String owner : expectedOwners) {
            List<TerrainSamplePoint> candidates = samples.get(owner);
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalStateException("Continuous biome region has no visible sample: " + owner);
            }
            if (owner.startsWith("route:")) {
                continue;
            }
            TerrainSamplePoint selected = candidates.stream()
                .filter(candidate -> isValidRenderedSample(level, world, candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Continuous biome region has no valid interior sample: " + owner
                ));
            ResourceKey<net.minecraft.world.level.biome.Biome> expected = ResourceKey.create(
                Registries.BIOME,
                ResourceLocation.parse(selected.sample().biome())
            );
            BlockPos samplePosition = new BlockPos(selected.point().x(), 69, selected.point().z());
            if (!level.getBiome(samplePosition).is(expected)) {
                throw new IllegalStateException(
                    "Continuous biome rendering mismatch for " + owner
                        + ": expected " + selected.sample().biome()
                );
            }
            int groundY = terrainGroundY(
                world, selected.sample(), selected.point().x(), selected.point().z()
            );
            BlockState renderedSurface = level.getBlockState(new BlockPos(
                selected.point().x(), groundY, selected.point().z()
            ));
            boolean expectedSurface = renderedSurface.is(surfaceBlock(selected.sample().biome()).getBlock())
                || (selected.sample().surfaceStyle().equals("road")
                    && renderedSurface.is(Blocks.COBBLESTONE));
            if (!expectedSurface) {
                throw new IllegalStateException(
                    "Terrain surface rendering mismatch for " + owner + " at Y=" + groundY
                );
            }
            if (isAquatic(selected.sample())) {
                if (groundY > WATER_LEVEL - minimumWaterDepth(selected.sample())
                    || !level.getBlockState(new BlockPos(
                        selected.point().x(), groundY + 1, selected.point().z()
                    )).is(Blocks.WATER)
                    || !level.getBlockState(new BlockPos(
                        selected.point().x(), WATER_LEVEL, selected.point().z()
                    )).is(Blocks.WATER)
                    || !hasUnbreakableFoundation(
                        level, selected.point().x(), selected.point().z(),
                        DEEP_FOUNDATION_MIN_Y, DEEP_FOUNDATION_MAX_Y
                    )) {
                    throw new IllegalStateException(
                        "Aquatic terrain must be carved below a continuous waterline: " + owner
                    );
                }
            }
            if ("cobbleventure:field_move/rock_climb".equals(selected.sample().accessRequirement())
                && groundY < 74) {
                throw new IllegalStateException(
                    "Rock Climb region must be at least six blocks above the base surface: " + owner
                );
            }
            if ("cobbleventure:field_move/surf".equals(selected.sample().accessRequirement())
                && isAquatic(selected.sample())
                && !level.getBlockState(new BlockPos(
                    selected.point().x(), 69, selected.point().z()
                )).is(Blocks.WATER)) {
                throw new IllegalStateException("Surf region is missing navigable water: " + owner);
            }
        }
    }

    private static boolean hasUnbreakableFoundation(
        ServerLevel level, int x, int z, int minY, int maxY
    ) {
        for (int y = minY; y <= maxY; y++) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.is(Blocks.BEDROCK) && !state.is(Blocks.BARRIER)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidRenderedSample(
        ServerLevel level, HexWorldPlan world, TerrainSamplePoint selected
    ) {
        ResourceKey<net.minecraft.world.level.biome.Biome> expected = ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.parse(selected.sample().biome())
        );
        BlockPos samplePosition = new BlockPos(selected.point().x(), 69, selected.point().z());
        if (!level.getBiome(samplePosition).is(expected)) {
            return false;
        }
        int groundY = terrainGroundY(
            world, selected.sample(), selected.point().x(), selected.point().z()
        );
        BlockState renderedSurface = level.getBlockState(new BlockPos(
            selected.point().x(), groundY, selected.point().z()
        ));
        boolean expectedSurface = renderedSurface.is(surfaceBlock(selected.sample().biome()).getBlock())
            || (selected.sample().surfaceStyle().equals("road")
                && renderedSurface.is(Blocks.COBBLESTONE));
        if (!expectedSurface) {
            return false;
        }
        if (isAquatic(selected.sample())) {
            return groundY <= WATER_LEVEL - minimumWaterDepth(selected.sample())
                && level.getBlockState(new BlockPos(
                    selected.point().x(), groundY + 1, selected.point().z()
                )).is(Blocks.WATER)
                && level.getBlockState(new BlockPos(
                    selected.point().x(), WATER_LEVEL, selected.point().z()
                )).is(Blocks.WATER)
                && hasUnbreakableFoundation(
                    level, selected.point().x(), selected.point().z(),
                    DEEP_FOUNDATION_MIN_Y, DEEP_FOUNDATION_MAX_Y
                );
        }
        if ("cobbleventure:field_move/rock_climb".equals(selected.sample().accessRequirement())
            && groundY < 74) {
            return false;
        }
        return !"cobbleventure:field_move/surf".equals(selected.sample().accessRequirement())
            || !isAquatic(selected.sample())
            || level.getBlockState(new BlockPos(
                selected.point().x(), 69, selected.point().z()
            )).is(Blocks.WATER);
    }

    private static TerrainSample terrainAt(HexWorldPlan world, double x, double z) {
        TerrainSample town = strongestCellInfluence(world, x, z, "town");
        if (town != null) {
            return town;
        }
        TerrainSample route = strongestRouteInfluence(world, x, z);
        if (route != null) {
            return route;
        }
        return strongestCellInfluence(world, x, z, "surrounding");
    }

    private static TerrainSample strongestCellInfluence(
        HexWorldPlan world, double x, double z, String kind
    ) {
        TerrainSample selected = null;
        double selectedStrength = Double.NEGATIVE_INFINITY;
        for (Map.Entry<HexCoord, CellPlan> entry : world.cells().entrySet()) {
            CellPlan plan = entry.getValue();
            if (!plan.kind().equals(kind)) {
                continue;
            }
            Point center = world.grid().worldCenter(entry.getKey());
            double noise = layeredNoise(world.seed(), plan.owner() + ":edge", x, z, 96.0D);
            double radius = plan.influenceRadius() * (
                1.0D + Math.min(0.48D, plan.edgeNoise() * 1.65D) * noise
            );
            double strength = 1.0D - Math.hypot(x - center.x(), z - center.z()) / radius;
            if (strength >= 0.0D && strength > selectedStrength) {
                selectedStrength = strength;
                selected = new TerrainSample(
                    plan.biome(), plan.boundaryProfile(), plan.kind(), plan.owner(),
                    plan.terrainProfile(), plan.accessRequirement(), plan.surfaceStyle()
                );
            }
        }
        return selected;
    }

    private static TerrainSample strongestRouteInfluence(
        HexWorldPlan world, double x, double z
    ) {
        TerrainSample selected = null;
        double selectedStrength = Double.NEGATIVE_INFINITY;
        for (ConnectionPath route : world.paths()) {
            double distance = distanceToRoute(world.grid(), route.cells(), x, z);
            double noise = layeredNoise(world.seed(), route.id() + ":edge", x, z, 80.0D);
            double radius = route.corridorWidthBlocks() / 2.0D * (
                1.0D + Math.min(0.42D, route.edgeNoise() * 1.5D) * noise
            );
            double strength = 1.0D - distance / radius;
            if (strength >= 0.0D && strength > selectedStrength) {
                selectedStrength = strength;
                selected = new TerrainSample(
                    route.biome(), route.boundaryProfile(), "route", route.id(),
                    route.terrainProfile(), route.accessRequirement(), route.surfaceStyle()
                );
            }
        }
        return selected;
    }

    private static double distanceToRoute(
        HexGrid grid, List<HexCoord> cells, double x, double z
    ) {
        double closest = Double.POSITIVE_INFINITY;
        if (cells.size() == 1) {
            Point point = grid.worldCenter(cells.get(0));
            return Math.hypot(x - point.x(), z - point.z());
        }
        for (int index = 1; index < cells.size(); index++) {
            Point start = grid.worldCenter(cells.get(index - 1));
            Point end = grid.worldCenter(cells.get(index));
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            double lengthSquared = dx * dx + dz * dz;
            double factor = lengthSquared == 0.0D ? 0.0D
                : ((x - start.x()) * dx + (z - start.z()) * dz) / lengthSquared;
            factor = Math.max(0.0D, Math.min(1.0D, factor));
            double projectedX = start.x() + factor * dx;
            double projectedZ = start.z() + factor * dz;
            closest = Math.min(closest, Math.hypot(x - projectedX, z - projectedZ));
        }
        return closest;
    }

    private static double smoothNoise(long seed, String salt, double x, double z) {
        int minX = (int) Math.floor(x);
        int minZ = (int) Math.floor(z);
        double localX = fade(x - minX);
        double localZ = fade(z - minZ);
        double northWest = noiseCorner(seed, salt, minX, minZ);
        double northEast = noiseCorner(seed, salt, minX + 1, minZ);
        double southWest = noiseCorner(seed, salt, minX, minZ + 1);
        double southEast = noiseCorner(seed, salt, minX + 1, minZ + 1);
        double north = northWest + (northEast - northWest) * localX;
        double south = southWest + (southEast - southWest) * localX;
        return north + (south - north) * localZ;
    }

    private static double layeredNoise(
        long seed, String salt, double x, double z, double scale
    ) {
        return smoothNoise(seed, salt + ":broad", x / scale, z / scale) * 0.58D
            + smoothNoise(seed, salt + ":medium", x / (scale * 0.43D), z / (scale * 0.43D)) * 0.29D
            + smoothNoise(seed, salt + ":detail", x / (scale * 0.19D), z / (scale * 0.19D)) * 0.13D;
    }

    private static double noiseCorner(long seed, String salt, int x, int z) {
        int value = stableHash(seed, salt, new HexCoord(x, z));
        return (Math.floorMod(value, 20001) / 10000.0D) - 1.0D;
    }

    private static double fade(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static void fillBiome(
        ServerLevel level,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        String biome
    ) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
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
        ServerLevel level, HexWorldPlan world, int x, int z, TerrainSample sample
    ) {
        int groundY = terrainGroundY(world, sample, x, z);
        boolean aquatic = isAquatic(sample);
        BlockState surface = surfaceBlock(sample.biome());
        BlockState filler = fillerBlock(sample.biome());
        if (aquatic) {
            for (int y = DEEP_FOUNDATION_MIN_Y; y <= DEEP_FOUNDATION_MAX_Y; y++) {
                level.setBlock(new BlockPos(x, y, z), Blocks.BEDROCK.defaultBlockState(), 2);
            }
        }
        int fillerStart = aquatic ? DEEP_FOUNDATION_MAX_Y + 1 : LEGACY_FOUNDATION_MAX_Y + 1;
        for (int y = fillerStart; y < groundY; y++) {
            level.setBlock(new BlockPos(x, y, z), filler, 2);
        }
        level.setBlock(new BlockPos(x, groundY, z), surface, 2);
        int waterTop = aquatic ? WATER_LEVEL : groundY;
        for (int y = groundY + 1; y <= waterTop; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), 2);
        }
        for (int y = waterTop + 1; y <= 84; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static int terrainGroundY(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainProfile terrain = sample.terrainProfile();
        double noise = layeredNoise(
            world.seed(), sample.owner() + ":height",
            x, z, terrain.noiseScaleBlocks()
        );
        int height = 68 + terrain.baseHeightOffset()
            + (int) Math.round(terrain.heightVariation() * noise);
        int minimumY = isAquatic(sample) ? DEEP_FOUNDATION_MAX_Y + 1 : 65;
        return Math.max(minimumY, Math.min(88, height));
    }

    private static boolean isAquatic(TerrainSample sample) {
        return sample.surfaceStyle().equals("water")
            || sample.biome().contains("ocean")
            || sample.biome().contains("river");
    }

    private static int minimumWaterDepth(TerrainSample sample) {
        return sample.biome().contains("ocean") ? 20 : 6;
    }

    private static BlockState fillerBlock(String biome) {
        if (biome.contains("ocean")) {
            return Blocks.STONE.defaultBlockState();
        }
        if (biome.contains("desert") || biome.contains("beach")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (biome.contains("badlands")) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        if (biome.contains("mountain") || biome.contains("windswept")) {
            return Blocks.STONE.defaultBlockState();
        }
        return Blocks.DIRT.defaultBlockState();
    }

    private static BlockState surfaceBlock(String biome) {
        if (biome.contains("ocean")) {
            return biome.contains("warm")
                ? Blocks.SAND.defaultBlockState()
                : Blocks.GRAVEL.defaultBlockState();
        }
        if (biome.contains("river") || biome.contains("beach")) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (biome.contains("forest") || biome.contains("taiga")) {
            return Blocks.PODZOL.defaultBlockState();
        }
        if (biome.contains("badlands")) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        if (biome.contains("desert") || biome.contains("beach")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (biome.contains("snow") || biome.contains("ice")) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static void drawContinuousBoundaries(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Set<String> rendered = new HashSet<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null) {
                    continue;
                }
                int outwardX = 0;
                int outwardZ = 0;
                for (int[] direction : directions) {
                    if (terrainAt(
                        world,
                        x + 0.5D + direction[0] * 2.0D,
                        z + 0.5D + direction[1] * 2.0D
                    ) == null) {
                        outwardX = direction[0];
                        outwardZ = direction[1];
                        break;
                    }
                }
                if (outwardX == 0 && outwardZ == 0) {
                    continue;
                }
                BoundaryProfile profile = world.boundaryProfiles().get(sample.boundaryProfile());
                if (profile == null) {
                    throw new IllegalStateException(
                        "Missing continuous boundary profile: " + sample.boundaryProfile()
                    );
                }
                for (int offset = -profile.width() / 2; offset <= profile.width() / 2; offset++) {
                    int targetX = x + outwardX * offset;
                    int targetZ = z + outwardZ * offset;
                    String key = profile.id() + ":" + targetX + ":" + targetZ;
                    if (rendered.add(key)) {
                        int baseY = terrainGroundY(world, sample, targetX, targetZ) + 1;
                        drawBoundaryColumn(
                            level, targetX, baseY, targetZ, offset,
                            Math.floorMod(x * 31 + z * 17, 100000), profile, world.seed()
                        );
                    }
                }
            }
        }
    }

    private static void drawBoundaryColumn(
        ServerLevel level,
        int x,
        int baseY,
        int z,
        int offset,
        int step,
        BoundaryProfile profile,
        long seed
    ) {
        level.getChunk(x >> 4, z >> 4);
        BlockState core = blockState(profile.coreBlock());
        BlockState surface = blockState(profile.surfaceBlocks().get(
            Math.floorMod((int) (seed + x * 31L + z * 17L), profile.surfaceBlocks().size())
        ));
        for (int y = baseY - profile.foundationDepth(); y < baseY; y++) {
            level.setBlock(new BlockPos(x, y, z), core, 2);
        }
        if (profile.type().equals("wall")) {
            BlockState block = offset == 0 ? core : surface;
            for (int y = baseY; y < baseY + profile.height(); y++) {
                level.setBlock(new BlockPos(x, y, z), block, 2);
            }
            return;
        }
        if (profile.type().equals("earthwork")) {
            int half = Math.max(1, profile.width() / 2);
            int moundHeight = Math.max(1, profile.height() * (half - Math.abs(offset) + 1) / (half + 1));
            for (int y = baseY; y < baseY + moundHeight; y++) {
                level.setBlock(new BlockPos(x, y, z), y == baseY + moundHeight - 1 ? surface : Blocks.DIRT.defaultBlockState(), 2);
            }
            if (Math.abs(offset) <= 2 && profile.collision().equals("protected")) {
                for (int y = baseY + moundHeight; y < baseY + profile.height() + 3; y++) {
                    level.setBlock(new BlockPos(x, y, z), core, 2);
                }
            }
            return;
        }
        TreeProfile tree = profile.tree();
        if (Math.abs(offset) <= 2 && offset != 0 && !profile.collision().equals("soft")) {
            for (int y = baseY; y < baseY + profile.height() + 2; y++) {
                level.setBlock(new BlockPos(x, y, z), core, 2);
            }
        }
        level.setBlock(new BlockPos(x, baseY - 1, z), surface, 2);
        if (tree != null && offset == 0 && step % tree.spacing() == 0) {
            int height = tree.minHeight() + Math.floorMod(
                (int) (seed + x * 13L + z * 7L), tree.maxHeight() - tree.minHeight() + 1
            );
            BlockState log = blockState(tree.log());
            BlockState leaves = blockState(tree.leaves());
            for (int y = baseY; y < baseY + height; y++) {
                level.setBlock(new BlockPos(x, y, z), log, 2);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) <= 4) {
                            level.setBlock(new BlockPos(x + dx, baseY + height + dy, z + dz), leaves, 2);
                        }
                    }
                }
            }
        }
    }

    private static BlockState blockState(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private static void drawHexRoads(ServerLevel level, HexWorldPlan world) {
        for (ConnectionPath connection : world.paths()) {
            if (!connection.surfaceStyle().equals("road")) {
                continue;
            }
            List<HexCoord> cells = connection.cells();
            for (int index = 1; index < cells.size(); index++) {
                drawRoadSegment(
                    level, world,
                    world.grid().worldCenter(cells.get(index - 1)),
                    world.grid().worldCenter(cells.get(index))
                );
            }
        }
    }

    private static void decorateNaturalTerrain(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        for (int gridX = bounds.minX(); gridX <= bounds.maxX(); gridX += 6) {
            for (int gridZ = bounds.minZ(); gridZ <= bounds.maxZ(); gridZ += 6) {
                int hash = decorationHash(world.seed(), gridX, gridZ);
                int x = gridX + Math.floorMod(hash, 5) - 2;
                int z = gridZ + Math.floorMod(hash / 7, 5) - 2;
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || !sample.kind().equals("surrounding") || isAquatic(sample)
                    || !hasDecorationClearance(world, sample, x, z)) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                BlockPos ground = new BlockPos(x, groundY, z);
                BlockPos above = ground.above();
                if (!level.getBlockState(above).isAir()) {
                    continue;
                }
                String biome = sample.biome();
                int choice = Math.floorMod(hash, 100);
                if (biome.contains("forest") || biome.contains("taiga")) {
                    if (choice < 58) {
                        placeNaturalTree(level, above, hash, biome.contains("taiga"));
                    } else if (choice < 84) {
                        placeGroundPlant(level, above, hash, biome.contains("flower"));
                    }
                } else if (biome.contains("flower") || biome.contains("plains")) {
                    if (choice < 72) {
                        placeGroundPlant(level, above, hash, biome.contains("flower"));
                    } else if (choice < 79) {
                        placeNaturalTree(level, above, hash, false);
                    }
                } else if (biome.contains("badlands") || biome.contains("desert")) {
                    if (choice < 38) {
                        level.setBlock(above, Blocks.DEAD_BUSH.defaultBlockState(), 2);
                    } else if (choice < 48) {
                        placeBoulder(level, above, hash, biome.contains("badlands"));
                    }
                } else if (biome.contains("mountain") || biome.contains("windswept")) {
                    if (choice < 20) {
                        placeNaturalTree(level, above, hash, true);
                    } else if (choice < 45) {
                        placeBoulder(level, above, hash, false);
                    }
                }
            }
        }
    }

    private static boolean hasDecorationClearance(
        HexWorldPlan world, TerrainSample sample, int x, int z
    ) {
        int[][] checks = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}};
        for (int[] check : checks) {
            TerrainSample nearby = terrainAt(world, x + check[0] + 0.5D, z + check[1] + 0.5D);
            if (nearby == null || !nearby.owner().equals(sample.owner())) {
                return false;
            }
        }
        return true;
    }

    private static int decorationHash(long seed, int x, int z) {
        long mixed = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (int) (mixed ^ mixed >>> 32);
    }

    private static void placeGroundPlant(ServerLevel level, BlockPos pos, int hash, boolean flowers) {
        BlockState plant;
        if (!flowers || Math.floorMod(hash, 5) < 2) {
            plant = Blocks.SHORT_GRASS.defaultBlockState();
        } else {
            plant = switch (Math.floorMod(hash, 4)) {
                case 0 -> Blocks.DANDELION.defaultBlockState();
                case 1 -> Blocks.POPPY.defaultBlockState();
                case 2 -> Blocks.AZURE_BLUET.defaultBlockState();
                default -> Blocks.OXEYE_DAISY.defaultBlockState();
            };
        }
        level.setBlock(pos, plant, 2);
    }

    private static void placeNaturalTree(ServerLevel level, BlockPos base, int hash, boolean spruce) {
        int height = 4 + Math.floorMod(hash, 3);
        BlockState log = spruce ? Blocks.SPRUCE_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = spruce ? Blocks.SPRUCE_LEAVES.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState();
        for (int y = 0; y < height; y++) {
            level.setBlock(base.above(y), log, 2);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + Math.max(0, Math.abs(dy) - 1) > 4) {
                        continue;
                    }
                    BlockPos leafPos = base.offset(dx, height + dy, dz);
                    if (level.getBlockState(leafPos).isAir()) {
                        level.setBlock(leafPos, leaves, 2);
                    }
                }
            }
        }
    }

    private static void placeBoulder(ServerLevel level, BlockPos base, int hash, boolean red) {
        BlockState rock = red ? Blocks.RED_SANDSTONE.defaultBlockState()
            : (Math.floorMod(hash, 3) == 0
                ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                : Blocks.STONE.defaultBlockState());
        int radius = 1 + Math.floorMod(hash, 2);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= radius; dy++) {
                    if (dx * dx + dz * dz + dy * dy <= radius * radius + 1) {
                        BlockPos rockPos = base.offset(dx, dy, dz);
                        if (level.getBlockState(rockPos).isAir()) {
                            level.setBlock(rockPos, rock, 2);
                        }
                    }
                }
            }
        }
    }

    private static void drawRoadSegment(
        ServerLevel level, HexWorldPlan world, Point start, Point end
    ) {
        int dx = end.x() - start.x();
        int dz = end.z() - start.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.x() + dx * factor);
            int z = (int) Math.round(start.z() + dz * factor);
            for (int offsetX = -3; offsetX <= 3; offsetX++) {
                for (int offsetZ = -3; offsetZ <= 3; offsetZ++) {
                    if (offsetX * offsetX + offsetZ * offsetZ > 10) {
                        continue;
                    }
                    TerrainSample sample = terrainAt(
                        world, x + offsetX + 0.5D, z + offsetZ + 0.5D
                    );
                    if (sample == null || isAquatic(sample)) {
                        continue;
                    }
                    int groundY = terrainGroundY(world, sample, x + offsetX, z + offsetZ);
                    level.setBlock(
                        new BlockPos(x + offsetX, groundY, z + offsetZ),
                        Blocks.COBBLESTONE.defaultBlockState(),
                        2
                    );
                    for (int y = groundY + 1; y <= groundY + 4; y++) {
                        level.setBlock(new BlockPos(x + offsetX, y, z + offsetZ), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
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

    record Point(int x, int z) {
        Point translate(int deltaX, int deltaZ) {
            return new Point(x + deltaX, z + deltaZ);
        }
    }

    record BlockPoint(int x, int y, int z) {
        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        BlockPoint plus(BlockPoint other) {
            if (other == null) {
                throw new IllegalStateException("Facility instance offset is missing");
            }
            return new BlockPoint(x + other.x, y + other.y, z + other.z);
        }

        BlockPoint translate(int deltaX, int deltaZ) {
            return new BlockPoint(x + deltaX, y, z + deltaZ);
        }

        double distanceSquared(Vec3 position) {
            double dx = position.x - (x + 0.5D);
            double dy = position.y - y;
            double dz = position.z - (z + 0.5D);
            return dx * dx + dy * dy + dz * dz;
        }
    }

    record HexCoord(int q, int r) {
        private static final List<HexCoord> DIRECTIONS = List.of(
            new HexCoord(1, 0),
            new HexCoord(1, -1),
            new HexCoord(0, -1),
            new HexCoord(-1, 0),
            new HexCoord(-1, 1),
            new HexCoord(0, 1)
        );

        HexCoord plus(HexCoord other) {
            return new HexCoord(q + other.q, r + other.r);
        }

        HexCoord scale(int amount) {
            return new HexCoord(q * amount, r * amount);
        }

        int distance(HexCoord other) {
            int deltaQ = q - other.q;
            int deltaR = r - other.r;
            int deltaS = -q - r + other.q + other.r;
            return (Math.abs(deltaQ) + Math.abs(deltaR) + Math.abs(deltaS)) / 2;
        }

        List<HexCoord> neighbors() {
            return DIRECTIONS.stream().map(this::plus).toList();
        }

        @Override
        public String toString() {
            return q + "," + r;
        }
    }

    record HexGrid(int radius, BlockPoint origin) {
        Point worldCenter(HexCoord cell) {
            int x = (int) Math.round(
                origin.x() + radius * Math.sqrt(3.0D) * (cell.q() + cell.r() / 2.0D)
            );
            int z = (int) Math.round(origin.z() + radius * 1.5D * cell.r());
            return new Point(x, z);
        }

        HexCoord worldToHex(double x, double z) {
            double localX = x - origin.x();
            double localZ = z - origin.z();
            double qValue = (Math.sqrt(3.0D) / 3.0D * localX - localZ / 3.0D) / radius;
            double rValue = (2.0D / 3.0D * localZ) / radius;
            double sValue = -qValue - rValue;
            int q = (int) Math.round(qValue);
            int r = (int) Math.round(rValue);
            int s = (int) Math.round(sValue);
            double qDifference = Math.abs(q - qValue);
            double rDifference = Math.abs(r - rValue);
            double sDifference = Math.abs(s - sValue);
            if (qDifference > rDifference && qDifference > sDifference) {
                q = -r - s;
            } else if (rDifference > sDifference) {
                r = -q - s;
            }
            return new HexCoord(q, r);
        }

        HexBounds bounds(Set<HexCoord> cells) {
            if (cells.isEmpty()) {
                throw new IllegalStateException("Hex world contains no cells");
            }
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (HexCoord cell : cells) {
                Point center = worldCenter(cell);
                minX = Math.min(minX, center.x() - radius);
                minZ = Math.min(minZ, center.z() - radius);
                maxX = Math.max(maxX, center.x() + radius);
                maxZ = Math.max(maxZ, center.z() + radius);
            }
            return new HexBounds(minX, minZ, maxX, maxZ);
        }
    }

    record HexBounds(int minX, int minZ, int maxX, int maxZ) {}

    record SurroundingRegion(
        String id,
        String biome,
        int tileCount,
        String preferredDirection,
        String growth,
        double influenceRadiusBlocks,
        double edgeNoise,
        String boundaryProfile,
        TerrainProfile terrainProfile,
        String accessRequirement
    ) {}

    record HexSettlement(
        String settlement,
        HexCoord anchor,
        int townRadiusCells,
        String townBiome,
        List<SurroundingRegion> surroundings,
        String boundaryProfile,
        TerrainProfile terrainProfile,
        String accessRequirement
    ) {}

    record HexConnection(
        String id,
        String from,
        String to,
        String routeBiome,
        int widthCells,
        String pathfinding,
        int detourCells,
        double corridorWidthBlocks,
        double edgeNoise,
        String boundaryProfile,
        TerrainProfile terrainProfile,
        String surfaceStyle,
        String accessRequirement
    ) {}

    record CellPlan(
        String biome,
        String boundaryProfile,
        String kind,
        String owner,
        double influenceRadius,
        double edgeNoise,
        TerrainProfile terrainProfile,
        String accessRequirement,
        String surfaceStyle
    ) {}

    record ConnectionPath(
        String id,
        String biome,
        String boundaryProfile,
        double corridorWidthBlocks,
        double edgeNoise,
        TerrainProfile terrainProfile,
        String surfaceStyle,
        String accessRequirement,
        List<HexCoord> cells
    ) {}

    record TerrainProfile(int baseHeightOffset, int heightVariation, double noiseScaleBlocks) {}

    record TerrainSample(
        String biome,
        String boundaryProfile,
        String kind,
        String owner,
        TerrainProfile terrainProfile,
        String accessRequirement,
        String surfaceStyle
    ) {}

    record TerrainSamplePoint(Point point, TerrainSample sample) {}

    record TreeProfile(
        String log,
        String leaves,
        int spacing,
        int minHeight,
        int maxHeight
    ) {}

    record BoundaryProfile(
        String id,
        String type,
        int width,
        int height,
        int foundationDepth,
        String collision,
        String coreBlock,
        List<String> surfaceBlocks,
        TreeProfile tree
    ) {}

    record HexWorldPlan(
        HexGrid grid,
        long seed,
        Map<HexCoord, CellPlan> cells,
        List<ConnectionPath> paths,
        Map<String, HexSettlement> settlements,
        Map<String, BoundaryProfile> boundaryProfiles
    ) {}

    record PathNode(HexCoord cell, int cost, int score) {}

    record RuntimeWorld(
        Map<String, SettlementPlan> settlements,
        HexWorldPlan hexWorld
    ) {}

    record FacilityPlacement(
        String id,
        String mode,
        String structure,
        String anchor,
        String entryAnchor,
        String returnAnchor,
        BlockPoint instanceOrigin,
        BlockPoint instanceEntryOffset,
        BlockPoint instanceExitOffset,
        double triggerRadius
    ) {}

    record FacilityPortal(
        BlockPoint entry,
        BlockPoint returnPoint,
        BlockPoint instanceEntry,
        BlockPoint instanceExit,
        double radiusSquared
    ) {}

    record SettlementPlan(
        String id,
        boolean enabled,
        String structure,
        Point center,
        Point structurePoint,
        Point playerSpawn,
        Map<String, BlockPoint> anchors,
        List<FacilityPlacement> facilities
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

        boolean hasExistingMap() {
            return complete || mapVersion > 0;
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
