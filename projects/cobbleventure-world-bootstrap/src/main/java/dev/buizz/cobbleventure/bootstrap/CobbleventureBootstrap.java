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
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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
    private static final int BCA_REFERENCE_SURFACE_Y = 68;
    private static final int LEGACY_SURFACE_Y = 69;
    private static final int SEALED_OUTER_SURFACE_Y = 92;
    private static final int SEALED_OUTER_MIN_Y = 88;
    private static final int WATER_SURFACE_Y = 64;
    private static final int NORMAL_TERRAIN_MIN_Y = 66;
    private static final int SHORE_LAND_TARGET_Y = WATER_SURFACE_Y;
    private static final int SHORE_BLEND_WIDTH = 24;
    private static final int SHORE_SAND_HEIGHT_BLOCKS = 3;
    private static final int SHORE_SAND_WIDTH_BLOCKS = 6;
    private static final int OUTER_TERRAIN_TRANSITION_WIDTH = 32;
    private static final int OCEAN_CLIFF_WIDTH = 14;
    private static final int MAX_TOWN_PREPARATION_CHUNKS = 320;
    private static final int LAZY_TOWN_TRIGGER_DISTANCE = 64;
    private static final int STARTER_TOWN_CHUNKS_PER_TICK = 8;
    private static final int BACKGROUND_TOWN_CHUNKS_PER_TICK = 1;
    private static final int OCEAN_CLIFF_MIN_Y = WATER_SURFACE_Y + 2;
    private static final int OCEAN_CLIFF_MAX_Y = WATER_SURFACE_Y + 14;
    private static final int GYM_RING_ROAD_MARGIN = 6;
    private static final int GYM_RING_ROAD_WIDTH = 4;
    private static final int GYM_BUILDING_CLEARANCE = 16;
    private static final int GYM_LOT_CLEARANCE = 6;
    private static final int GYM_LOT_SEARCH_RADIUS = 86;
    private static final int GYM_ROAD_SEARCH_RADIUS = 42;
    // Every RGS Kanto gym uses the same 25x13x26 shell. The west-side fence gate
    // at (2, 3, 10) is decoration; the actual public entrance is the centered
    // north opening identified by its interior pressure plate at (12, 3, 3).
    private static final BlockPoint RGS_GYM_ENTRANCE_OFFSET = new BlockPoint(2, 3, 10);
    private static final int LEGACY_VISIBLE_BOUNDARY_CLEANUP_RADIUS = 8;
    private static final int DEEP_FOUNDATION_MIN_Y = 0;
    private static final int DEEP_FOUNDATION_MAX_Y = 9;
    private static final int FLAT_GENERATOR_SURFACE_Y = 67;
    private static final int PREVIOUS_FOUNDATION_MIN_Y = 50;
    private static final int PREVIOUS_FOUNDATION_MAX_Y = 59;
    private static final int LEGACY_FOUNDATION_MIN_Y = 55;
    private static final int LEGACY_FOUNDATION_MAX_Y = 64;
    private static final int MAP_VERSION = 83;
    private static final int COLLISION_SHELL_RADIUS = 2;
    private static final double TOWN_RELIEF_SCALE = 0.22D;
    private static final int TOWN_EDGE_RELIEF_BLEND_BLOCKS = 40;
    private static final double TOWN_STRUCTURE_MAX_RADIUS_BLOCKS = 116.0D;
    private static final double TOWN_BOUNDARY_CLEARANCE_BLOCKS = 16.0D;
    private static final double TOWN_ROUTE_CLIP_RADIUS_BLOCKS = 64.0D;
    private static final int TOWN_PRELOAD_RADIUS_CHUNKS = 9;
    private static final int WAITING_AREA_Y = 80;
    private static final int WAITING_AREA_X = -8192;
    private static final int WAITING_AREA_Z = -8192;
    private static final int WAITING_AREA_RADIUS = 8;
    private static final String STARTER_SETTLEMENT = "cobbleventure:settlement/starter_town";
    private static final String INTEGRATION_TEST_PROPERTY = "cobbleventure.testStarterTown";
    private static final String HEX_WORLD_TEST_PROPERTY = "cobbleventure.testHexWorld";
    private static final String TEST_CLEAN_EXISTING_PROPERTY = "cobbleventure.testCleanExisting";
    private static final String PERFORMANCE_TEST_PROPERTY = "cobbleventure.performanceTest";
    private static final String TEST_RENDER_RADIUS_PROPERTY = "cobbleventure.testRenderRadius";
    private static final String PLAYER_STARTED = "cobbleventureGenerationOneStarted";
    private static final String PLAYER_WAITING = "cobbleventureGenerationWaiting";
    private static final String FACILITY_PORTAL_COOLDOWN = "cobbleventureFacilityPortalCooldown";
    private static final String FIELD_MOVE_PREFIX = "cobbleventureFieldMove.";
    private static final String FIELD_MOVE_MESSAGE_COOLDOWN = "cobbleventureFieldMoveMessageCooldown";
    private static volatile List<FacilityPortal> activeFacilityPortals = List.of();
    private static volatile Map<String, SettlementPlan> activeSettlements = Map.of();
    private static volatile HexWorldPlan activeHexWorld;
    private static volatile ShoreDistanceField activeShoreDistances;
    private static volatile int integrationShutdownTicks = -1;
    private static volatile UUID pendingInitializationPlayer;
    private static volatile int pendingInitializationTicks = -1;
    private static volatile WorldInitializationJob activeInitialization;
    private static final Map<NoiseKey, NormalNoise> TERRAIN_NOISES = new ConcurrentHashMap<>();
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
    private static final ResourceKey<net.minecraft.world.level.biome.Biome> SEALED_DARK_FOREST =
        ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "sealed_dark_forest")
        );
    private static final TreeProfile OUTER_FOREST_TREE = new TreeProfile(
        "minecraft:dark_oak_log", "minecraft:dark_oak_leaves", 9, 6, 10
    );

    public CobbleventureBootstrap(IEventBus modBus) {
        NativeWorldGeneration.register(modBus);
        TrainerCosmetics.register(modBus);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerTick);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onRegisterCommands);
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
            || !event.getLevel().dimension().equals(GENERATION_ONE)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player
            && event.getLevel() instanceof ServerLevel level) {
            BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
                DATA_FILE
            );
            if (!data.isComplete(MAP_VERSION)) {
                double offsetX = player.getX() - (WAITING_AREA_X + 0.5D);
                double offsetZ = player.getZ() - (WAITING_AREA_Z + 0.5D);
                boolean outsideWaitingArea = Math.abs(offsetX) > WAITING_AREA_RADIUS - 1
                    || Math.abs(offsetZ) > WAITING_AREA_RADIUS - 1
                    || player.getY() < WAITING_AREA_Y;
                if (!player.getPersistentData().getBoolean(PLAYER_WAITING)
                    || outsideWaitingArea) {
                    BlockPos waitingArea = createWaitingArea(level);
                    movePlayerToWaitingArea(player, level, waitingArea);
                }
            }
            return;
        }
        if (activeHexWorld == null) {
            return;
        }
        ResourceLocation entityType = BuiltInRegistries.ENTITY_TYPE.getKey(
            event.getEntity().getType()
        );
        if (entityType.getNamespace().equals("cobblemon")
            && terrainAt(activeHexWorld, event.getEntity().getX(), event.getEntity().getZ()) == null) {
            event.setCanceled(true);
        }
    }

    private static void onServerStarted(ServerStartedEvent event) {
        pendingInitializationPlayer = null;
        pendingInitializationTicks = -1;
        activeInitialization = null;
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            throw new IllegalStateException("Cobbleventure generation_1 dimension is missing");
        }

        boolean nativeGenerator = NativeWorldGeneration.usesNativeGenerator(
            level.getChunkSource().getGenerator()
        );
        if (!nativeGenerator) {
            BlockPos surface = surfacePosition(level, 0, 0);
            if (!level.getBiome(surface).is(STARTER_BIOME)) {
                throw new IllegalStateException("Cobbleventure starter_plains biome is missing at spawn");
            }
            if (surface.getY() != BCA_REFERENCE_SURFACE_Y
                && surface.getY() != LEGACY_SURFACE_Y
                && surface.getY() != SEALED_OUTER_SURFACE_Y) {
                throw new IllegalStateException(
                    "Cobbleventure generation_1 base surface height must be "
                        + BCA_REFERENCE_SURFACE_Y + ", " + LEGACY_SURFACE_Y
                        + " or " + SEALED_OUTER_SURFACE_Y
                        + ", but was " + surface.getY()
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
            boolean sealedOuterTerrain = surface.getY() == SEALED_OUTER_SURFACE_Y
                && level.getBlockState(new BlockPos(0, 91, 0)).is(Blocks.GRASS_BLOCK)
                && level.getBlockState(new BlockPos(0, 60, 0)).is(Blocks.STONE);
            boolean legacyTerrain = surface.getY() == LEGACY_SURFACE_Y
                && level.getBlockState(new BlockPos(0, 68, 0)).is(Blocks.GRASS_BLOCK)
                && emptyBelowFoundation;
            boolean bcaAlignedTerrain = surface.getY() == BCA_REFERENCE_SURFACE_Y
                && level.getBlockState(new BlockPos(0, 67, 0)).is(Blocks.GRASS_BLOCK)
                && emptyBelowFoundation;
            if ((!deepFoundation && !previousFoundation && !legacyFoundation)
                || (!sealedOuterTerrain && !legacyTerrain && !bcaAlignedTerrain)) {
                throw new IllegalStateException(
                    "Cobbleventure generation_1 has an unsupported base terrain layout"
                );
            }
            LOGGER.info(
                "Cobbleventure legacy generation_1 ready: biome={}, surfaceY={}",
                STARTER_BIOME.location(), surface.getY()
            );
        } else {
            LOGGER.info(
                "Cobbleventure native generation_1 ready: terrain is generated per chunk"
            );
        }

        RuntimeWorld runtime = loadRuntimeWorld(level);
        activeHexWorld = runtime.hexWorld();
        activeSettlements = runtime.settlements();
        activeFacilityPortals = facilityPortals(runtime.settlements());

        if (nativeGenerator) {
            SettlementPlan starter = runtime.settlements().get(STARTER_SETTLEMENT);
            if (starter == null) {
                throw new IllegalStateException("Cobbleventure starter settlement is missing");
            }
            BlockPos starterSurface = surfacePosition(
                level, starter.center().x(), starter.center().z()
            );
            if (!level.getBiome(starterSurface).is(STARTER_BIOME)) {
                throw new IllegalStateException(
                    "Native generator did not assign starter_plains at the starter town"
                );
            }
            if (!hasBedrockFoundation(
                level, starter.center().x(), starter.center().z(),
                DEEP_FOUNDATION_MIN_Y, DEEP_FOUNDATION_MAX_Y
            )) {
                throw new IllegalStateException(
                    "Native generator did not create the deep bedrock foundation"
                );
            }
            LOGGER.info(
                "Cobbleventure native starter chunk verified: position={}, surfaceY={}",
                starterSurface, starterSurface.getY()
            );
        }

        if (Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY)) {
            if (!Boolean.getBoolean(PERFORMANCE_TEST_PROPERTY)) {
                verifyTerrainRelief(runtime.hexWorld());
                verifyBoundaryWarp(runtime.hexWorld());
            }
            if (nativeGenerator) {
                generateNativeTestArea(level, runtime.hexWorld());
            } else {
                drawHexWorld(
                    level, runtime.hexWorld(), Boolean.getBoolean(TEST_CLEAN_EXISTING_PROPERTY),
                    new GenerationProgress(null)
                );
            }
            if (Boolean.getBoolean(PERFORMANCE_TEST_PROPERTY)
                && Integer.getInteger(TEST_RENDER_RADIUS_PROPERTY, 0) > 0) {
                LOGGER.info(
                    "Cobbleventure cropped world generation performance test succeeded: radius={}",
                    Integer.getInteger(TEST_RENDER_RADIUS_PROPERTY, 0)
                );
                integrationShutdownTicks = 200;
                return;
            }
            if (nativeGenerator) {
                verifyNativeWorld(level, runtime.hexWorld());
            } else {
                verifyHexWorld(level, runtime.hexWorld());
            }
            for (SettlementPlan settlement : runtime.settlements().values()) {
                if (settlement.enabled() && !placeTown(level, settlement)) {
                    throw new IllegalStateException(
                        "Cobbleventure town placement integration test failed: " + settlement.id()
                    );
                }
            }
            for (SettlementPlan settlement : runtime.settlements().values()) {
                if (settlement.enabled() && !placeFacilities(level, settlement)) {
                    throw new IllegalStateException(
                        "Town facility integration placement failed: " + settlement.id()
                    );
                }
            }
            LOGGER.info("Cobbleventure hex world rendering integration test succeeded");
            integrationShutdownTicks = 200;
        }

        if (Boolean.getBoolean(INTEGRATION_TEST_PROPERTY)) {
            for (SettlementPlan settlement : runtime.settlements().values()) {
                if (!settlement.enabled()) {
                    continue;
                }
                int preparationChunks = townPreparationChunkKeys(settlement).size();
                if (preparationChunks > MAX_TOWN_PREPARATION_CHUNKS) {
                    throw new IllegalStateException(
                        "Town preparation still requests too many chunks: "
                            + settlement.id() + " (" + preparationChunks + ")"
                    );
                }
                LOGGER.info(
                    "Town preparation footprint verified: settlement={}, chunks={}",
                    settlement.id(), preparationChunks
                );
            }
            SettlementPlan starter = runtime.settlements().get(STARTER_SETTLEMENT);
            if (starter == null || !placeTown(level, starter) || !placeFacilities(level, starter)) {
                throw new IllegalStateException("Cobbleventure starter town integration placement failed");
            }
            LOGGER.info("Cobbleventure starter town integration placement succeeded at {}", starter.center());
            integrationShutdownTicks = 40;
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

    private static void generateNativeTestArea(
        ServerLevel level, HexWorldPlan world
    ) {
        long started = System.nanoTime();
        int generated = 0;
        int radius = Integer.getInteger(TEST_RENDER_RADIUS_PROPERTY, 0);
        HexSettlement starter = world.settlements().get(STARTER_SETTLEMENT);
        if (radius > 0 && starter != null) {
            Point center = townFootprintWorldCenter(world.grid(), starter);
            int minChunkX = (center.x() - radius) >> 4;
            int maxChunkX = (center.x() + radius) >> 4;
            int minChunkZ = (center.z() - radius) >> 4;
            int maxChunkZ = (center.z() + radius) >> 4;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    level.getChunk(chunkX, chunkZ);
                    generated++;
                }
            }
        } else {
            Set<ChunkPos> chunks = new HashSet<>();
            for (HexCoord cell : worldRenderCells(world)) {
                Point center = world.grid().worldCenter(cell);
                chunks.add(new ChunkPos(center.x() >> 4, center.z() >> 4));
            }
            for (ChunkPos chunk : chunks) {
                level.getChunk(chunk.x, chunk.z);
                generated++;
            }
        }
        LOGGER.info(
            "Native worldgen test chunks ready: chunks={}, elapsedMs={}",
            generated, (System.nanoTime() - started) / 1_000_000L
        );
    }

    private static void verifyNativeWorld(
        ServerLevel level, HexWorldPlan world
    ) {
        int verified = 0;
        for (HexCoord cell : worldRenderCells(world)) {
            Point center = world.grid().worldCenter(cell);
            TerrainSample expected = terrainAt(
                world, center.x() + 0.5D, center.z() + 0.5D
            );
            if (expected == null) {
                continue;
            }
            BlockPos surface = surfacePosition(level, center.x(), center.z());
            ResourceLocation actual = level.getBiome(surface).unwrapKey()
                .orElseThrow(() -> new IllegalStateException(
                    "Native biome has no registry key at " + surface
                ))
                .location();
            if (!actual.toString().equals(expected.biome())) {
                throw new IllegalStateException(
                    "Native biome mismatch at " + surface + ": expected="
                        + expected.biome() + ", actual=" + actual
                );
            }
            verified++;
        }
        LOGGER.info("Native JSON world verification succeeded: samples={}", verified);
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
            BlockPos waitingArea = createWaitingArea(generationOne);
            movePlayerToWaitingArea(player, generationOne, waitingArea);
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 전용 시작 바이옴과 마을을 준비하고 있습니다..."
            ));
            scheduleWorldInitialization(player);
            return;
        }

        if (!player.getPersistentData().getBoolean(PLAYER_STARTED)) {
            movePlayerToStart(player, generationOne, data.spawnPos());
        }
    }

    private static boolean initializeWorld(
        ServerLevel level,
        ServerPlayer firstPlayer,
        BootstrapSavedData data,
        BlockPos waitingArea
    ) {
        GenerationProgress progress = new GenerationProgress(firstPlayer);
        progress.update(1, "마을 지도 데이터 읽는 중");
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
        activeSettlements = settlements;
        SettlementPlan starter = settlements.get(STARTER_SETTLEMENT);
        if (starter == null) {
            firstPlayer.sendSystemMessage(Component.literal(
                "[Cobbleventure] 시작 마을 데이터가 없습니다."
            ));
            return false;
        }
        progress.update(5, "안전한 대기 장소 준비 완료");
        boolean nativeGenerator = NativeWorldGeneration.usesNativeGenerator(
            level.getChunkSource().getGenerator()
        );
        if (nativeGenerator) {
            activeHexWorld = runtime.hexWorld();
            activeShoreDistances = null;
            progress.update(85, "필요한 청크를 월드젠 워커에서 생성할 준비 완료");
            LOGGER.info(
                "Skipped legacy fillbiome and post-generation terrain rendering; native chunk generation is active"
            );
        } else {
            try {
                drawHexWorld(level, runtime.hexWorld(), data.hasExistingMap(), progress);
            } catch (RuntimeException error) {
                LOGGER.error("Settlement map drawing failed", error);
                firstPlayer.sendSystemMessage(Component.literal(
                    "[Cobbleventure] 마을 지도 생성에 실패했습니다. 서버 로그를 확인하세요."
                ));
                return false;
            }
        }
        BlockPos spawnPos = starter.playerSpawn().toBlockPos();
        BlockPos villagePos = townSurfacePosition(level, starter);
        level.setDefaultSpawnPos(spawnPos, 0.0F);
        int enabledSettlements = (int) settlements.values().stream()
            .filter(SettlementPlan::enabled)
            .count();
        if (nativeGenerator) {
            activeInitialization = new WorldInitializationJob(
                level, firstPlayer, data, waitingArea, runtime,
                spawnPos, villagePos, List.of(starter), progress, true
            );
            progress.update(86, "시작 마을 청크를 빠르게 생성합니다");
            LOGGER.info(
                "Fast starter town initialization scheduled: settlement={}, chunksPerTick={}",
                starter.id(), STARTER_TOWN_CHUNKS_PER_TICK
            );
            return true;
        }
        int placedSettlements = 0;
        for (SettlementPlan settlement : settlements.values()) {
            if (settlement.enabled()) {
                progress.update(
                    86 + (placedSettlements * 12 / Math.max(1, enabledSettlements)),
                    "마을 구조물 배치 중: " + settlement.id()
                );
            }
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
            if (settlement.enabled()) {
                decorateTownLandscape(level, runtime.hexWorld(), settlement);
                placedSettlements++;
            }
        }

        activeFacilityPortals = facilityPortals(settlements);

        data.complete(spawnPos, villagePos, MAP_VERSION);
        progress.update(100, "시작 지역 생성 완료");
        moveWaitingPlayersToStart(level, spawnPos);
        removeWaitingArea(level, waitingArea);
        firstPlayer.sendSystemMessage(Component.literal(
            "[Cobbleventure] 마을 데이터로 1세대 시작 지역과 연결 통로를 생성했습니다."
        ));
        return true;
    }

    private static boolean placeTown(ServerLevel level, SettlementPlan settlement) {
        BlockPos villagePos = surfacePosition(
            level, settlement.center().x(), settlement.center().z()
        ).below();
        drawNativeTownRoadSkeleton(level, settlement);
        connectTownRoadsToRegionalRoutes(level, settlement);
        LOGGER.info(
            "Native configured town base placed without BCA village: {} at {}, shape={}, road={}x{}",
            settlement.id(), villagePos, settlement.layoutShape(),
            settlement.roadProfile().width(), settlement.roadProfile().material()
        );
        return true;
    }

    private static void drawNativeTownRoadSkeleton(
        ServerLevel level, SettlementPlan settlement
    ) {
        long startedAt = System.nanoTime();
        Point center = new Point(settlement.center().x(), settlement.center().z());
        RoadProfile road = settlement.roadProfile();
        TownLayout layout = generateTownLayout(settlement);
        int plazaRadius = Math.max(5, road.width());
        Set<Long> roadColumns = new HashSet<>();
        for (int x = center.x() - plazaRadius; x <= center.x() + plazaRadius; x++) {
            for (int z = center.z() - plazaRadius; z <= center.z() + plazaRadius; z++) {
                if (Math.hypot(x - center.x(), z - center.z()) <= plazaRadius + 0.5D) {
                    roadColumns.add(blockColumnKey(x, z));
                }
            }
        }
        for (TownRoad generatedRoad : layout.roads()) {
            collectConfiguredRoadColumns(
                roadColumns,
                center.translate(generatedRoad.x1(), generatedRoad.z1()),
                center.translate(generatedRoad.x2(), generatedRoad.z2()),
                road.width()
            );
        }
        for (TownRoad accessRoad : layout.accessRoads()) {
            collectConfiguredRoadColumns(
                roadColumns,
                center.translate(accessRoad.x1(), accessRoad.z1()),
                center.translate(accessRoad.x2(), accessRoad.z2()),
                Math.min(3, road.width())
            );
        }
        for (long key : roadColumns) {
            paintConfiguredRoadColumn(
                level, blockColumnX(key), blockColumnZ(key), road.material()
            );
        }
        long roadsFinishedAt = System.nanoTime();

        List<TownTemplatePlacement> housePlacements = new ArrayList<>();
        int smallVariant = 0;
        for (TownPlot house : layout.houses()) {
            String compiledStructure = house.structure();
            int paletteIndex = Math.min(
                settlement.basicBuildings().size() - 1,
                smallVariant++ % Math.max(1, settlement.basicBuildings().size())
            );
            String structure = compiledStructure != null && !compiledStructure.isBlank()
                ? compiledStructure : settlement.basicBuildings().get(paletteIndex);
            int x = center.x() + (int) Math.round(house.x());
            int z = center.z() + (int) Math.round(house.z());
            clearVegetationAroundPlot(level, x, z, house.width(), house.depth(), 6);
            int groundY = plannedTerrainGroundY(level, x, z);
            BlockPoint origin = rotatedTemplateOrigin(
                x, groundY, z, house.width(), house.depth(), house.rotation()
            );
            housePlacements.add(new TownTemplatePlacement(
                structure, origin, house.rotation()
            ));
        }
        preloadTemplateChunks(level, housePlacements);
        for (TownTemplatePlacement placement : housePlacements) {
            if (!placeTemplateLoaded(
                level, placement.structure(), placement.position(), placement.rotation()
            )) {
                String structure = placement.structure();
                throw new IllegalStateException("Basic building NBT placement failed: " + structure);
            }
        }
        // Structure NBTs may contain air padding at their outer edge. Repaint the
        // authored road mask after placement so every doorway remains physically
        // connected even when that padding overlaps the road boundary.
        for (long key : roadColumns) {
            paintConfiguredRoadColumn(
                level, blockColumnX(key), blockColumnZ(key), road.material()
            );
        }
        long housesFinishedAt = System.nanoTime();
        LOGGER.info(
            "Generated town layout applied: settlement={}, seed={}, depth={}, roads={}, facilities={}, houses={}, roadColumns={}, roadMs={}, houseMs={}, totalMs={}",
            settlement.id(), settlement.generationSeed(), settlement.generationDepth(),
            layout.roads().size(), layout.facilities().size(), layout.houses().size(),
            roadColumns.size(),
            (roadsFinishedAt - startedAt) / 1_000_000L,
            (housesFinishedAt - roadsFinishedAt) / 1_000_000L,
            (housesFinishedAt - startedAt) / 1_000_000L
        );
    }

    private static void collectConfiguredRoadColumns(
        Set<Long> columns, Point start, Point end, int width
    ) {
        int dx = end.x() - start.x();
        int dz = end.z() - start.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int radius = Math.max(1, width / 2);
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.x() + dx * factor);
            int z = (int) Math.round(start.z() + dz * factor);
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (offsetX * offsetX + offsetZ * offsetZ
                        <= radius * radius + radius) {
                        columns.add(blockColumnKey(x + offsetX, z + offsetZ));
                    }
                }
            }
        }
    }

    private static long blockColumnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int blockColumnX(long key) {
        return (int) (key >> 32);
    }

    private static int blockColumnZ(long key) {
        return (int) key;
    }

    private static TownLayout generateTownLayout(SettlementPlan settlement) {
        if (settlement.compiledLayout() != null) {
            return settlement.compiledLayout();
        }
        PreviewRandom random = new PreviewRandom(settlement.generationSeed());
        int[][] directions = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        int[] initialDirections = switch (settlement.layoutShape()) {
            case "linear" -> new int[] {1, 3};
            case "radial", "loop" -> new int[] {0, 1, 2, 3};
            case "terraced" -> new int[] {1, 3, 2};
            default -> new int[] {0, 1, 2, 3};
        };
        ArrayDeque<TownConnector> queue = new ArrayDeque<>();
        for (int direction : initialDirections) {
            queue.add(new TownConnector(0, 0, direction, 0));
        }
        Set<String> occupiedRoad = new HashSet<>();
        occupiedRoad.add("0,0");
        List<TownRoad> roads = new ArrayList<>();
        int maximumRoads = Math.min(56, 5 + settlement.generationDepth() * 8);
        while (!queue.isEmpty() && roads.size() < maximumRoads) {
            TownConnector connector = queue.removeFirst();
            int[] vector = directions[connector.direction()];
            int cells = 3 + (int) Math.floor(random.nextDouble() * 4.0D);
            List<Point> points = new ArrayList<>();
            boolean blocked = false;
            for (int step = 1; step <= cells; step++) {
                int cellX = connector.x() / 16 + vector[0] * step;
                int cellZ = connector.z() / 16 + vector[1] * step;
                String key = cellX + "," + cellZ;
                if (occupiedRoad.contains(key) && step > 1) {
                    blocked = true;
                    break;
                }
                points.add(new Point(cellX * 16, cellZ * 16));
            }
            if (blocked || points.size() < 2) continue;
            for (Point point : points) occupiedRoad.add(point.x() / 16 + "," + point.z() / 16);
            Point end = points.get(points.size() - 1);
            roads.add(new TownRoad(connector.x(), connector.z(), end.x(), end.z()));
            if (connector.depth() + 1 >= settlement.generationDepth()) continue;
            List<Integer> nextDirections = new ArrayList<>();
            nextDirections.add(connector.direction());
            double branchChance = switch (settlement.layoutShape()) {
                case "linear" -> 0.12D;
                case "radial" -> 0.20D;
                case "loop" -> 0.34D;
                default -> 0.55D;
            };
            double branchRoll = random.nextDouble();
            if ((settlement.layoutShape().equals("branching") && connector.depth() == 0)
                || branchRoll < branchChance) {
                nextDirections.add(Math.floorMod(connector.direction() + (random.nextDouble() < 0.5D ? 1 : 3), 4));
            }
            if (settlement.layoutShape().equals("terraced") && connector.depth() % 2 == 1
                && random.nextDouble() < 0.60D) {
                nextDirections.add((connector.direction() + 1) % 4);
            }
            Set<Integer> queuedDirections = new HashSet<>();
            for (int direction : nextDirections) {
                if (queuedDirections.add(direction)) {
                    queue.add(new TownConnector(end.x(), end.z(), direction, connector.depth() + 1));
                }
            }
        }

        List<TownSlot> slots = new ArrayList<>();
        double[] ratios = buildingDensityRatios(settlement.buildingDensity());
        double plotGap = buildingDensityGap(settlement.buildingDensity());
        for (int roadIndex = 0; roadIndex < roads.size(); roadIndex++) {
            for (double ratio : ratios) {
                slots.add(new TownSlot(roadIndex, ratio, -1));
                slots.add(new TownSlot(roadIndex, ratio, 1));
            }
        }
        for (int index = slots.size() - 1; index > 0; index--) {
            int swap = (int) Math.floor(random.nextDouble() * (index + 1));
            TownSlot value = slots.get(index);
            slots.set(index, slots.get(swap));
            slots.set(swap, value);
        }
        List<TownPlot> plots = new ArrayList<>();
        Map<String, TownPlot> facilities = new LinkedHashMap<>();
        for (FacilityPlacement facility : settlement.facilities()) {
            if (!facility.id().startsWith("facility_")
                && !facility.id().contains("gym")) continue;
            TownPlot plot = tryPlaceTownPlot(
                roads, slots, plots, random, settlement.roadProfile().width(),
                facility.footprintWidth(), facility.footprintDepth(), facility.id(),
                Math.max(slots.size(), slots.size() * 3), plotGap
            );
            if (plot == null) {
                LOGGER.warn(
                    "Generated layout could not reserve facility lot; configured anchor fallback will be used: settlement={}, facility={}",
                    settlement.id(), facility.id()
                );
                continue;
            }
            facilities.put(facility.id(), plot);
        }
        int baseHouseTarget = Math.min(30, Math.max(4, roads.size() + (int) Math.floor(settlement.generationDepth() * 1.5D)));
        int houseTarget = Math.max(2, (int) Math.round(
            baseHouseTarget * buildingDensityMultiplier(settlement.buildingDensity())
        ));
        List<TownPlot> houses = new ArrayList<>();
        for (int index = 0; index < houseTarget; index++) {
            TownPlot plot = tryPlaceTownPlot(
                roads, slots, plots, random, settlement.roadProfile().width(),
                16, 16, "house_" + (index + 1), 18, plotGap
            );
            if (plot != null) houses.add(plot);
        }
        List<TownRoad> accessRoads = Stream.concat(
                facilities.values().stream(), houses.stream()
            )
            .map(CobbleventureBootstrap::townPlotAccessRoad)
            .toList();
        return new TownLayout(
            List.copyOf(roads), List.copyOf(accessRoads),
            Map.copyOf(facilities), List.copyOf(houses), List.of()
        );
    }

    private static TownPlot tryPlaceTownPlot(
        List<TownRoad> roads, List<TownSlot> slots, List<TownPlot> plots,
        PreviewRandom random, int roadWidth, int width, int depth, String id, int attempts,
        double plotGap
    ) {
        if (slots.isEmpty()) return null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int slotIndex = Math.floorMod(
                attempt + (int) Math.floor(random.nextDouble() * slots.size()), slots.size()
            );
            TownSlot slot = slots.get(slotIndex);
            TownRoad road = roads.get(slot.roadIndex());
            boolean horizontal = road.z1() == road.z2();
            double alongX = road.x1() + (road.x2() - road.x1()) * slot.ratio();
            double alongZ = road.z1() + (road.z2() - road.z1()) * slot.ratio();
            double distance = roadWidth / 2.0D + (horizontal ? depth : width) / 2.0D;
            double centerX = alongX + (horizontal ? 0.0D : slot.side() * distance);
            double centerZ = alongZ + (horizontal ? slot.side() * distance : 0.0D);
            String facing = horizontal
                ? (slot.side() < 0 ? "south" : "north")
                : (slot.side() < 0 ? "east" : "west");
            if (!id.startsWith("house_")
                && !facing.equals(facilityCanonicalEntranceFacing(id))) {
                continue;
            }
            String rotation = id.startsWith("house_") ? switch (facing) {
                case "east" -> "clockwise_90";
                case "south" -> "clockwise_180";
                case "west" -> "counterclockwise_90";
                default -> "none";
            } : "none";
            TownPlot candidate = new TownPlot(
                centerX - width / 2.0D, centerZ - depth / 2.0D,
                width, depth, id, null, rotation,
                (int) Math.round(alongX), (int) Math.round(alongZ)
            );
            boolean intersects = plots.stream().anyMatch(plot -> townPlotsIntersect(candidate, plot, plotGap));
            if (intersects) continue;
            for (int roadIndex = 0; roadIndex < roads.size(); roadIndex++) {
                if (roadIndex == slot.roadIndex()) continue;
                if (townPlotIntersectsRoad(candidate, roads.get(roadIndex), roadWidth + 3, 2.0D)) {
                    intersects = true;
                    break;
                }
            }
            if (intersects) continue;
            plots.add(candidate);
            return candidate;
        }
        return null;
    }

    private static double[] buildingDensityRatios(String density) {
        return switch (density) {
            case "sparse" -> new double[] {0.22D, 0.50D, 0.78D};
            case "dense" -> new double[] {0.08D, 0.22D, 0.36D, 0.50D, 0.64D, 0.78D, 0.92D};
            case "packed" -> new double[] {0.06D, 0.17D, 0.28D, 0.39D, 0.50D, 0.61D, 0.72D, 0.83D, 0.94D};
            default -> new double[] {0.15D, 0.32D, 0.50D, 0.68D, 0.85D};
        };
    }

    private static double buildingDensityGap(String density) {
        return switch (density) {
            case "sparse" -> 8.0D;
            case "dense" -> 1.0D;
            case "packed" -> 0.0D;
            default -> 4.0D;
        };
    }

    private static double buildingDensityMultiplier(String density) {
        return switch (density) {
            case "sparse" -> 0.7D;
            case "dense" -> 1.4D;
            case "packed" -> 1.8D;
            default -> 1.0D;
        };
    }

    private static TownRoad townPlotAccessRoad(TownPlot plot) {
        int x = (int) Math.round(plot.x());
        int z = (int) Math.round(plot.z());
        String facing = plot.id().startsWith("house_")
            ? switch (plot.rotation()) {
                case "clockwise_90" -> "east";
                case "clockwise_180" -> "south";
                case "counterclockwise_90" -> "west";
                default -> "north";
            }
            : facilityCanonicalEntranceFacing(plot.id());
        Point entrance = plot.id().equals("facility_pokemon_center")
            ? new Point(x - 1, z + Math.min(10, plot.depth() - 1))
            : plot.id().equals("facility_pokemart")
                ? new Point(x + plot.width(), z + Math.min(15, plot.depth() - 1))
            : switch (facing) {
            case "east" -> new Point(x + plot.width(), z + plot.depth() / 2);
            case "south" -> new Point(x + plot.width() / 2, z + plot.depth());
            case "west" -> new Point(
                x - 1, z + (plot.id().contains("gym") ? Math.min(10, plot.depth() - 1) : plot.depth() / 2)
            );
            default -> new Point(x + plot.width() / 2, z - 1);
        };
        return new TownRoad(
            plot.roadConnectionX(), plot.roadConnectionZ(), entrance.x(), entrance.z()
        );
    }

    private static String facilityCanonicalEntranceFacing(String facilityId) {
        return switch (facilityId) {
            case "facility_pokemon_center" -> "west";
            case "facility_pokemart" -> "east";
            case "facility_department_store" -> "north";
            default -> facilityId.contains("gym") ? "west" : "north";
        };
    }

    private static BlockPoint rotatedTemplateOrigin(
        int x, int y, int z, int width, int depth, String rotation
    ) {
        return switch (rotation) {
            case "clockwise_90" -> new BlockPoint(x + depth - 1, y, z);
            case "clockwise_180" -> new BlockPoint(x + width - 1, y, z + depth - 1);
            case "counterclockwise_90" -> new BlockPoint(x, y, z + width - 1);
            default -> new BlockPoint(x, y, z);
        };
    }

    private static boolean townPlotsIntersect(TownPlot a, TownPlot b, double margin) {
        return a.x() - margin < b.x() + b.width() && a.x() + a.width() + margin > b.x()
            && a.z() - margin < b.z() + b.depth() && a.z() + a.depth() + margin > b.z();
    }

    private static boolean townPlotIntersectsRoad(TownPlot plot, TownRoad road, int width, double margin) {
        TownPlot roadRect = new TownPlot(
            Math.min(road.x1(), road.x2()) - width / 2.0D,
            Math.min(road.z1(), road.z2()) - width / 2.0D,
            Math.abs(road.x2() - road.x1()) + width,
            Math.abs(road.z2() - road.z1()) + width,
            "road"
        );
        return townPlotsIntersect(plot, roadRect, margin);
    }

    private static void cleanupTownAssemblyMarkers(
        ServerLevel level, SettlementPlan settlement
    ) {
        int centerX = settlement.center().x();
        int centerZ = settlement.center().z();
        int radius = (int) Math.ceil(TOWN_STRUCTURE_MAX_RADIUS_BLOCKS + 4.0D);
        int removedJigsaws = 0;
        int removedStructureVoids = 0;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (Math.hypot(x - centerX, z - centerZ) > radius) {
                    continue;
                }
                int baseY = townGenerationBaseHeight(
                    level, x, z, Heightmap.Types.WORLD_SURFACE_WG
                ) - 1;
                HexWorldPlan world = activeHexWorld;
                TerrainSample sample = world == null
                    ? null : terrainAt(world, x + 0.5D, z + 0.5D);
                int minY = Math.max(level.getMinBuildHeight(), baseY - 2);
                int maxY = Math.min(level.getMaxBuildHeight() - 1, baseY + 32);
                for (int y = minY; y <= maxY; y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(position);
                    BlockState replacement = y > baseY
                        ? Blocks.AIR.defaultBlockState()
                        : y == baseY
                            ? surfaceBlock(sample == null ? "minecraft:plains" : sample.biome())
                            : fillerBlock(sample == null ? "minecraft:plains" : sample.biome());
                    if (state.is(Blocks.JIGSAW)) {
                        level.setBlock(position, replacement, 2);
                        removedJigsaws++;
                    } else if (state.is(Blocks.STRUCTURE_VOID)) {
                        level.setBlock(position, replacement, 2);
                        removedStructureVoids++;
                    }
                }
            }
        }
        LOGGER.info(
            "Town assembly markers cleaned: settlement={}, jigsaws={}, structureVoids={}",
            settlement.id(), removedJigsaws, removedStructureVoids
        );
    }

    private static int townGenerationBaseHeight(
        ServerLevel level, int x, int z, Heightmap.Types heightmap
    ) {
        HexWorldPlan world = activeHexWorld;
        TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample == null) {
            return level.getHeight(heightmap, x, z);
        }
        int groundY = terrainGroundY(world, sample, x, z);
        if (isAquatic(sample) || isCoastalWater(world, sample, x, z, groundY)) {
            return WATER_SURFACE_Y + 1;
        }
        return groundY + 1;
    }

    private static BlockPos townSurfacePosition(
        ServerLevel level, SettlementPlan settlement
    ) {
        BlockPoint configured = settlement.structurePoint();
        return surfacePosition(level, configured.x(), configured.z()).below();
    }

    private static boolean placeFacilities(ServerLevel level, SettlementPlan settlement) {
        List<FacilitySite> configuredSites = new ArrayList<>();
        for (FacilityPlacement facility : settlement.facilities()) {
            BlockPoint position;
            if (facility.mode().equals("instanced_entry")) {
                position = facility.instanceOrigin();
            } else if (facility.mode().equals("direct_template")
                || facility.mode().equals("placeholder")) {
                position = resolveDirectFacilityPosition(level, settlement, facility);
            } else {
                LOGGER.error("Unknown facility placement mode: {}", facility.mode());
                return false;
            }
            if (position != null && (facility.id().equals("special_district_building")
                || facility.id().startsWith("facility_")
                || facility.id().contains("gym"))) {
                prepareSpecialDistrict(level, facility, position);
            }
            boolean placed = position != null && (facility.mode().equals("placeholder")
                ? placeFacilityPlaceholder(level, facility, position)
                : facility.mode().equals("direct_template")
                    ? placeFacilityTemplate(level, facility.structure(), position)
                    : placeTemplate(level, facility.structure(), position));
            if (!placed) {
                LOGGER.error(
                    "Facility placement failed for {} / {} at {}",
                    settlement.id(), facility.id(), position
                );
                return false;
            }
            if (facility.mode().equals("direct_template")) {
                cleanupFacilityTemplateMarkers(level, facility.structure(), position);
                if (!placeFacilityWorkers(level, facility, position)) {
                    return false;
                }
            }
            if (facility.id().startsWith("facility_")) {
                configuredSites.add(new FacilitySite(facility, position));
            }
            if (facility.mode().equals("direct_template")
                && facility.id().contains("gym")) {
                drawGymRingRoad(level, settlement, facility, position);
                drawGymEntranceApron(level, settlement, facility, position);
            }
        }
        drawFacilityRoadNetwork(level, settlement, configuredSites);
        return true;
    }

    private static void cleanupFacilityTemplateMarkers(
        ServerLevel level, String structure, BlockPoint origin
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return;
        var size = template.get().getSize();
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos position = new BlockPos(
                        origin.x() + x, origin.y() + y, origin.z() + z
                    );
                    BlockState state = level.getBlockState(position);
                    if (state.is(Blocks.JIGSAW)) {
                        BlockState replacement = Blocks.AIR.defaultBlockState();
                        if (level.getBlockEntity(position) instanceof JigsawBlockEntity jigsaw) {
                            try {
                                replacement = BlockStateParser.parseForBlock(
                                    level.holderLookup(Registries.BLOCK),
                                    jigsaw.getFinalState(), false
                                ).blockState();
                            } catch (CommandSyntaxException error) {
                                LOGGER.warn(
                                    "Invalid facility jigsaw final_state: structure={}, position={}, state={}",
                                    structure, position, jigsaw.getFinalState()
                                );
                            }
                        }
                        level.setBlock(position, replacement, 2);
                    } else if (state.is(Blocks.STRUCTURE_VOID)) {
                        level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static boolean placeFacilityWorkers(
        ServerLevel level, FacilityPlacement facility, BlockPoint origin
    ) {
        for (FacilityWorkerPlacement worker : facilityWorkers(facility.id())) {
            BlockPoint position = origin.plus(worker.offset());
            if (!placeTemplate(level, worker.structure(), position)) {
                LOGGER.error(
                    "Required facility worker placement failed: facility={}, structure={}, position={}",
                    facility.id(), worker.structure(), position
                );
                return false;
            }
            cleanupFacilityTemplateMarkers(level, worker.structure(), position);
        }
        return true;
    }

    private static List<FacilityWorkerPlacement> facilityWorkers(String facilityId) {
        return switch (facilityId) {
            case "facility_pokemon_center" -> List.of(
                facilityWorker("nurse_joy", 16, 5, 10)
            );
            case "facility_pokemart" -> List.of(
                facilityWorker("pokemart_shopkeeper", 7, 2, 17)
            );
            case "facility_department_store" -> List.of(
                facilityWorker("shopkeeper_ds_vitamins", 8, 1, 41),
                facilityWorker("shopkeeper_ds_battle_items", 8, 1, 43),
                facilityWorker("shopkeeper_ds_tech", 10, 1, 56),
                facilityWorker("shopkeeper_ds_general", 19, 1, 50),
                facilityWorker("shopkeeper_ds_special_balls", 19, 1, 52),
                facilityWorker("shopkeeper_ds_food", 29, 1, 56),
                facilityWorker("store_worker_currency-exchange", 9, 8, 41),
                facilityWorker("shopkeeper_ds_held_items_2", 10, 8, 51),
                facilityWorker("shopkeeper_ds_ev-stone", 17, 8, 56),
                facilityWorker("shopkeeper_ds_ev-stone_2", 21, 8, 56),
                facilityWorker("shopkeeper_ds_held_items", 28, 8, 51),
                facilityWorker("shopkeeper_ds_xp", 29, 8, 41),
                facilityWorker("shopkeeper_ds_apricorns", 7, 18, 44),
                facilityWorker("shopkeeper_ds_mulch", 19, 18, 63)
            );
            default -> List.of();
        };
    }

    private static FacilityWorkerPlacement facilityWorker(
        String resource, int x, int y, int z
    ) {
        return new FacilityWorkerPlacement(
            "bca:stores/store_workers/" + resource, new BlockPoint(x, y, z)
        );
    }

    private static void drawFacilityRoadNetwork(
        ServerLevel level, SettlementPlan settlement, List<FacilitySite> sites
    ) {
        if (sites.isEmpty()) return;
        Point center = new Point(settlement.center().x(), settlement.center().z());
        for (FacilitySite site : sites) {
            for (Point entrance : facilityEntrances(site)) {
                int clearance = Math.max(2, site.facility().clearance());
                Point road = findNearestVillageRoad(
                    level, entrance, 72,
                    site.origin().x() - clearance,
                    site.origin().z() - clearance,
                    site.origin().x() + site.facility().footprintWidth() + clearance,
                    site.origin().z() + site.facility().footprintDepth() + clearance
                );
                drawFacilityApproachRoad(
                    level, site, entrance, road == null ? center : road,
                    settlement.roadProfile()
                );
            }
        }
        LOGGER.info(
            "Entrance-aware facility roads completed: settlement={}, width={}, material={}, facilities={}",
            settlement.id(), settlement.roadProfile().width(),
            settlement.roadProfile().material(), sites.size()
        );
    }

    private static void drawFacilityApproachRoad(
        ServerLevel level,
        FacilitySite site,
        Point entrance,
        Point road,
        RoadProfile profile
    ) {
        int minX = site.origin().x();
        int maxX = minX + Math.max(8, site.facility().footprintWidth()) - 1;
        int minZ = site.origin().z();
        boolean roadBehindBuilding = road.z() >= minZ
            && road.x() >= minX && road.x() <= maxX;
        if (roadBehindBuilding) {
            int clearance = Math.max(2, site.facility().clearance());
            int left = minX - clearance - 2;
            int right = maxX + clearance + 2;
            int detourX = Math.abs(entrance.x() - left) <= Math.abs(right - entrance.x())
                ? left : right;
            Point frontCorner = new Point(detourX, entrance.z());
            Point rearCorner = new Point(detourX, road.z());
            drawConfiguredRoad(level, entrance, frontCorner, profile);
            drawConfiguredRoad(level, frontCorner, rearCorner, profile);
            drawConfiguredRoad(level, rearCorner, road, profile);
            return;
        }
        Point corner = new Point(road.x(), entrance.z());
        drawConfiguredRoad(level, entrance, corner, profile);
        drawConfiguredRoad(level, corner, road, profile);
    }

    private static List<Point> facilityEntrances(FacilitySite site) {
        int width = Math.max(8, site.facility().footprintWidth());
        int depth = Math.max(8, site.facility().footprintDepth());
        int clearance = Math.max(2, site.facility().clearance());
        if (site.facility().id().equals("facility_department_store")) {
            int plazaZ = site.origin().z() + Math.min(18, depth - 1);
            return List.of(
                new Point(site.origin().x() + width / 2, site.origin().z() - clearance),
                new Point(site.origin().x() - clearance, plazaZ),
                new Point(site.origin().x() + width + clearance, plazaZ)
            );
        }
        if (site.facility().id().equals("facility_pokemon_center")) {
            return List.of(new Point(
                site.origin().x() - clearance,
                site.origin().z() + Math.min(10, depth - 1)
            ));
        }
        if (site.facility().id().equals("facility_pokemart")) {
            return List.of(new Point(
                site.origin().x() + width + clearance,
                site.origin().z() + Math.min(15, depth - 1)
            ));
        }
        Point entrance = switch (facilityCanonicalEntranceFacing(site.facility().id())) {
            case "east" -> new Point(
                site.origin().x() + width + clearance,
                site.origin().z() + depth / 2
            );
            case "west" -> new Point(
                site.origin().x() - clearance,
                site.origin().z() + (site.facility().id().contains("gym")
                    ? Math.min(10, depth - 1) : depth / 2)
            );
            case "south" -> new Point(
                site.origin().x() + width / 2,
                site.origin().z() + depth + clearance
            );
            default -> new Point(
                site.origin().x() + width / 2,
                site.origin().z() - clearance
            );
        };
        return List.of(entrance);
    }

    private static void drawConfiguredRoad(
        ServerLevel level, Point start, Point end, RoadProfile profile
    ) {
        int dx = end.x() - start.x();
        int dz = end.z() - start.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        int radius = Math.max(1, profile.width() / 2);
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.x() + dx * factor);
            int z = (int) Math.round(start.z() + dz * factor);
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (offsetX * offsetX + offsetZ * offsetZ <= radius * radius + radius) {
                        paintConfiguredRoadColumn(
                            level, x + offsetX, z + offsetZ, profile.material()
                        );
                    }
                }
            }
        }
    }

    private static boolean paintConfiguredRoadColumn(
        ServerLevel level, int x, int z, String material
    ) {
        HexWorldPlan world = activeHexWorld;
        TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample != null && isAquatic(sample)) return false;
        int groundY = sample == null
            ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            : terrainGroundY(world, sample, x, z);
        clearVegetationColumn(level, x, groundY, z, 32);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            BlockState current = level.getBlockState(new BlockPos(x, y, z));
            if (!current.isAir() && !current.canBeReplaced()) return false;
        }
        BlockState road = switch (material) {
            case "stone_bricks" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "gravel" -> Blocks.GRAVEL.defaultBlockState();
            case "packed_mud" -> Blocks.PACKED_MUD.defaultBlockState();
            case "sandstone" -> Blocks.SANDSTONE.defaultBlockState();
            case "snow" -> Blocks.POLISHED_DIORITE.defaultBlockState();
            default -> Blocks.COBBLESTONE.defaultBlockState();
        };
        level.setBlock(new BlockPos(x, groundY, z), road, 2);
        if (level.getBlockState(new BlockPos(x, groundY - 1, z)).isAir()) {
            level.setBlock(
                new BlockPos(x, groundY - 1, z), Blocks.COBBLESTONE.defaultBlockState(), 2
            );
        }
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return true;
    }

    private static int plannedTerrainGroundY(ServerLevel level, int x, int z) {
        HexWorldPlan world = activeHexWorld;
        TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
        return sample == null
            ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            : terrainGroundY(world, sample, x, z);
    }

    private static void clearVegetationAroundPlot(
        ServerLevel level, int originX, int originZ,
        int width, int depth, int clearance
    ) {
        for (int x = originX - clearance; x < originX + width + clearance; x++) {
            for (int z = originZ - clearance; z < originZ + depth + clearance; z++) {
                clearVegetationColumn(
                    level, x, plannedTerrainGroundY(level, x, z), z, 32
                );
            }
        }
    }

    private static void clearVegetationColumn(
        ServerLevel level, int x, int groundY, int z, int clearHeight
    ) {
        int top = Math.min(level.getMaxBuildHeight() - 1, groundY + clearHeight);
        for (int y = groundY + 1; y <= top; y++) {
            BlockPos position = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(position);
            if (state.isAir()) continue;
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS)
                || state.is(Blocks.BAMBOO) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.RED_MUSHROOM) || state.canBeReplaced()) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static void prepareSpecialDistrict(
        ServerLevel level,
        FacilityPlacement facility,
        BlockPoint origin
    ) {
        int width = Math.max(8, facility.footprintWidth());
        int depth = Math.max(8, facility.footprintDepth());
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId != null) {
            var template = level.getStructureManager().get(structureId);
            if (template.isPresent()) {
                var size = template.get().getSize();
                width = Math.max(width, size.getX());
                depth = Math.max(depth, size.getZ());
            }
        }
        int clearance = Math.max(0, facility.clearance());
        int targetY = facilityGroundLevelY(facility, origin);
        int minX = origin.x() - clearance;
        int minZ = origin.z() - clearance;
        int maxX = origin.x() + width - 1 + clearance;
        int maxZ = origin.z() + depth - 1 + clearance;
        int clearTop = Math.min(level.getMaxBuildHeight() - 1, targetY + 32);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.getChunk(x >> 4, z >> 4);
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                HexWorldPlan world = activeHexWorld;
                TerrainSample sample = world == null
                    ? null : terrainAt(world, x + 0.5D, z + 0.5D);
                String biome = sample == null ? "minecraft:plains" : sample.biome();
                if (surfaceY < targetY) {
                    for (int y = Math.max(level.getMinBuildHeight(), surfaceY + 1); y < targetY; y++) {
                        level.setBlock(new BlockPos(x, y, z), fillerBlock(biome), 2);
                    }
                    level.setBlock(new BlockPos(x, targetY, z), surfaceBlock(biome), 2);
                } else if (surfaceY > targetY) {
                    level.setBlock(new BlockPos(x, targetY, z), surfaceBlock(biome), 2);
                }
                for (int y = targetY + 1; y <= Math.max(clearTop, surfaceY); y++) {
                    if (y >= level.getMaxBuildHeight()) break;
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        LOGGER.info(
            "Special district prepared: structure={}, origin={}, footprint={}x{}, clearance={}",
            facility.structure(), origin, width, depth, clearance
        );
    }

    private static BlockPoint resolveDirectFacilityPosition(
        ServerLevel level,
        SettlementPlan settlement,
        FacilityPlacement facility
    ) {
        TownPlot generated = generateTownLayout(settlement).facilities().get(facility.id());
        if (generated != null) {
            int x = settlement.center().x() + (int) Math.round(generated.x());
            int z = settlement.center().z() + (int) Math.round(generated.z());
            int groundY = plannedTerrainGroundY(level, x, z);
            LOGGER.info(
                "Generated town facility lot selected: settlement={}, facility={}, origin=({}, {}, {})",
                settlement.id(), facility.id(), x, groundY, z
            );
            return facilityTemplateOrigin(facility, x, groundY, z);
        }
        if (facility.id().startsWith("facility_")) {
            if (generated == null) {
                return null;
            }
        }
        BlockPoint anchor = settlement.anchors().get(facility.anchor());
        if (anchor == null) {
            return null;
        }
        if (facility.mode().equals("placeholder") || !facility.id().contains("gym")) {
            return facilityTemplateOrigin(
                facility,
                anchor.x(), plannedTerrainGroundY(level, anchor.x(), anchor.z()), anchor.z()
            );
        }

        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId == null) {
            return null;
        }
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            LOGGER.error("Cannot resolve gym footprint for missing structure: {}", structureId);
            return null;
        }
        var size = template.get().getSize();
        GymLot lot = findGymLot(level, settlement, size.getX(), size.getZ());
        if (lot == null) {
            LOGGER.error(
                "No road-connected gym lot was found inside village: settlement={}, size={}x{}",
                settlement.id(), size.getX(), size.getZ()
            );
            return null;
        }
        BlockPoint resolved = lot.origin();
        LOGGER.info(
            "Gym lot selected inside assembled village: settlement={}, configured={}, resolved={}, "
                + "size={}x{}, roadSides={}, roadDistance={}, obstructions={}, score={}",
            settlement.id(), anchor, resolved, size.getX(), size.getZ(),
            lot.roadSides(), lot.roadDistance(), lot.obstructions(), lot.score()
        );
        return resolved;
    }

    private static BlockPoint facilityTemplateOrigin(
        FacilityPlacement facility, int x, int groundY, int z
    ) {
        return new BlockPoint(x, groundY - facilityGroundOffset(facility), z);
    }

    private static int facilityGroundLevelY(
        FacilityPlacement facility, BlockPoint origin
    ) {
        return origin.y() + facilityGroundOffset(facility);
    }

    private static int facilityGroundOffset(FacilityPlacement facility) {
        // The BCA Pokecenter template stores its four-block basement at local
        // Y=0..3. Local Y=4 is the public ground floor and must meet the lot.
        return facility.id().equals("facility_pokemon_center") ? 4 : 0;
    }

    private static boolean placeFacilityPlaceholder(
        ServerLevel level,
        FacilityPlacement facility,
        BlockPoint origin
    ) {
        int width = Math.max(8, Math.min(96, facility.footprintWidth()));
        int depth = Math.max(8, Math.min(96, facility.footprintDepth()));
        int height = Math.max(4, Math.min(48, facility.footprintHeight()));
        int groundY = origin.y();
        String facilityType = facility.facilityType() == null
            ? facility.id() : facility.facilityType();
        String label = facility.label() == null ? facilityType : facility.label();
        BlockState[] frames = {
            Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState(),
            Blocks.LIME_CONCRETE.defaultBlockState(),
            Blocks.ORANGE_CONCRETE.defaultBlockState(),
            Blocks.PINK_CONCRETE.defaultBlockState(),
            Blocks.PURPLE_CONCRETE.defaultBlockState(),
            Blocks.YELLOW_CONCRETE.defaultBlockState()
        };
        BlockState frame = frames[Math.floorMod(facilityType.hashCode(), frames.length)];
        BlockState wall = Blocks.WHITE_STAINED_GLASS.defaultBlockState();

        for (int localX = -1; localX <= width; localX++) {
            for (int localZ = -1; localZ <= depth; localZ++) {
                int x = origin.x() + localX;
                int z = origin.z() + localZ;
                level.getChunk(x >> 4, z >> 4);
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                for (int y = Math.max(level.getMinBuildHeight(), surfaceY + 1); y < groundY; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                }
                level.setBlock(new BlockPos(x, groundY, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = groundY + 1; y <= Math.max(surfaceY, groundY + height + 1); y++) {
                    if (y >= level.getMaxBuildHeight()) break;
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }

        int doorwayCenter = width / 2;
        for (int localX = 0; localX < width; localX++) {
            for (int localZ = 0; localZ < depth; localZ++) {
                level.setBlock(
                    new BlockPos(origin.x() + localX, groundY, origin.z() + localZ),
                    Blocks.SMOOTH_STONE.defaultBlockState(), 2
                );
                boolean perimeter = localX == 0 || localX == width - 1
                    || localZ == 0 || localZ == depth - 1;
                if (!perimeter) continue;
                boolean corner = (localX == 0 || localX == width - 1)
                    && (localZ == 0 || localZ == depth - 1);
                for (int localY = 1; localY <= height; localY++) {
                    boolean doorway = localZ == 0
                        && Math.abs(localX - doorwayCenter) <= 1 && localY <= 3;
                    BlockPos block = new BlockPos(
                        origin.x() + localX, groundY + localY, origin.z() + localZ
                    );
                    if (doorway) {
                        level.setBlock(block, Blocks.AIR.defaultBlockState(), 2);
                    } else {
                        level.setBlock(
                            block,
                            corner || localY == 1 || localY == height ? frame : wall,
                            2
                        );
                    }
                }
            }
        }

        BlockPos signSupport = new BlockPos(
            origin.x() + doorwayCenter, groundY, origin.z() - 2
        );
        BlockPos signPosition = signSupport.above();
        level.setBlock(signSupport, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        level.setBlock(signPosition, Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(signPosition) instanceof SignBlockEntity sign) {
            SignText text = sign.getFrontText()
                .setMessage(0, Component.literal(label))
                .setMessage(1, Component.literal("PLACEHOLDER"))
                .setMessage(2, Component.literal(width + " x " + depth))
                .setMessage(3, Component.literal(facilityType));
            sign.setText(text, true);
            sign.setChanged();
            level.sendBlockUpdated(
                signPosition, level.getBlockState(signPosition),
                level.getBlockState(signPosition), 3
            );
        }
        LOGGER.info(
            "Facility placeholder placed: id={}, type={}, label={}, origin={}, footprint={}x{}x{}",
            facility.id(), facilityType, label, origin,
            width, depth, height
        );
        return true;
    }

    private static GymLot findGymLot(
        ServerLevel level, SettlementPlan settlement, int width, int depth
    ) {
        int centerX = settlement.center().x();
        int centerZ = settlement.center().z();
        GymLot best = null;
        for (int originX = centerX - GYM_LOT_SEARCH_RADIUS;
             originX <= centerX + GYM_LOT_SEARCH_RADIUS - width;
             originX += 3) {
            for (int originZ = centerZ - GYM_LOT_SEARCH_RADIUS;
                 originZ <= centerZ + GYM_LOT_SEARCH_RADIUS - depth;
                 originZ += 3) {
                GymLotAssessment assessment = assessGymLot(
                    level, originX, originZ, width, depth
                );
                if (assessment == null) {
                    continue;
                }
                GymEntranceGeometry entranceGeometry = gymEntranceGeometry(
                    new BlockPoint(originX, assessment.groundY(), originZ),
                    width, depth, RGS_GYM_ENTRANCE_OFFSET
                );
                Point entrance = entranceGeometry.doorway();
                Point road = findNearestVillageRoad(
                    level, entrance, GYM_ROAD_SEARCH_RADIUS,
                    originX - GYM_LOT_CLEARANCE,
                    originZ - GYM_LOT_CLEARANCE,
                    originX + width - 1 + GYM_LOT_CLEARANCE,
                    originZ + depth - 1 + GYM_LOT_CLEARANCE
                );
                if (road == null) {
                    continue;
                }
                int roadDistance = Math.abs(road.x() - entrance.x())
                    + Math.abs(road.z() - entrance.z());
                int roadSides = countRoadSides(
                    level, originX, originZ, width, depth, GYM_ROAD_SEARCH_RADIUS
                );
                int lotCenterX = originX + width / 2;
                int lotCenterZ = originZ + depth / 2;
                int centerDistance = Math.abs(lotCenterX - centerX)
                    + Math.abs(lotCenterZ - centerZ);
                int score = roadSides * 10_000 - roadDistance * 120 - centerDistance * 8
                    - assessment.obstructions() * 5_000;
                GymLot candidate = new GymLot(
                    new BlockPoint(originX, assessment.groundY(), originZ), road,
                    roadSides, roadDistance, assessment.obstructions(), score
                );
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static GymEntranceGeometry gymEntranceGeometry(
        BlockPoint origin, int width, int depth, BlockPoint entranceOffset
    ) {
        int localX = Math.max(0, Math.min(width - 1, entranceOffset.x()));
        int localZ = Math.max(0, Math.min(depth - 1, entranceOffset.z()));
        int westDistance = localX;
        int eastDistance = width - 1 - localX;
        int northDistance = localZ;
        int southDistance = depth - 1 - localZ;
        int closest = Math.min(
            Math.min(westDistance, eastDistance),
            Math.min(northDistance, southDistance)
        );
        if (closest == westDistance) {
            return new GymEntranceGeometry(
                new Point(origin.x() - 1, origin.z() + localZ), -1, 0
            );
        }
        if (closest == eastDistance) {
            return new GymEntranceGeometry(
                new Point(origin.x() + width, origin.z() + localZ), 1, 0
            );
        }
        if (closest == northDistance) {
            return new GymEntranceGeometry(
                new Point(origin.x() + localX, origin.z() - 1), 0, -1
            );
        }
        return new GymEntranceGeometry(
            new Point(origin.x() + localX, origin.z() + depth), 0, 1
        );
    }

    private static GymLotAssessment assessGymLot(
        ServerLevel level, int originX, int originZ, int width, int depth
    ) {
        int minX = originX - GYM_LOT_CLEARANCE;
        int minZ = originZ - GYM_LOT_CLEARANCE;
        int maxX = originX + width - 1 + GYM_LOT_CLEARANCE;
        int maxZ = originZ + depth - 1 + GYM_LOT_CLEARANCE;
        int minimumGround = Integer.MAX_VALUE;
        int maximumGround = Integer.MIN_VALUE;
        int obstructions = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                HexWorldPlan world = activeHexWorld;
                TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || isAquatic(sample)) {
                    return null;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                minimumGround = Math.min(minimumGround, groundY);
                maximumGround = Math.max(maximumGround, groundY);
                BlockPos existingSurface = surfacePosition(level, x, z).below();
                BlockState existing = level.getBlockState(existingSurface);
                if (existingSurface.getY() > groundY
                    && !existing.isAir() && !existing.canBeReplaced()) {
                    obstructions++;
                }
            }
        }
        return maximumGround - minimumGround <= 1 && obstructions == 0
            ? new GymLotAssessment(maximumGround, 0)
            : null;
    }

    private static int countRoadSides(
        ServerLevel level, int originX, int originZ, int width, int depth, int radius
    ) {
        int centerX = originX + width / 2;
        int centerZ = originZ + depth / 2;
        int sides = 0;
        if (findRoadAlong(level, originX - 1, centerZ, -1, 0, radius) != null) sides++;
        if (findRoadAlong(level, originX + width, centerZ, 1, 0, radius) != null) sides++;
        if (findRoadAlong(level, centerX, originZ - 1, 0, -1, radius) != null) sides++;
        if (findRoadAlong(level, centerX, originZ + depth, 0, 1, radius) != null) sides++;
        return sides;
    }

    private static Point findRoadAlong(
        ServerLevel level, int x, int z, int stepX, int stepZ, int radius
    ) {
        for (int distance = 0; distance <= radius; distance++) {
            int sampleX = x + stepX * distance;
            int sampleZ = z + stepZ * distance;
            if (isVillageRoadAt(level, sampleX, sampleZ)) {
                return new Point(sampleX, sampleZ);
            }
        }
        return null;
    }

    private static Point findNearestVillageRoad(
        ServerLevel level,
        Point origin,
        int radius,
        int excludedMinX,
        int excludedMinZ,
        int excludedMaxX,
        int excludedMaxZ
    ) {
        Point best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = origin.x() - radius; x <= origin.x() + radius; x++) {
            for (int z = origin.z() - radius; z <= origin.z() + radius; z++) {
                if (x >= excludedMinX && x <= excludedMaxX
                    && z >= excludedMinZ && z <= excludedMaxZ) {
                    continue;
                }
                int distance = Math.abs(x - origin.x()) + Math.abs(z - origin.z());
                if (distance >= bestDistance || !isVillageRoadAt(level, x, z)) {
                    continue;
                }
                best = new Point(x, z);
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isVillageRoadAt(ServerLevel level, int x, int z) {
        BlockPos surface = surfacePosition(level, x, z).below();
        HexWorldPlan world = activeHexWorld;
        TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample == null || isAquatic(sample)
            || surface.getY() != terrainGroundY(world, sample, x, z)) {
            return false;
        }
        BlockState state = level.getBlockState(surface);
        return state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.MOSSY_COBBLESTONE)
            || state.is(Blocks.STONE_BRICKS)
            || state.is(Blocks.MOSSY_STONE_BRICKS)
            || state.is(Blocks.COBBLED_DEEPSLATE)
            || state.is(Blocks.ANDESITE)
            || state.is(Blocks.POLISHED_ANDESITE)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.PACKED_MUD)
            || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.POLISHED_DIORITE)
            || state.is(Blocks.DIRT_PATH);
    }

    private static void drawGymRingRoad(
        ServerLevel level,
        SettlementPlan settlement,
        FacilityPlacement facility,
        BlockPoint origin
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId == null) {
            LOGGER.warn("Cannot create gym ring road for invalid structure ID: {}", facility.structure());
            return;
        }
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            LOGGER.warn("Cannot read gym template size for ring road: {}", structureId);
            return;
        }
        var size = template.get().getSize();
        int minX = origin.x() - GYM_RING_ROAD_MARGIN;
        int minZ = origin.z() - GYM_RING_ROAD_MARGIN;
        int maxX = origin.x() + Math.max(1, size.getX()) - 1 + GYM_RING_ROAD_MARGIN;
        int maxZ = origin.z() + Math.max(1, size.getZ()) - 1 + GYM_RING_ROAD_MARGIN;
        int columns = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean ring = x < minX + GYM_RING_ROAD_WIDTH
                    || x > maxX - GYM_RING_ROAD_WIDTH
                    || z < minZ + GYM_RING_ROAD_WIDTH
                    || z > maxZ - GYM_RING_ROAD_WIDTH;
                if (ring && paintGymRoadColumn(level, x, z)) {
                    columns++;
                }
            }
        }

        GymEntranceGeometry entranceGeometry = gymEntranceGeometry(
            origin, size.getX(), size.getZ(), RGS_GYM_ENTRANCE_OFFSET
        );
        Point doorwayApron = entranceGeometry.doorway();
        Point ringEntrance = new Point(
            doorwayApron.x() + entranceGeometry.outwardX()
                * (GYM_RING_ROAD_MARGIN - GYM_RING_ROAD_WIDTH + 1),
            doorwayApron.z() + entranceGeometry.outwardZ()
                * (GYM_RING_ROAD_MARGIN - GYM_RING_ROAD_WIDTH + 1)
        );
        Point villageRoad = findNearestVillageRoad(
            level, ringEntrance, GYM_ROAD_SEARCH_RADIUS,
            minX, minZ, maxX, maxZ
        );
        if (villageRoad != null) {
            drawSafeGymApproachRoad(level, villageRoad, ringEntrance);
        }
        drawSafeGymApproachRoad(level, ringEntrance, doorwayApron);
        LOGGER.info(
            "Gym ring road completed: settlement={}, structure={}, size={}x{}, entrance={}, "
                + "villageRoad={}, columns={}",
            settlement.id(), structureId, size.getX(), size.getZ(), doorwayApron,
            villageRoad, columns
        );
    }

    private static void drawSafeGymApproachRoad(
        ServerLevel level, Point start, Point end
    ) {
        int dx = end.x() - start.x();
        int dz = end.z() - start.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.x() + dx * factor);
            int z = (int) Math.round(start.z() + dz * factor);
            for (int offsetX = -2; offsetX <= 2; offsetX++) {
                for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                    if (offsetX * offsetX + offsetZ * offsetZ <= 5) {
                        paintGymRoadColumn(level, x + offsetX, z + offsetZ);
                    }
                }
            }
        }
    }

    private static void drawGymEntranceApron(
        ServerLevel level,
        SettlementPlan settlement,
        FacilityPlacement facility,
        BlockPoint origin
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId == null) {
            return;
        }
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            return;
        }
        var size = template.get().getSize();
        GymEntranceGeometry entranceGeometry = gymEntranceGeometry(
            origin, size.getX(), size.getZ(), RGS_GYM_ENTRANCE_OFFSET
        );
        Point doorway = entranceGeometry.doorway();
        int approachLength = GYM_RING_ROAD_MARGIN - GYM_RING_ROAD_WIDTH + 1;
        int columns = 0;
        for (int distance = 0; distance <= approachLength; distance++) {
            int centerX = doorway.x() + entranceGeometry.outwardX() * distance;
            int centerZ = doorway.z() + entranceGeometry.outwardZ() * distance;
            int perpendicularX = -entranceGeometry.outwardZ();
            int perpendicularZ = entranceGeometry.outwardX();
            for (int offset = -1; offset <= 1; offset++) {
                int x = centerX + perpendicularX * offset;
                int z = centerZ + perpendicularZ * offset;
                if (paintGymEntranceRoadColumn(level, x, origin.y(), z)) {
                    columns++;
                }
            }
        }
        LOGGER.info(
            "Gym entrance connected to civic road: settlement={}, entrance=({}, {}, {}), columns={}",
            settlement.id(), doorway.x(), origin.y(), doorway.z(), columns
        );
    }

    private static boolean paintGymEntranceRoadColumn(
        ServerLevel level, int x, int groundY, int z
    ) {
        clearVegetationColumn(level, x, groundY, z, 32);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            BlockState current = level.getBlockState(new BlockPos(x, y, z));
            if (!current.isAir() && !current.canBeReplaced()) {
                return false;
            }
        }
        BlockPos roadPosition = new BlockPos(x, groundY, z);
        if (level.getBlockState(roadPosition.below()).isAir()) {
            level.setBlock(
                roadPosition.below(), Blocks.COBBLESTONE.defaultBlockState(), 2
            );
        }
        long pattern = (long) x * 73428767L ^ (long) z * 912931L;
        BlockState road = Math.floorMod(pattern, 11L) == 0L
            ? Blocks.ANDESITE.defaultBlockState()
            : Math.floorMod(pattern, 5L) == 0L
                ? Blocks.COBBLESTONE.defaultBlockState()
                : Blocks.STONE_BRICKS.defaultBlockState();
        level.setBlock(roadPosition, road, 2);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return true;
    }

    private static boolean paintGymRoadColumn(ServerLevel level, int x, int z) {
        HexWorldPlan world = activeHexWorld;
        if (world == null) {
            return false;
        }
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample == null || isAquatic(sample)) {
            return false;
        }
        int groundY = terrainGroundY(world, sample, x, z);
        clearVegetationColumn(level, x, groundY, z, 32);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            BlockState current = level.getBlockState(new BlockPos(x, y, z));
            if (!current.isAir() && !current.canBeReplaced()) {
                return false;
            }
        }
        long pattern = (long) x * 73428767L ^ (long) z * 912931L;
        BlockState road = Math.floorMod(pattern, 11L) == 0L
            ? Blocks.ANDESITE.defaultBlockState()
            : Math.floorMod(pattern, 5L) == 0L
                ? Blocks.COBBLESTONE.defaultBlockState()
                : Blocks.STONE_BRICKS.defaultBlockState();
        level.setBlock(new BlockPos(x, groundY, z), road, 2);
        if (level.getBlockState(new BlockPos(x, groundY - 1, z)).isAir()) {
            level.setBlock(
                new BlockPos(x, groundY - 1, z),
                Blocks.COBBLESTONE.defaultBlockState(), 2
            );
        }
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return true;
    }

    private static boolean placeTemplate(ServerLevel level, String structure, BlockPoint position) {
        BlockPos blockPos = position.toBlockPos();
        List<ChunkPos> forcedChunks = forceTemplateChunks(level, structure, blockPos);
        try {
            return placeTemplateLoaded(level, structure, position);
        } finally {
            releaseForcedChunks(level, forcedChunks);
        }
    }

    private static boolean placeFacilityTemplate(
        ServerLevel level, String structure, BlockPoint position
    ) {
        BlockPos blockPos = position.toBlockPos();
        List<ChunkPos> forcedChunks = forceTemplateChunks(level, structure, blockPos);
        try {
            ResourceLocation structureId = ResourceLocation.tryParse(structure);
            if (structureId == null) return false;
            var template = level.getStructureManager().get(structureId);
            if (template.isEmpty()) return false;
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .addProcessor(FacilityTerrainPreservationProcessor.INSTANCE);
            return template.get().placeInWorld(
                level, blockPos, blockPos, settings,
                RandomSource.create(level.getSeed() ^ blockPos.asLong()), 2
            );
        } finally {
            releaseForcedChunks(level, forcedChunks);
        }
    }

    private static boolean placeTemplateLoaded(
        ServerLevel level, String structure, BlockPoint position
    ) {
        BlockPos blockPos = position.toBlockPos();
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

    private static boolean placeTemplateLoaded(
        ServerLevel level, String structure, BlockPoint position, String rotationName
    ) {
        if (rotationName.equals("none")) {
            return placeTemplateLoaded(level, structure, position);
        }
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return false;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return false;
        Rotation rotation = switch (rotationName) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        BlockPos blockPos = position.toBlockPos();
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        return template.get().placeInWorld(
            level, blockPos, blockPos, settings,
            RandomSource.create(level.getSeed() ^ blockPos.asLong()), 2
        );
    }

    private static void preloadTemplateChunks(
        ServerLevel level, List<TownTemplatePlacement> placements
    ) {
        Set<Long> chunks = new HashSet<>();
        for (TownTemplatePlacement placement : placements) {
            BlockPos origin = placement.position().toBlockPos();
            var template = level.getStructureManager().get(
                ResourceLocation.parse(placement.structure())
            );
            int sizeX = template.map(value -> value.getSize().getX()).orElse(16);
            int sizeZ = template.map(value -> value.getSize().getZ()).orElse(16);
            int minChunkX = (Math.min(origin.getX(), origin.getX() + sizeX) >> 4) - 1;
            int maxChunkX = (Math.max(origin.getX(), origin.getX() + sizeX) >> 4) + 1;
            int minChunkZ = (Math.min(origin.getZ(), origin.getZ() + sizeZ) >> 4) - 1;
            int maxChunkZ = (Math.max(origin.getZ(), origin.getZ() + sizeZ) >> 4) + 1;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunks.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }
        for (long key : chunks) {
            level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
        }
    }

    private static List<ChunkPos> forceChunksAround(
        ServerLevel level, BlockPos center, int radius
    ) {
        List<ChunkPos> forcedChunks = new ArrayList<>();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        if (level.setChunkForced(centerChunkX, centerChunkZ, true)) {
            forcedChunks.add(new ChunkPos(centerChunkX, centerChunkZ));
        }
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        return forcedChunks;
    }

    private static List<ChunkPos> forceTemplateChunks(
        ServerLevel level, String structure, BlockPos origin
    ) {
        var template = level.getStructureManager().get(ResourceLocation.parse(structure));
        if (template.isEmpty()) {
            return forceChunksAround(level, origin, 1);
        }
        var size = template.get().getSize();
        int minChunkX = (Math.min(origin.getX(), origin.getX() + size.getX()) >> 4) - 1;
        int maxChunkX = (Math.max(origin.getX(), origin.getX() + size.getX()) >> 4) + 1;
        int minChunkZ = (Math.min(origin.getZ(), origin.getZ() + size.getZ()) >> 4) - 1;
        int maxChunkZ = (Math.max(origin.getZ(), origin.getZ() + size.getZ()) >> 4) + 1;
        List<ChunkPos> forcedChunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.setChunkForced(chunkX, chunkZ, true)) {
                    forcedChunks.add(new ChunkPos(chunkX, chunkZ));
                }
                level.getChunk(chunkX, chunkZ);
            }
        }
        return forcedChunks;
    }

    private static void releaseForcedChunks(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (integrationShutdownTicks > 0 && --integrationShutdownTicks == 0) {
            event.getServer().halt(false);
            return;
        }
        runPendingWorldInitialization(event);
        runActiveWorldInitialization();
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            return;
        }
        long gameTime = level.getGameTime();
        scheduleNearbyTownInitialization(level, gameTime);
        scheduleBackgroundTownInitialization(level);
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

    private static void scheduleNearbyTownInitialization(ServerLevel level, long gameTime) {
        if (activeInitialization != null || activeHexWorld == null
            || activeSettlements.isEmpty() || gameTime % 20L != 0L) {
            return;
        }
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (!data.isComplete(MAP_VERSION)) {
            return;
        }
        double triggerDistanceSquared = (double) LAZY_TOWN_TRIGGER_DISTANCE
            * LAZY_TOWN_TRIGGER_DISTANCE;
        for (ServerPlayer player : level.players()) {
            for (SettlementPlan settlement : activeSettlements.values()) {
                if (!settlement.enabled() || data.isSettlementGenerated(settlement.id())) {
                    continue;
                }
                double dx = player.getX() - settlement.center().x();
                double dz = player.getZ() - settlement.center().z();
                if (dx * dx + dz * dz > triggerDistanceSquared) {
                    continue;
                }
                GenerationProgress progress = new GenerationProgress(player);
                activeInitialization = new WorldInitializationJob(
                    level, player, data, BlockPos.ZERO,
                    new RuntimeWorld(activeSettlements, activeHexWorld),
                    data.spawnPos(), BlockPos.ZERO, List.of(settlement), progress, false
                );
                progress.update(86, "접근한 마을을 생성합니다: " + settlement.id());
                LOGGER.info(
                    "Lazy town initialization scheduled: settlement={}, player={}, distance={}",
                    settlement.id(), player.getGameProfile().getName(),
                    Math.sqrt(dx * dx + dz * dz)
                );
                return;
            }
        }
    }

    private static void scheduleBackgroundTownInitialization(ServerLevel level) {
        if (activeInitialization != null || activeHexWorld == null
            || activeSettlements.isEmpty()
            || Boolean.getBoolean(INTEGRATION_TEST_PROPERTY)
            || Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY)) {
            return;
        }
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (!data.isComplete(MAP_VERSION)) {
            return;
        }
        SettlementPlan starter = activeSettlements.get(STARTER_SETTLEMENT);
        SettlementPlan next = activeSettlements.values().stream()
            .filter(SettlementPlan::enabled)
            .filter(settlement -> !data.isSettlementGenerated(settlement.id()))
            .min(Comparator
                .comparingInt((SettlementPlan settlement) -> settlement.id().equals(STARTER_SETTLEMENT)
                    ? Integer.MIN_VALUE
                    : settlementDistanceSquared(starter, settlement))
                .thenComparing(SettlementPlan::id))
            .orElse(null);
        if (next == null) {
            return;
        }
        activeInitialization = new WorldInitializationJob(
            level, null, data, BlockPos.ZERO,
            new RuntimeWorld(activeSettlements, activeHexWorld),
            data.spawnPos(), BlockPos.ZERO, List.of(next), new GenerationProgress(null), false
        );
        LOGGER.info(
            "Background town initialization scheduled: settlement={}, chunksPerTick={}",
            next.id(), townChunkLoadBudget(next)
        );
    }

    private static int settlementDistanceSquared(
        SettlementPlan origin, SettlementPlan settlement
    ) {
        if (origin == null) {
            return 0;
        }
        int dx = settlement.center().x() - origin.center().x();
        int dz = settlement.center().z() - origin.center().z();
        return dx * dx + dz * dz;
    }

    private static void scheduleWorldInitialization(ServerPlayer player) {
        if (pendingInitializationPlayer == null && activeInitialization == null) {
            pendingInitializationPlayer = player.getUUID();
            // Let the login/teleport packet and the waiting-area chunk reach the
            // client before the long synchronous terrain build blocks server ticks.
            pendingInitializationTicks = 40;
            LOGGER.info(
                "World initialization scheduled after waiting-area handoff: player={}, delayTicks={}",
                player.getGameProfile().getName(), pendingInitializationTicks
            );
        }
    }

    private static void runPendingWorldInitialization(ServerTickEvent.Post event) {
        UUID playerId = pendingInitializationPlayer;
        if (playerId == null || pendingInitializationTicks < 0) {
            return;
        }
        if (pendingInitializationTicks-- > 0) {
            return;
        }
        pendingInitializationPlayer = null;
        pendingInitializationTicks = -1;

        ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
        ServerLevel generationOne = event.getServer().getLevel(GENERATION_ONE);
        if (player == null || generationOne == null) {
            LOGGER.warn("Deferred world initialization was canceled because the player or dimension left");
            return;
        }
        BootstrapSavedData data = event.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        BlockPos waitingArea = createWaitingArea(generationOne);
        if (data.isComplete(MAP_VERSION)) {
            moveWaitingPlayersToStart(generationOne, data.spawnPos());
            removeWaitingArea(generationOne, waitingArea);
            return;
        }
        LOGGER.info(
            "Deferred world initialization starting: player={}, position={}",
            player.getGameProfile().getName(), player.blockPosition()
        );
        initializeWorld(generationOne, player, data, waitingArea);
    }

    private static void runActiveWorldInitialization() {
        WorldInitializationJob job = activeInitialization;
        if (job == null) {
            return;
        }
        if (job.index >= job.settlements.size()) {
            finishWorldInitialization(job);
            return;
        }

        SettlementPlan settlement = job.settlements.get(job.index);
        int percent = 86 + job.index * 12 / Math.max(1, job.settlements.size());
        String phaseName = switch (job.phase) {
            case -1 -> "청크";
            case 0 -> "도로";
            case 1 -> "시설";
            default -> "조경";
        };
        if (job.phase != -1) {
            job.progress.update(percent, "마을 " + phaseName + " 배치 중: " + settlement.id());
        }
        try {
            if (job.phase == -1) {
                if (job.townChunks.isEmpty()) {
                    prepareTownChunks(job, settlement);
                }
                loadTownChunksIncrementally(job, settlement);
                int ready = countReadyTownChunks(job);
                if (ready < job.townChunks.size()) {
                    if (job.lastReportedReadyChunks < 0
                        || ready - job.lastReportedReadyChunks >= 8) {
                        job.lastReportedReadyChunks = ready;
                        job.progress.update(
                            percent,
                            "마을 청크 순차 생성 중: " + settlement.id()
                                + " (" + ready + "/" + job.townChunks.size() + ")"
                        );
                    }
                    return;
                }
                LOGGER.info(
                    "Town chunks are ready: settlement={}, chunks={}, elapsedMs={}",
                    settlement.id(), job.townChunks.size(),
                    (System.nanoTime() - job.chunkPreparationStartedAt) / 1_000_000L
                );
                job.phase = 0;
            } else if (job.phase == 0) {
                if (!placeTown(job.level, settlement)) {
                    throw new IllegalStateException("Town road placement returned false");
                }
                job.phase = 1;
            } else if (job.phase == 1) {
                if (!placeFacilities(job.level, settlement)) {
                    throw new IllegalStateException("Town facility placement returned false");
                }
                job.phase = 2;
            } else {
                if (NativeWorldGeneration.usesNativeGenerator(
                    job.level.getChunkSource().getGenerator()
                )) {
                    LOGGER.info(
                        "Town post-landscaping skipped because native chunk generation already decorates it: settlement={}",
                        settlement.id()
                    );
                } else {
                    decorateTownLandscape(job.level, job.runtime.hexWorld(), settlement);
                }
                releasePreparedTownChunks(job);
                job.phase = -1;
                job.index++;
                if (job.index >= job.settlements.size()) {
                    finishWorldInitialization(job);
                }
            }
        } catch (RuntimeException error) {
            releasePreparedTownChunks(job);
            activeInitialization = null;
            LOGGER.error(
                "Incremental town generation failed: settlement={}, phase={}",
                settlement.id(), phaseName, error
            );
            if (job.player != null) {
                job.player.sendSystemMessage(Component.literal(
                    "[Cobbleventure] 마을 생성에 실패했습니다: " + settlement.id()
                        + " (" + phaseName + "). 서버 로그를 확인하세요."
                ));
            }
        }
    }

    private static void prepareTownChunks(
        WorldInitializationJob job, SettlementPlan settlement
    ) {
        Set<Long> chunkKeys = townPreparationChunkKeys(settlement);
        job.chunkPreparationStartedAt = System.nanoTime();
        int centerChunkX = settlement.center().x() >> 4;
        int centerChunkZ = settlement.center().z() >> 4;
        chunkKeys.stream()
            .map(key -> new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)))
            .sorted(Comparator
                .comparingInt((ChunkPos chunk) -> Math.abs(chunk.x - centerChunkX)
                    + Math.abs(chunk.z - centerChunkZ))
                .thenComparingInt(chunk -> chunk.x)
                .thenComparingInt(chunk -> chunk.z))
            .forEach(job.townChunks::add);
        LOGGER.info(
            "Town chunk preparation planned: settlement={}, chunks={}",
            settlement.id(), job.townChunks.size()
        );
    }

    private static void loadTownChunksIncrementally(
        WorldInitializationJob job, SettlementPlan settlement
    ) {
        int budget = townChunkLoadBudget(settlement);
        int requested = 0;
        while (job.nextTownChunk < job.townChunks.size() && requested < budget) {
            ChunkPos chunk = job.townChunks.get(job.nextTownChunk++);
            if (job.level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
                job.level.getChunk(chunk.x, chunk.z);
            }
            requested++;
        }
    }

    private static int townChunkLoadBudget(SettlementPlan settlement) {
        return settlement.id().equals(STARTER_SETTLEMENT)
            ? STARTER_TOWN_CHUNKS_PER_TICK
            : BACKGROUND_TOWN_CHUNKS_PER_TICK;
    }

    private static int countReadyTownChunks(WorldInitializationJob job) {
        int ready = 0;
        for (ChunkPos chunk : job.townChunks) {
            if (job.level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null) {
                ready++;
            }
        }
        return ready;
    }

    private static void releasePreparedTownChunks(WorldInitializationJob job) {
        job.townChunks.clear();
        job.nextTownChunk = 0;
        job.lastReportedReadyChunks = -1;
        job.chunkPreparationStartedAt = 0L;
    }

    private static Set<Long> townPreparationChunkKeys(SettlementPlan settlement) {
        Set<Long> chunks = new HashSet<>();
        TownLayout layout = generateTownLayout(settlement);
        Point center = new Point(settlement.center().x(), settlement.center().z());
        Set<Long> roadColumns = new HashSet<>();
        int plazaRadius = Math.max(5, settlement.roadProfile().width());
        for (int x = center.x() - plazaRadius; x <= center.x() + plazaRadius; x++) {
            for (int z = center.z() - plazaRadius; z <= center.z() + plazaRadius; z++) {
                if (Math.hypot(x - center.x(), z - center.z()) <= plazaRadius + 0.5D) {
                    roadColumns.add(blockColumnKey(x, z));
                }
            }
        }
        for (TownRoad road : layout.roads()) {
            collectConfiguredRoadColumns(
                roadColumns,
                center.translate(road.x1(), road.z1()),
                center.translate(road.x2(), road.z2()),
                settlement.roadProfile().width()
            );
        }
        for (long column : roadColumns) {
            chunks.add(ChunkPos.asLong(
                blockColumnX(column) >> 4, blockColumnZ(column) >> 4
            ));
        }
        for (TownPlot plot : layout.houses()) {
            addChunkRectangle(
                chunks,
                center.x() + (int) Math.floor(plot.x()) - 2,
                center.z() + (int) Math.floor(plot.z()) - 2,
                center.x() + (int) Math.ceil(plot.x() + plot.width()) + 2,
                center.z() + (int) Math.ceil(plot.z() + plot.depth()) + 2
            );
        }
        for (TownPlot plot : layout.facilities().values()) {
            addChunkRectangle(
                chunks,
                center.x() + (int) Math.floor(plot.x()) - 4,
                center.z() + (int) Math.floor(plot.z()) - 4,
                center.x() + (int) Math.ceil(plot.x() + plot.width()) + 4,
                center.z() + (int) Math.ceil(plot.z() + plot.depth()) + 4
            );
        }
        return chunks;
    }

    private static void addChunkRectangle(
        Set<Long> chunks, int minX, int minZ, int maxX, int maxZ
    ) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                chunks.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
    }

    private static void finishWorldInitialization(WorldInitializationJob job) {
        activeInitialization = null;
        activeFacilityPortals = facilityPortals(job.runtime.settlements());
        for (SettlementPlan settlement : job.settlements) {
            job.data.markSettlementGenerated(settlement.id());
        }
        if (!job.initialGeneration) {
            String settlementId = job.settlements.getFirst().id();
            job.progress.update(100, "마을 생성 완료: " + settlementId);
            if (job.player != null) {
                job.player.sendSystemMessage(Component.literal(
                    "[Cobbleventure] 접근한 마을을 생성했습니다: " + settlementId
                ));
            } else {
                LOGGER.info("Background town initialization completed: {}", settlementId);
            }
            return;
        }
        job.data.complete(job.spawnPos, job.villagePos, MAP_VERSION);
        job.progress.update(100, "시작 지역 생성 완료");
        moveWaitingPlayersToStart(job.level, job.spawnPos);
        removeWaitingArea(job.level, job.waitingArea);
        job.player.sendSystemMessage(Component.literal(
            "[Cobbleventure] 마을 데이터로 1세대 시작 지역과 연결 통로를 생성했습니다."
        ));
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
            Point center = townFootprintWorldCenter(world.grid(), starter);
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
            Commands.literal("cobbleventure_place_structure")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("structure", StringArgumentType.greedyString())
                    .executes(context -> placeTerrainAwareStructure(
                        context.getSource(),
                        StringArgumentType.getString(context, "structure")
                    )))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_generate_town")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("settlement", StringArgumentType.greedyString())
                    .executes(context -> generateConfiguredTown(
                        context.getSource(),
                        StringArgumentType.getString(context, "settlement")
                    )))
        );
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

    private static int placeTerrainAwareStructure(
        CommandSourceStack source, String structure
    ) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        if (!level.dimension().equals(GENERATION_ONE)) {
            source.sendFailure(Component.literal(
                "[Cobbleventure] 이 명령은 generation_1 차원에서만 사용할 수 있습니다."
            ));
            return 0;
        }
        if (activeHexWorld == null) {
            source.sendFailure(Component.literal(
                "[Cobbleventure] 지형 높이 정보가 아직 준비되지 않았습니다."
            ));
            return 0;
        }

        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) {
            source.sendFailure(Component.literal(
                "[Cobbleventure] 잘못된 구조물 ID입니다: " + structure
            ));
            return 0;
        }

        BlockPos commandPosition = BlockPos.containing(source.getPosition());
        BlockPos origin = surfacePosition(
            level, commandPosition.getX(), commandPosition.getZ()
        ).below();
        int[] heightLookups = {0, Integer.MAX_VALUE, Integer.MIN_VALUE};
        List<ChunkPos> forcedChunks = forceChunksAround(
            level, origin, TOWN_PRELOAD_RADIUS_CHUNKS
        );
        try (TownPlacementHeightContext.Scope ignored = TownPlacementHeightContext.open(
            (x, z, heightmap) -> {
                int height = townGenerationBaseHeight(level, x, z, heightmap);
                heightLookups[0]++;
                heightLookups[1] = Math.min(heightLookups[1], height);
                heightLookups[2] = Math.max(heightLookups[2], height);
                return height;
            }
        )) {
            int placed = level.getServer().getCommands().getDispatcher().execute(
                "place structure " + structureId + " ~ ~ ~",
                source.withPosition(Vec3.atLowerCornerOf(origin))
                    .withPermission(4)
                    .withSuppressedOutput()
            );
            if (placed == 0) {
                source.sendFailure(Component.literal(
                    "[Cobbleventure] 구조물을 배치하지 못했습니다: " + structureId
                ));
                return 0;
            }
        } finally {
            releaseForcedChunks(level, forcedChunks);
        }

        String heightRange = heightLookups[0] == 0
            ? "조회 없음"
            : heightLookups[1] + ".." + heightLookups[2];
        source.sendSuccess(() -> Component.literal(
            "[Cobbleventure] 구조물 배치 완료: " + structureId
                + " at " + origin.toShortString()
                + " (지형 높이 조회 " + heightLookups[0] + "회, Y " + heightRange + ")"
        ), true);
        return 1;
    }

    private static int generateConfiguredTown(
        CommandSourceStack source, String requestedSettlement
    ) {
        ServerLevel level = source.getLevel();
        if (!level.dimension().equals(GENERATION_ONE)) {
            source.sendFailure(Component.literal(
                "[Cobbleventure] 이 명령은 generation_1 차원에서만 사용할 수 있습니다."
            ));
            return 0;
        }
        String settlementId = requestedSettlement.contains(":")
            ? requestedSettlement
            : "cobbleventure:settlement/" + requestedSettlement;
        SettlementPlan configured = activeSettlements.get(settlementId);
        if (configured == null) {
            source.sendFailure(Component.literal(
                "[Cobbleventure] 알 수 없는 마을 ID입니다: " + settlementId
            ));
            return 0;
        }
        BlockPos commandPosition = BlockPos.containing(source.getPosition());
        SettlementPlan translated = translateSettlementForTest(
            configured, commandPosition.getX(), commandPosition.getZ()
        );
        try {
            if (!placeTown(level, translated) || !placeFacilities(level, translated)) {
                source.sendFailure(Component.literal(
                    "[Cobbleventure] 설정형 마을 생성에 실패했습니다: " + settlementId
                ));
                return 0;
            }
        } catch (RuntimeException error) {
            LOGGER.error("Configured town command failed: {}", settlementId, error);
            source.sendFailure(Component.literal(
                "[Cobbleventure] 설정형 마을 생성 중 오류가 발생했습니다: " + settlementId
                    + ". 서버 로그를 확인하세요."
            ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "[Cobbleventure] BCA 없이 설정형 마을 생성 완료: " + settlementId
                + " at " + commandPosition.getX() + ", " + commandPosition.getZ()
        ), true);
        return 1;
    }

    private static SettlementPlan translateSettlementForTest(
        SettlementPlan settlement, int targetX, int targetZ
    ) {
        int deltaX = targetX - settlement.center().x();
        int deltaZ = targetZ - settlement.center().z();
        Map<String, BlockPoint> anchors = new LinkedHashMap<>();
        settlement.anchors().forEach((id, point) ->
            anchors.put(id, point.translate(deltaX, deltaZ))
        );
        return new SettlementPlan(
            settlement.id(), settlement.enabled(), settlement.townRadiusCells(),
            settlement.structure(), settlement.houseStyle(), settlement.disableCommercialOneOff(),
            settlement.layoutShape(), settlement.roadProfile(), settlement.generationSeed(),
            settlement.generationDepth(), settlement.buildingDensity(), settlement.basicBuildings(),
            settlement.center().translate(deltaX, deltaZ),
            settlement.structurePoint().translate(deltaX, deltaZ),
            settlement.playerSpawn().translate(deltaX, deltaZ),
            Map.copyOf(anchors), settlement.facilities(), settlement.gates(),
            settlement.compiledLayout()
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
        BlockPoint center = blockPointFrom(centerJson);
        JsonObject anchors = root.getAsJsonObject("anchors");
        Map<String, BlockPoint> anchorPoints = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : anchors.entrySet()) {
            anchorPoints.put(entry.getKey(), blockPointFrom(entry.getValue().getAsJsonObject()));
        }
        BlockPoint structurePoint = anchors.has("town_square")
            ? blockPointFrom(anchors.getAsJsonObject("town_square"))
            : center;
        BlockPoint playerSpawn = anchors.has("player_spawn")
            ? blockPointFrom(anchors.getAsJsonObject("player_spawn"))
            : center;
        JsonObject structureProfile = root.getAsJsonObject("structure_profile");
        String structure = requiredString(structureProfile, "structure");
        String houseStyle = structureProfile.has("house_style")
            ? requiredString(structureProfile, "house_style")
            : "bca:default/general";
        boolean disableCommercialOneOff = structureProfile.has("commercial_center")
            && (structureProfile.get("commercial_center").getAsString().equals("none")
                || (structureProfile.has("civic_facilities_explicit")
                    && structureProfile.get("civic_facilities_explicit").getAsBoolean()));
        String layoutShape = structureProfile.has("layout_shape")
            ? requiredString(structureProfile, "layout_shape") : "branching";
        JsonObject roadProfileJson = structureProfile.has("road_profile")
            ? structureProfile.getAsJsonObject("road_profile") : null;
        RoadProfile roadProfile = roadProfileJson == null
            ? new RoadProfile(7, "cobblestone")
            : new RoadProfile(
                roadProfileJson.get("width").getAsInt(),
                requiredString(roadProfileJson, "material")
            );
        JsonObject generationProfile = structureProfile.has("generation_profile")
            ? structureProfile.getAsJsonObject("generation_profile") : null;
        int generationSeed = generationProfile == null
            ? 1 + Math.floorMod(id.hashCode(), 999_999_998)
            : generationProfile.get("seed").getAsInt();
        int generationDepth = generationProfile == null
            ? 4 : generationProfile.get("depth").getAsInt();
        String buildingDensity = generationProfile != null
            && generationProfile.has("building_density")
            ? generationProfile.get("building_density").getAsString() : "normal";
        List<String> basicBuildings = new ArrayList<>();
        if (generationProfile != null && generationProfile.has("basic_buildings")) {
            for (JsonElement element : generationProfile.getAsJsonArray("basic_buildings")) {
                basicBuildings.add(element.getAsString());
            }
        }
        if (basicBuildings.isEmpty()) {
            basicBuildings.add("cobbleventure:placeholder/basic_building_1");
            basicBuildings.add("cobbleventure:placeholder/basic_building_2");
            basicBuildings.add("cobbleventure:placeholder/basic_building_3");
        }
        JsonObject gymConfig = structureProfile.has("gym")
            ? structureProfile.getAsJsonObject("gym")
            : null;
        boolean hasGymConfig = gymConfig != null;
        boolean hasDistrictConfig = structureProfile.has("special_district");
        List<TownGateConfig> gates = new ArrayList<>();
        if (root.has("connections")) {
            for (JsonElement element : root.getAsJsonArray("connections")) {
                JsonObject connection = element.getAsJsonObject();
                JsonObject placement = connection.getAsJsonObject("placement");
                gates.add(new TownGateConfig(
                    requiredString(connection, "id"),
                    requiredString(connection, "target_settlement"),
                    requiredString(placement, "mode"),
                    requiredString(placement, "preferred_side"),
                    placement.get("offset").getAsInt(),
                    connection.get("gate_width").getAsInt(),
                    connection.get("path_width").getAsInt()
                ));
            }
        }
        List<FacilityPlacement> facilities = new ArrayList<>();
        boolean starterSettlement = id.endsWith("/starter_town")
            || (structureProfile.has("village_preset")
                && structureProfile.get("village_preset").getAsString()
                    .equals("cobbleventure_starter"));
        boolean pokemonCenterEnabled = structureProfile.has("pokemon_center_enabled")
            ? structureProfile.get("pokemon_center_enabled").getAsBoolean()
            : !starterSettlement;
        if (pokemonCenterEnabled) {
            facilities.add(new FacilityPlacement(
                "facility_pokemon_center", "direct_template",
                "bca:default/one_off/pokecenter", "pokemon_center", "포켓몬센터",
                null, null, null, null, null, null, 1.5D,
                22, 23, 15, 6
            ));
        }
        String commercialCenter = structureProfile.has("commercial_center")
            ? structureProfile.get("commercial_center").getAsString()
            : (starterSettlement ? "none" : "pokemart");
        if (commercialCenter.equals("preset")) {
            commercialCenter = "pokemart";
        }
        if (commercialCenter.equals("pokemart")) {
            facilities.add(new FacilityPlacement(
                "facility_pokemart", "direct_template",
                "bca:default/one_off/structure_pokemart", "pokemart", "포켓몬상점",
                null, null, null, null, null, null, 1.5D,
                23, 22, 15, 6
            ));
        } else if (commercialCenter.equals("department_store")) {
            facilities.add(new FacilityPlacement(
                "facility_department_store", "direct_template",
                "bca:default/centers/center_department_store",
                "department_store", "백화점", null, null, null,
                null, null, null, 1.5D, 40, 72, 41, 8
            ));
        }
        if (structureProfile.has("facility_placements")) {
            for (JsonElement element : structureProfile.getAsJsonArray("facility_placements")) {
                JsonObject facility = element.getAsJsonObject();
                String facilityId = requiredString(facility, "id");
                if ((hasGymConfig && facilityId.equals("gym_building"))
                    || (hasDistrictConfig && facilityId.equals("special_district_building"))) {
                    continue;
                }
                JsonObject footprint = facility.has("footprint")
                    ? facility.getAsJsonObject("footprint") : null;
                facilities.add(new FacilityPlacement(
                    facilityId,
                    requiredString(facility, "mode"),
                    requiredString(facility, "structure"),
                    optionalString(facility, "facility_type"),
                    optionalString(facility, "label"),
                    optionalString(facility, "anchor"),
                    optionalString(facility, "entry_anchor"),
                    optionalString(facility, "return_anchor"),
                    optionalBlockPoint(facility, "instance_origin"),
                    optionalBlockPoint(facility, "instance_entry_offset"),
                    optionalBlockPoint(facility, "instance_exit_offset"),
                    facility.has("trigger_radius") ? facility.get("trigger_radius").getAsDouble() : 1.5D,
                    footprint == null ? 0 : footprint.get("width").getAsInt(),
                    footprint == null ? 0 : footprint.get("depth").getAsInt(),
                    footprint == null ? 0 : footprint.get("height").getAsInt(),
                    facility.has("clearance") ? facility.get("clearance").getAsInt() : 0
                ));
            }
        }
        if (gymConfig != null && gymConfig.has("enabled") && gymConfig.get("enabled").getAsBoolean()) {
            facilities.add(new FacilityPlacement(
                "gym_building", "direct_template", requiredString(gymConfig, "structure"),
                null, null, requiredString(gymConfig, "anchor"), null, null,
                null, null, null, 1.5D, 25, 26, 13, 6
            ));
        }
        if (hasDistrictConfig) {
            JsonObject district = structureProfile.getAsJsonObject("special_district");
            JsonObject building = district.getAsJsonObject("building");
            if (district.get("enabled").getAsBoolean() && building.get("enabled").getAsBoolean()) {
                JsonObject footprint = district.getAsJsonObject("footprint");
                facilities.add(new FacilityPlacement(
                    "special_district_building", "direct_template",
                    requiredString(building, "structure"), null, null,
                    requiredString(district, "anchor"),
                    null, null, null, null, null, 1.5D,
                    footprint.get("width").getAsInt(), footprint.get("depth").getAsInt(), 0,
                    district.get("clearance").getAsInt()
                ));
            }
        }
        TownLayout compiledLayout = parseCompiledTownLayout(root);
        return new SettlementPlan(
            id, enabled, root.get("town_radius_cells").getAsInt(),
            structure, houseStyle, disableCommercialOneOff, layoutShape, roadProfile,
            generationSeed, generationDepth, buildingDensity, List.copyOf(basicBuildings),
            center, structurePoint, playerSpawn,
            Map.copyOf(anchorPoints), List.copyOf(facilities), List.copyOf(gates),
            compiledLayout
        );
    }

    private static TownLayout parseCompiledTownLayout(JsonObject root) {
        if (!root.has("compiled_layout")) {
            return null;
        }
        JsonObject compiled = root.getAsJsonObject("compiled_layout");
        List<TownRoad> roads = new ArrayList<>();
        for (JsonElement element : compiled.getAsJsonArray("roads")) {
            JsonObject road = element.getAsJsonObject();
            roads.add(new TownRoad(
                road.get("x1").getAsInt(), road.get("z1").getAsInt(),
                road.get("x2").getAsInt(), road.get("z2").getAsInt()
            ));
        }
        List<TownRoad> accessRoads = new ArrayList<>();
        if (compiled.has("access_roads")) {
            for (JsonElement element : compiled.getAsJsonArray("access_roads")) {
                JsonObject road = element.getAsJsonObject();
                accessRoads.add(new TownRoad(
                    road.get("x1").getAsInt(), road.get("z1").getAsInt(),
                    road.get("x2").getAsInt(), road.get("z2").getAsInt()
                ));
            }
        }
        Map<String, TownPlot> facilities = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry
            : compiled.getAsJsonObject("facilities").entrySet()) {
            facilities.put(entry.getKey(), parseCompiledTownPlot(entry.getValue().getAsJsonObject()));
        }
        List<TownPlot> houses = new ArrayList<>();
        for (JsonElement element : compiled.getAsJsonArray("houses")) {
            houses.add(parseCompiledTownPlot(element.getAsJsonObject()));
        }
        List<Point> externalExits = new ArrayList<>();
        if (compiled.has("external_exit_points")) {
            for (JsonElement element : compiled.getAsJsonArray("external_exit_points")) {
                externalExits.add(pointFrom(element.getAsJsonObject()));
            }
        }
        return new TownLayout(
            List.copyOf(roads), List.copyOf(accessRoads),
            Map.copyOf(facilities), List.copyOf(houses), List.copyOf(externalExits)
        );
    }

    private static TownPlot parseCompiledTownPlot(JsonObject plot) {
        return new TownPlot(
            plot.get("x").getAsDouble(), plot.get("z").getAsDouble(),
            plot.get("width").getAsInt(), plot.get("depth").getAsInt(),
            requiredString(plot, "id"),
            plot.has("structure") ? requiredString(plot, "structure") : null,
            plot.has("rotation") ? requiredString(plot, "rotation") : "none",
            plot.has("road_connection")
                ? plot.getAsJsonObject("road_connection").get("x").getAsInt() : 0,
            plot.has("road_connection")
                ? plot.getAsJsonObject("road_connection").get("z").getAsInt() : 0
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
        HexWorldPlan world = loadHexWorldPlan(level, settlements);
        return new RuntimeWorld(translateSettlements(settlements, world), world);
    }

    private static HexWorldPlan loadHexWorldPlan(
        ServerLevel level,
        Map<String, SettlementPlan> settlementPlans
    ) {
        JsonObject root = readJsonResource(level, "hex_worlds/generation_1.json");
        Map<String, BoundaryProfile> profiles = loadBoundaryProfiles(level);
        Map<String, Integer> townRadii = new LinkedHashMap<>();
        settlementPlans.forEach((id, plan) -> townRadii.put(id, plan.townRadiusCells()));
        long seed = nativeWorldSeed(level, root);
        return parseHexWorldPlan(root, townRadii, profiles, seed);
    }

    static HexWorldPlan parseHexWorldPlan(
        JsonObject root,
        Map<String, Integer> townRadii,
        Map<String, BoundaryProfile> profiles,
        long seed
    ) {
        JsonObject gridJson = root.getAsJsonObject("grid");
        HexGrid grid = new HexGrid(
            gridJson.get("tile_radius_blocks").getAsInt(),
            blockPointFrom(gridJson.getAsJsonObject("origin"))
        );
        boolean usesPlacedTiles = root.get("schema_version").getAsInt() >= 2;
        List<HexSettlement> hexSettlements = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("settlements")) {
            JsonObject value = element.getAsJsonObject();
            String settlement = requiredString(value, "settlement");
            Integer townRadius = townRadii.get(settlement);
            if (townRadius == null) {
                throw new IllegalStateException("Hex world references missing settlement: " + settlement);
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            List<SurroundingRegion> surroundings = new ArrayList<>();
            for (JsonElement regionElement : usesPlacedTiles
                ? List.<JsonElement>of()
                : value.getAsJsonArray("surroundings")) {
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
            String boundary = value.has("boundary_profile")
                ? requiredString(value, "boundary_profile")
                : "cobbleventure:boundary/dense_tree_line";
            requireBoundaryProfile(profiles, boundary);
            List<HexCoord> customFootprint = new ArrayList<>();
            if (value.has("town_footprint_cells")) {
                for (JsonElement cellElement : value.getAsJsonArray("town_footprint_cells")) {
                    JsonObject cell = cellElement.getAsJsonObject();
                    customFootprint.add(new HexCoord(cell.get("q").getAsInt(), cell.get("r").getAsInt()));
                }
            }
            hexSettlements.add(new HexSettlement(
                settlement,
                new HexCoord(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                townRadius,
                value.has("town_footprint_shape")
                    ? requiredString(value, "town_footprint_shape") : "line_q",
                List.copyOf(customFootprint),
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
            // Older/custom web map saves did not persist these two visual
            // fields. Keep such maps loadable and let a later editor save
            // normalize them instead of making the whole dimension invalid.
            String boundary = value.has("boundary_profile")
                ? requiredString(value, "boundary_profile")
                : "cobbleventure:boundary/dense_tree_line";
            requireBoundaryProfile(profiles, boundary);
            List<HexCoord> explicitCells = new ArrayList<>();
            if (value.has("cells")) {
                for (JsonElement cellElement : value.getAsJsonArray("cells")) {
                    JsonObject cell = cellElement.getAsJsonObject();
                    explicitCells.add(new HexCoord(
                        cell.get("q").getAsInt(), cell.get("r").getAsInt()
                    ));
                }
            }
            connections.add(new HexConnection(
                requiredString(value, "id"),
                optionalString(value, "from"),
                optionalString(value, "to"),
                value.has("route_biome")
                    ? requiredString(value, "route_biome") : "minecraft:plains",
                value.has("width_cells") ? value.get("width_cells").getAsInt() : 1,
                value.has("pathfinding") ? requiredString(value, "pathfinding") : "explicit",
                value.has("detour_cells") ? value.get("detour_cells").getAsInt() : 0,
                value.get("corridor_width_blocks").getAsDouble(),
                value.has("edge_noise") ? value.get("edge_noise").getAsDouble() : 0.0D,
                boundary,
                value.has("terrain_profile")
                    ? terrainProfile(value) : new TerrainProfile(0, 0, 96.0D),
                requiredString(value, "surface_style"),
                optionalString(value, "access_requirement"),
                List.copyOf(explicitCells)
            ));
        }
        List<PlacedTile> placedTiles = new ArrayList<>();
        if (root.has("tiles")) {
            for (JsonElement element : root.getAsJsonArray("tiles")) {
                JsonObject value = element.getAsJsonObject();
                String boundary = requiredString(value, "boundary_profile");
                requireBoundaryProfile(profiles, boundary);
                placedTiles.add(new PlacedTile(
                    new HexCoord(value.get("q").getAsInt(), value.get("r").getAsInt()),
                    requiredString(value, "biome"), boundary, terrainProfile(value),
                    optionalString(value, "access_requirement")
                ));
            }
        }
        String defaultEmptyTerrain = "high_forest";
        Map<HexCoord, String> emptyTerrainTiles = new LinkedHashMap<>();
        if (root.has("empty_terrain")) {
            JsonObject emptyTerrain = root.getAsJsonObject("empty_terrain");
            defaultEmptyTerrain = emptyTerrain.has("default_type")
                ? requiredString(emptyTerrain, "default_type") : "high_forest";
            if (emptyTerrain.has("tiles")) {
                for (JsonElement element : emptyTerrain.getAsJsonArray("tiles")) {
                    JsonObject value = element.getAsJsonObject();
                    HexCoord coordinate = new HexCoord(
                        value.get("q").getAsInt(), value.get("r").getAsInt()
                    );
                    String previous = emptyTerrainTiles.putIfAbsent(
                        coordinate, requiredString(value, "type")
                    );
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate empty terrain tile: " + coordinate);
                    }
                }
            }
        }
        requireEmptyTerrainType(defaultEmptyTerrain);
        emptyTerrainTiles.values().forEach(CobbleventureBootstrap::requireEmptyTerrainType);
        HexWorldPlan plan = planHexWorld(
            grid, seed, List.copyOf(hexSettlements), List.copyOf(connections),
            List.copyOf(placedTiles), defaultEmptyTerrain,
            Map.copyOf(emptyTerrainTiles), profiles
        );
        verifySettlementBoundaryClearance(plan);
        LOGGER.info(
            "Hex world planned: cells={}, settlements={}, routes={}",
            plan.cells().size(),
            plan.settlements().size(),
            plan.paths().size()
        );
        return plan;
    }

    private static long nativeWorldSeed(ServerLevel level, JsonObject root) {
        long salt = root.get("seed_salt").getAsLong();
        return NativeWorldGeneration.usesNativeGenerator(
            level.getChunkSource().getGenerator()
        ) ? salt : level.getSeed() ^ salt;
    }

    private static void requireEmptyTerrainType(String type) {
        if (!Set.of("high_forest", "ocean", "desert", "stone_mountain", "snow_mountain").contains(type)) {
            throw new IllegalStateException("Unsupported empty terrain type: " + type);
        }
    }

    private static void verifySettlementBoundaryClearance(HexWorldPlan world) {
        double requiredRadius = TOWN_STRUCTURE_MAX_RADIUS_BLOCKS
            + TOWN_BOUNDARY_CLEARANCE_BLOCKS;
        int probes = 0;
        for (HexSettlement settlement : world.settlements().values()) {
            Point center = townFootprintWorldCenter(world.grid(), settlement);
            for (int ring = 1; ; ring++) {
                double checkedRadius = Math.min(ring * 16.0D, requiredRadius);
                for (int angleIndex = 0; angleIndex < 96; angleIndex++) {
                    double angle = Math.PI * 2.0D * angleIndex / 96.0D;
                    double x = center.x() + Math.cos(angle) * checkedRadius;
                    double z = center.z() + Math.sin(angle) * checkedRadius;
                    TerrainSample sample = terrainAt(world, x, z);
                    probes++;
                    if (sample == null || !sample.kind().equals("town")
                        || !sample.owner().equals(settlement.settlement())) {
                        throw new IllegalStateException(
                            "Settlement boundary clearance is too small for "
                                + settlement.settlement() + " at radius="
                                + Math.round(checkedRadius) + ". Increase town_radius_cells."
                        );
                    }
                }
                if (checkedRadius == requiredRadius) {
                    break;
                }
            }
        }
        LOGGER.info(
            "Settlement boundary clearance verified: settlements={}, requiredRadius={}, probes={}",
            world.settlements().size(), requiredRadius, probes
        );
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
        return parseBoundaryProfiles(root);
    }

    static Map<String, BoundaryProfile> parseBoundaryProfiles(JsonObject root) {
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
        List<PlacedTile> placedTiles,
        String defaultEmptyTerrain,
        Map<HexCoord, String> emptyTerrainTiles,
        Map<String, BoundaryProfile> profiles
    ) {
        Map<HexCoord, CellPlan> cells = new LinkedHashMap<>();
        Map<HexCoord, String> townOwners = new HashMap<>();
        Map<String, HexSettlement> byId = new LinkedHashMap<>();
        for (HexSettlement settlement : settlements) {
            if (byId.putIfAbsent(settlement.settlement(), settlement) != null) {
                throw new IllegalStateException("Duplicate hex settlement: " + settlement.settlement());
            }
            for (HexSettlement other : byId.values()) {
                if (other.settlement().equals(settlement.settlement())) {
                    continue;
                }
                boolean tooClose = townFootprint(settlement).stream()
                    .anyMatch(cell -> townFootprint(other).stream()
                        .anyMatch(otherCell -> cell.distance(otherCell) < 2));
                if (tooClose) {
                    throw new IllegalStateException(
                        "Town hex footprints require at least one buffer cell: "
                            + other.settlement() + " / " + settlement.settlement()
                    );
                }
            }
            for (HexCoord cell : townFootprint(settlement)) {
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

        Map<HexCoord, PlacedTile> placedTilesByCoordinate = new HashMap<>();
        for (PlacedTile tile : placedTiles) {
            placedTilesByCoordinate.put(tile.coordinate(), tile);
        }

        List<ConnectionPath> paths = new ArrayList<>();
        for (HexConnection connection : connections) {
            HexSettlement from = connection.from() == null ? null : byId.get(connection.from());
            HexSettlement to = connection.to() == null ? null : byId.get(connection.to());
            if (connection.cells().isEmpty() && (from == null || to == null)) {
                throw new IllegalStateException("Road requires directly placed cells: " + connection.id());
            }
            List<HexCoord> path = findHexPath(
                from == null ? null : from.anchor(), to == null ? null : to.anchor(),
                townOwners, connection, seed
            );
            List<Point> centerline = buildRouteCenterline(
                grid, seed, byId, connection, path
            );
            paths.add(new ConnectionPath(
                connection.id(), connection.from(), connection.to(),
                connection.routeBiome(), connection.boundaryProfile(),
                connection.corridorWidthBlocks(), connection.edgeNoise(), connection.terrainProfile(),
                connection.surfaceStyle(), connection.accessRequirement(), List.copyOf(path),
                centerline
            ));
        }

        for (HexSettlement settlement : settlements) {
            for (SurroundingRegion region : settlement.surroundings()) {
                growSurroundingRegion(cells, settlement, region, seed);
            }
        }
        for (PlacedTile tile : placedTiles) {
            CellPlan existing = cells.get(tile.coordinate());
            if (townOwners.containsKey(tile.coordinate())) {
                continue;
            }
            cells.put(tile.coordinate(), new CellPlan(
                tile.biome(), tile.boundaryProfile(), "surrounding",
                "tile:" + tile.coordinate().q() + "," + tile.coordinate().r(),
                grid.radius() * 1.04D, 0.08D, tile.terrainProfile(),
                tile.accessRequirement(), "natural"
            ));
        }
        verifyRouteCenterlines(grid, byId, paths);
        return new HexWorldPlan(
            grid, seed, Map.copyOf(cells), List.copyOf(paths), Map.copyOf(byId), profiles,
            defaultEmptyTerrain, emptyTerrainTiles
        );
    }

    private static void verifyRouteCenterlines(
        HexGrid grid,
        Map<String, HexSettlement> settlements,
        List<ConnectionPath> paths
    ) {
        double expectedEdge = TOWN_STRUCTURE_MAX_RADIUS_BLOCKS
            + TOWN_BOUNDARY_CLEARANCE_BLOCKS + 2.0D;
        for (ConnectionPath path : paths) {
            if (path.centerline().size() < 2) {
                throw new IllegalStateException(
                    "Route centerline is too short: " + path.id()
                );
            }
            verifyRouteEndpoint(
                grid, settlements.get(path.from()), path.centerline().getFirst(),
                expectedEdge, path.id(), "from"
            );
            verifyRouteEndpoint(
                grid, settlements.get(path.to()), path.centerline().getLast(),
                expectedEdge, path.id(), "to"
            );
        }
    }

    private static void verifyRouteEndpoint(
        HexGrid grid,
        HexSettlement settlement,
        Point endpoint,
        double expectedDistance,
        String routeId,
        String side
    ) {
        if (settlement == null) {
            return;
        }
        Point center = townFootprintWorldCenter(grid, settlement);
        double distance = Math.hypot(
            endpoint.x() - center.x(), endpoint.z() - center.z()
        );
        if (Math.abs(distance - expectedDistance) > 2.0D) {
            throw new IllegalStateException(
                "Route endpoint is not anchored at the town edge: "
                    + routeId + " (" + side + ", distance=" + distance + ")"
            );
        }
    }

    private static void verifyConnectedSettlementGraph(
        Set<String> settlementIds, List<ConnectionPath> paths
    ) {
        if (settlementIds.isEmpty()) {
            return;
        }
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (String settlementId : settlementIds) {
            neighbors.put(settlementId, new HashSet<>());
        }
        for (ConnectionPath path : paths) {
            neighbors.get(path.from()).add(path.to());
            neighbors.get(path.to()).add(path.from());
        }
        Set<String> reached = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(settlementIds.iterator().next());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!reached.add(current)) {
                continue;
            }
            pending.addAll(neighbors.getOrDefault(current, Set.of()));
        }
        if (reached.size() != settlementIds.size()) {
            Set<String> missing = new HashSet<>(settlementIds);
            missing.removeAll(reached);
            throw new IllegalStateException("Disconnected settlements in route graph: " + missing);
        }
        LOGGER.info(
            "Settlement route graph verified: settlements={}, connections={}",
            settlementIds.size(), paths.size()
        );
    }

    private static List<HexCoord> findHexPath(
        HexCoord start,
        HexCoord target,
        Map<HexCoord, String> townOwners,
        HexConnection connection,
        long seed
    ) {
        if (connection.pathfinding().equals("explicit")) {
            List<HexCoord> cells = connection.cells();
            if (cells.isEmpty()) throw new IllegalStateException("Explicit road has no cells: " + connection.id());
            for (int index = 1; index < cells.size(); index++) {
                if (cells.get(index - 1).distance(cells.get(index)) != 1) {
                    throw new IllegalStateException(
                        "Explicit path contains non-adjacent cells: " + connection.id()
                    );
                }
            }
            return cells;
        }
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
            direction.scale(townFootprintRadius(settlement) + 1)
        );
        Set<HexCoord> selected = new HashSet<>();
        Set<HexCoord> frontier = new HashSet<>();
        for (HexCoord townCell : townFootprint(settlement)) {
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

    private static int townFootprintRadius(int cellCount) {
        if (cellCount == 1) {
            return 0;
        }
        return cellCount == 19 ? 2 : 1;
    }

    private static int townFootprintRadius(HexSettlement settlement) {
        if (!settlement.townFootprintShape().equals("custom")) {
            return townFootprintRadius(settlement.townRadiusCells());
        }
        return settlement.customFootprint().stream()
            .mapToInt(offset -> new HexCoord(0, 0).distance(offset))
            .max().orElse(0);
    }

    private static Set<HexCoord> townFootprint(HexSettlement settlement) {
        if (settlement.townFootprintShape().equals("custom")) {
            Set<HexCoord> result = new HashSet<>();
            for (HexCoord offset : settlement.customFootprint()) {
                result.add(settlement.anchor().plus(offset));
            }
            return result;
        }
        return townFootprint(settlement.anchor(), settlement.townRadiusCells(), settlement.townFootprintShape());
    }

    private static Set<HexCoord> townFootprint(HexCoord center, int cellCount, String shape) {
        if (cellCount == 3) {
            HexCoord[] offsets = switch (shape) {
                case "triangle_up" -> new HexCoord[] {new HexCoord(0, 0), new HexCoord(0, -1), new HexCoord(1, -1)};
                case "triangle_down" -> new HexCoord[] {new HexCoord(0, 0), new HexCoord(0, 1), new HexCoord(-1, 1)};
                case "line_r" -> new HexCoord[] {new HexCoord(0, -1), new HexCoord(0, 0), new HexCoord(0, 1)};
                case "line_s" -> new HexCoord[] {new HexCoord(-1, 1), new HexCoord(0, 0), new HexCoord(1, -1)};
                default -> new HexCoord[] {new HexCoord(-1, 0), new HexCoord(0, 0), new HexCoord(1, 0)};
            };
            return Arrays.stream(offsets).map(center::plus).collect(Collectors.toSet());
        }
        if (cellCount == 5) {
            HexCoord[] offsets = "five_down".equals(shape)
                ? new HexCoord[] {new HexCoord(-1, 0), new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(-1, 1), new HexCoord(0, 1)}
                : new HexCoord[] {new HexCoord(-1, 0), new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, -1), new HexCoord(1, -1)};
            return Arrays.stream(offsets).map(center::plus).collect(Collectors.toSet());
        }
        if (cellCount == 7) {
            return hexRange(center, 1);
        }
        if (cellCount == 19) {
            return hexRange(center, 2);
        }
        return Set.of(center);
    }

    private static Point townFootprintWorldCenter(HexGrid grid, HexSettlement settlement) {
        Set<HexCoord> footprint = townFootprint(settlement);
        if (footprint.isEmpty()) {
            return grid.worldCenter(settlement.anchor());
        }
        long sumX = 0L;
        long sumZ = 0L;
        for (HexCoord coordinate : footprint) {
            Point center = grid.worldCenter(coordinate);
            sumX += center.x();
            sumZ += center.z();
        }
        return new Point(
            (int) Math.round(sumX / (double) footprint.size()),
            (int) Math.round(sumZ / (double) footprint.size())
        );
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
            Point targetPoint = townFootprintWorldCenter(world.grid(), hex);
            BlockPoint target = new BlockPoint(
                targetPoint.x(), settlement.center().y(), targetPoint.z()
            );
            int deltaX = target.x() - settlement.center().x();
            int deltaZ = target.z() - settlement.center().z();
            Map<String, BlockPoint> anchors = new LinkedHashMap<>();
            settlement.anchors().forEach((id, point) ->
                anchors.put(id, point.translate(deltaX, deltaZ))
            );
            translated.put(settlement.id(), new SettlementPlan(
                settlement.id(),
                settlement.enabled(),
                settlement.townRadiusCells(),
                settlement.structure(),
                settlement.houseStyle(),
                settlement.disableCommercialOneOff(),
                settlement.layoutShape(),
                settlement.roadProfile(),
                settlement.generationSeed(),
                settlement.generationDepth(),
                settlement.buildingDensity(),
                settlement.basicBuildings(),
                target,
                settlement.structurePoint().translate(deltaX, deltaZ),
                settlement.playerSpawn().translate(deltaX, deltaZ),
                Map.copyOf(anchors),
                settlement.facilities(),
                settlement.gates(),
                settlement.compiledLayout()
            ));
        }
        return Map.copyOf(translated);
    }

    private static void drawHexWorld(
        ServerLevel level,
        HexWorldPlan world,
        boolean cleanExisting,
        GenerationProgress progress
    ) {
        GenerationProfiler profiler = new GenerationProfiler();
        HexBounds rawBounds = world.grid().bounds(worldRenderCells(world));
        int testRadius = Integer.getInteger(TEST_RENDER_RADIUS_PROPERTY, 0);
        HexSettlement starter = world.settlements().get(STARTER_SETTLEMENT);
        HexBounds bounds;
        if (testRadius > 0 && starter != null) {
            Point center = townFootprintWorldCenter(world.grid(), starter);
            bounds = new HexBounds(
                center.x() - testRadius, center.z() - testRadius,
                center.x() + testRadius, center.z() + testRadius
            );
            LOGGER.info(
                "Cropped world generation test enabled: center={}, radius={}, size={}x{}",
                center, testRadius, testRadius * 2 + 1, testRadius * 2 + 1
            );
        } else {
            bounds = new HexBounds(
                rawBounds.minX() - 32, rawBounds.minZ() - 32,
                rawBounds.maxX() + 32, rawBounds.maxZ() + 32
            );
        }
        progress.update(7, "해안선과 지형 경계 계산 중");
        activeShoreDistances = ShoreDistanceField.build(
            world, bounds, Math.max(48, SHORE_BLEND_WIDTH)
        );
        profiler.finishPhase("shore-distance-field");
        paintBiomes(level, world, bounds, progress);
        profiler.finishPhase("biome-painting");
        if (cleanExisting) {
            progress.update(23, "이전 버전의 지형 정리 중");
            cleanupRenderedWorld(level, bounds);
            profiler.finishPhase("legacy-map-cleanup");
        }
        progress.update(26, "청크 높이 정보 준비 중");
        primeTerrainHeightmaps(level, bounds);
        profiler.finishPhase("heightmap-prime");
        int terrainRows = Math.max(1, bounds.maxX() - bounds.minX() + 1);
        int terrainRow = 0;
        TerrainWriteStats terrainWrites = new TerrainWriteStats();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample != null) {
                    paintSurface(level, world, x, z, sample, cleanExisting, terrainWrites);
                } else {
                    paintSealedOuterTerrain(
                        level, world, x, z, cleanExisting, terrainWrites
                    );
                }
            }
            progress.update(28 + (++terrainRow * 34 / terrainRows), "지형과 수면 생성 중");
        }
        profiler.finishPhase("terrain-columns");
        LOGGER.info(
            "Terrain write optimization: fastPath={}, columns={}, attempted={}, changed={}, skipped={}",
            !cleanExisting, terrainWrites.columns, terrainWrites.attempted,
            terrainWrites.changed, terrainWrites.skipped
        );
        if (Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY) && testRadius <= 0) {
            progress.update(63, "수변 지형 검증 중");
            verifyWalkableShoreTransition(level, world);
        }
        progress.update(66, "나무와 자연물 배치 중");
        decorateVanillaBiomes(level, world, bounds);
        decorateOpenBiomeTrees(level, world, bounds);
        decorateOpenBiomeGroundCover(level, world, bounds);
        profiler.finishPhase("biome-decoration");
        progress.update(72, "마을과 도로 공간 정리 중");
        clearReservedTerrain(level, world, bounds);
        profiler.finishPhase("reserved-terrain-cleanup");
        progress.update(76, "접근 불가 지형 경사 계산 중");
        drawOuterTerrainTransition(level, world, bounds);
        profiler.finishPhase("outer-transition");
        progress.update(80, "접근 불가 지역 마감 중");
        decorateSealedOuterForest(level, world, bounds);
        profiler.finishPhase("sealed-outer-decoration");
        progress.update(83, "직접 배치 길 생성 중");
        drawHexRoads(level, world);
        profiler.finishPhase("route-rendering");
        progress.update(85, "지형 생성 완료");
    }

    private static Set<HexCoord> worldRenderCells(HexWorldPlan world) {
        Set<HexCoord> cells = new HashSet<>(world.cells().keySet());
        cells.addAll(world.emptyTerrainTiles().keySet());
        for (ConnectionPath path : world.paths()) {
            cells.addAll(path.cells());
        }
        return cells;
    }

    static String emptyTerrainAt(HexWorldPlan world, double x, double z) {
        return world.emptyTerrainTiles().getOrDefault(
            world.grid().worldToHex(x, z), world.defaultEmptyTerrain()
        );
    }

    static String emptyTerrainBiome(String type) {
        return switch (type) {
            case "ocean" -> "minecraft:deep_ocean";
            case "desert" -> "minecraft:desert";
            case "stone_mountain" -> "minecraft:stony_peaks";
            case "snow_mountain" -> "minecraft:snowy_slopes";
            default -> SEALED_DARK_FOREST.location().toString();
        };
    }

    private static void primeTerrainHeightmaps(
        ServerLevel level, HexBounds bounds
    ) {
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                Heightmap.primeHeightmaps(
                    level.getChunk(chunkX, chunkZ),
                    EnumSet.of(
                        Heightmap.Types.WORLD_SURFACE_WG,
                        Heightmap.Types.OCEAN_FLOOR_WG
                    )
                );
            }
        }
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
            BlockState expectedSurfaceState = isSandyShore(
                world, selected.sample(), selected.point().x(), selected.point().z(), groundY
            ) ? Blocks.SAND.defaultBlockState() : surfaceBlock(selected.sample().biome());
            boolean expectedSurface = renderedSurface.is(expectedSurfaceState.getBlock())
                || (selected.sample().surfaceStyle().equals("road")
                    && renderedSurface.is(Blocks.COBBLESTONE));
            if (!expectedSurface) {
                throw new IllegalStateException(
                    "Terrain surface rendering mismatch for " + owner + " at Y=" + groundY
                );
            }
            if (isAquatic(selected.sample())) {
                if (groundY > WATER_SURFACE_Y - minimumWaterDepth(selected.sample())
                    || !level.getBlockState(new BlockPos(
                        selected.point().x(), groundY + 1, selected.point().z()
                    )).is(Blocks.WATER)
                    || !level.getBlockState(new BlockPos(
                        selected.point().x(), WATER_SURFACE_Y, selected.point().z()
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
                    selected.point().x(), WATER_SURFACE_Y, selected.point().z()
                )).is(Blocks.WATER)) {
                throw new IllegalStateException("Surf region is missing navigable water: " + owner);
            }
        }
    }

    private static void verifyWalkableShoreTransition(
        ServerLevel level, HexWorldPlan world
    ) {
        HexBounds bounds = world.grid().bounds(world.cells().keySet());
        int shoreLandColumns = 0;
        int sandyShoreColumns = 0;
        int waterLevelLandColumns = 0;
        int shallowWaterColumns = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 2) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 2) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                if (!isAquatic(sample)) {
                    int distanceToWater = distanceToAquaticTerrain(
                        world, x, z, SHORE_BLEND_WIDTH
                    );
                    if (distanceToWater > SHORE_BLEND_WIDTH) {
                        continue;
                    }
                    shoreLandColumns++;
                    if (groundY < SHORE_LAND_TARGET_Y) {
                        throw new IllegalStateException(
                            "Ordinary shore terrain dropped below Y="
                                + SHORE_LAND_TARGET_Y + " at " + x + "," + z
                        );
                    }
                    if (distanceToWater <= 2 && groundY == WATER_SURFACE_Y) {
                        waterLevelLandColumns++;
                    }
                    if (distanceToWater <= 4 && groundY > NORMAL_TERRAIN_MIN_Y) {
                        throw new IllegalStateException(
                            "Shore terrain is too steep to approach on foot at " + x + "," + z
                        );
                    }
                    if (isSandyShore(world, sample, x, z, groundY)) {
                        sandyShoreColumns++;
                        if (!level.getBlockState(new BlockPos(x, groundY, z)).is(Blocks.SAND)) {
                            throw new IllegalStateException(
                                "Low shore terrain is missing sand at " + x + "," + z
                            );
                        }
                    }
                    continue;
                }
                int distanceToLand = distanceToNonAquaticTerrain(world, x, z, 6);
                if (distanceToLand > 6) {
                    continue;
                }
                shallowWaterColumns++;
                if (groundY >= WATER_SURFACE_Y
                    || !level.getBlockState(new BlockPos(x, WATER_SURFACE_Y, z)).is(Blocks.WATER)) {
                    throw new IllegalStateException(
                        "Shallow shore water is missing a submerged floor at " + x + "," + z
                    );
                }
            }
        }
        LOGGER.info(
            "Walkable shore transition verified: landColumns={}, sandyLandColumns={}, waterLevelLandColumns={}, waterColumns={}, waterY={}, inlandMinY={}",
            shoreLandColumns, sandyShoreColumns, waterLevelLandColumns, shallowWaterColumns,
            WATER_SURFACE_Y, NORMAL_TERRAIN_MIN_Y
        );
        if (shoreLandColumns == 0 || sandyShoreColumns == 0
            || waterLevelLandColumns == 0 || shallowWaterColumns == 0) {
            throw new IllegalStateException("Aquatic boundaries did not produce a walkable shore");
        }
    }

    private static void verifyTerrainRelief(HexWorldPlan world) {
        HexBounds bounds = world.grid().bounds(world.cells().keySet());
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        double minimumDensity = Double.POSITIVE_INFINITY;
        double maximumDensity = Double.NEGATIVE_INFINITY;
        int samples = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 8) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 8) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || isAquatic(sample)
                    || sample.kind().equals("route")
                    || Math.abs(sample.terrainProfile().baseHeightOffset()) > 2
                    || sample.terrainProfile().heightVariation() < 3) {
                    continue;
                }
                int height = terrainGroundY(world, sample, x, z);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                double density = terrainDensity(world, sample, x, z);
                minimumDensity = Math.min(minimumDensity, density);
                maximumDensity = Math.max(maximumDensity, density);
                samples++;
            }
        }
        LOGGER.info(
            "NormalNoise terrain relief verified: samples={}, minY={}, maxY={}, range={}, densityMin={}, densityMax={}",
            samples, minimum, maximum, maximum - minimum, minimumDensity, maximumDensity
        );
        if (samples == 0 || maximum - minimum < 6) {
            throw new IllegalStateException(
                "NormalNoise terrain relief is too flat: samples=" + samples
                    + ", range=" + (maximum - minimum)
            );
        }
    }

    private static void verifyBoundaryWarp(HexWorldPlan world) {
        HexBounds bounds = world.grid().bounds(world.cells().keySet());
        double totalDisplacement = 0.0D;
        double maximumDisplacement = 0.0D;
        int samples = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 16) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 16) {
                WarpedPoint warped = warpedCellPoint(world, x + 0.5D, z + 0.5D);
                double displacement = Math.hypot(
                    warped.x() - (x + 0.5D), warped.z() - (z + 0.5D)
                );
                totalDisplacement += displacement;
                maximumDisplacement = Math.max(maximumDisplacement, displacement);
                samples++;
            }
        }
        double averageDisplacement = samples == 0 ? 0.0D : totalDisplacement / samples;
        LOGGER.info(
            "XZ boundary warp verified: samples={}, average={}, maximum={}",
            samples, averageDisplacement, maximumDisplacement
        );
        if (averageDisplacement < 8.0D || maximumDisplacement < 24.0D) {
            throw new IllegalStateException(
                "XZ boundary warp is too weak: average=" + averageDisplacement
                    + ", maximum=" + maximumDisplacement
            );
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
        BlockState expectedSurfaceState = isCoastalWater(
            world, selected.sample(), selected.point().x(), selected.point().z(), groundY
        ) ? Blocks.SAND.defaultBlockState() : surfaceBlock(selected.sample().biome());
        boolean expectedSurface = renderedSurface.is(expectedSurfaceState.getBlock())
            || (selected.sample().surfaceStyle().equals("road")
                && renderedSurface.is(Blocks.COBBLESTONE));
        if (!expectedSurface) {
            return false;
        }
        if (isAquatic(selected.sample())) {
            return groundY <= WATER_SURFACE_Y - minimumWaterDepth(selected.sample())
                && level.getBlockState(new BlockPos(
                    selected.point().x(), groundY + 1, selected.point().z()
                )).is(Blocks.WATER)
                && level.getBlockState(new BlockPos(
                    selected.point().x(), WATER_SURFACE_Y, selected.point().z()
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
                selected.point().x(), WATER_SURFACE_Y, selected.point().z()
            )).is(Blocks.WATER);
    }

    static TerrainSample terrainAt(HexWorldPlan world, double x, double z) {
        TerrainSample protectedTown = protectedSettlementTerrain(world, x, z);
        if (protectedTown != null) {
            return protectedTown;
        }
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

    private static TerrainSample protectedSettlementTerrain(
        HexWorldPlan world, double x, double z
    ) {
        double protectedRadius = TOWN_STRUCTURE_MAX_RADIUS_BLOCKS
            + TOWN_BOUNDARY_CLEARANCE_BLOCKS;
        double protectedRadiusSquared = protectedRadius * protectedRadius;
        HexSettlement selected = null;
        double selectedDistanceSquared = Double.POSITIVE_INFINITY;
        for (HexSettlement settlement : world.settlements().values()) {
            Point center = townFootprintWorldCenter(world.grid(), settlement);
            double deltaX = x - center.x();
            double deltaZ = z - center.z();
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared <= protectedRadiusSquared + 1.0E-6D
                && distanceSquared < selectedDistanceSquared) {
                selected = settlement;
                selectedDistanceSquared = distanceSquared;
            }
        }
        if (selected == null) {
            return null;
        }
        CellPlan plan = world.cells().get(selected.anchor());
        if (plan == null || !plan.kind().equals("town")
            || !plan.owner().equals(selected.settlement())) {
            return null;
        }
        return new TerrainSample(
            plan.biome(), plan.boundaryProfile(), plan.kind(), plan.owner(),
            plan.terrainProfile(), plan.accessRequirement(), plan.surfaceStyle()
        );
    }

    private static TerrainSample strongestCellInfluence(
        HexWorldPlan world, double x, double z, String kind
    ) {
        WarpedPoint warped = warpedCellPoint(world, x, z);
        CellPlan plan = world.cells().get(world.grid().worldToHex(warped.x(), warped.z()));
        if (plan == null || !plan.kind().equals(kind)) {
            return null;
        }
        return new TerrainSample(
            plan.biome(), plan.boundaryProfile(), plan.kind(), plan.owner(),
            plan.terrainProfile(), plan.accessRequirement(), plan.surfaceStyle()
        );
    }

    private static WarpedPoint warpedCellPoint(HexWorldPlan world, double x, double z) {
        HexCoord unwarpedCell = world.grid().worldToHex(x, z);
        CellPlan unwarpedPlan = world.cells().get(unwarpedCell);
        Point cellCenter = world.grid().worldCenter(unwarpedCell);
        double configuredEdgeNoise = unwarpedPlan == null ? 0.18D : unwarpedPlan.edgeNoise();
        double warpGain = 0.82D + Math.min(0.72D, configuredEdgeNoise * 3.2D);
        double protection = settlementWarpFactor(world, x, z);
        double broadWarp = world.grid().radius() * 1.20D * warpGain * protection;
        double mediumWarp = world.grid().radius() * 0.74D * warpGain * protection;
        double detailWarp = world.grid().radius() * 0.39D * warpGain * protection;
        double microWarp = world.grid().radius() * 0.21D * warpGain * protection;
        double offsetX = anchoredCellNoise(
            world, "world:cell-warp-x:broad", x, z, cellCenter,
            world.grid().radius() * 3.2D
        ) * broadWarp + anchoredCellNoise(
            world, "world:cell-warp-x:medium", x, z, cellCenter,
            world.grid().radius() * 1.15D
        ) * mediumWarp + anchoredCellNoise(
            world, "world:cell-warp-x:detail", x, z, cellCenter,
            world.grid().radius() * 0.38D
        ) * detailWarp + anchoredCellNoise(
            world, "world:cell-warp-x:micro", x, z, cellCenter,
            world.grid().radius() * 0.14D
        ) * microWarp;
        double offsetZ = anchoredCellNoise(
            world, "world:cell-warp-z:broad", x, z, cellCenter,
            world.grid().radius() * 3.2D
        ) * broadWarp + anchoredCellNoise(
            world, "world:cell-warp-z:medium", x, z, cellCenter,
            world.grid().radius() * 1.15D
        ) * mediumWarp + anchoredCellNoise(
            world, "world:cell-warp-z:detail", x, z, cellCenter,
            world.grid().radius() * 0.38D
        ) * detailWarp + anchoredCellNoise(
            world, "world:cell-warp-z:micro", x, z, cellCenter,
            world.grid().radius() * 0.14D
        ) * microWarp;
        double length = Math.hypot(offsetX, offsetZ);
        double maximumWarp = world.grid().radius() * 0.44D;
        if (length > maximumWarp) {
            double scale = maximumWarp / length;
            offsetX *= scale;
            offsetZ *= scale;
        }
        return new WarpedPoint(x + offsetX, z + offsetZ);
    }

    private static double anchoredCellNoise(
        HexWorldPlan world,
        String salt,
        double x,
        double z,
        Point cellCenter,
        double scale
    ) {
        return layeredNoise(world.seed(), salt, x, z, scale)
            - layeredNoise(
                world.seed(), salt, cellCenter.x(), cellCenter.z(), scale
            );
    }

    private static double settlementWarpFactor(HexWorldPlan world, double x, double z) {
        double closest = Double.POSITIVE_INFINITY;
        for (HexSettlement settlement : world.settlements().values()) {
            Point center = townFootprintWorldCenter(world.grid(), settlement);
            closest = Math.min(closest, Math.hypot(x - center.x(), z - center.z()));
        }
        return 0.16D + 0.84D * fade(Math.max(
            0.0D, Math.min(1.0D, (closest - 36.0D) / 44.0D)
        ));
    }

    private static TerrainSample strongestRouteInfluence(
        HexWorldPlan world, double x, double z
    ) {
        TerrainSample selected = null;
        double selectedStrength = Double.NEGATIVE_INFINITY;
        for (ConnectionPath route : world.paths()) {
            double distance = distanceToRoute(route.centerline(), x, z);
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
        List<Point> centerline, double x, double z
    ) {
        double closest = Double.POSITIVE_INFINITY;
        if (centerline.size() == 1) {
            Point point = centerline.getFirst();
            return Math.hypot(x - point.x(), z - point.z());
        }
        for (int index = 1; index < centerline.size(); index++) {
            Point start = centerline.get(index - 1);
            Point end = centerline.get(index);
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

    private static double layeredNoise(
        long seed, String salt, double x, double z, double scale
    ) {
        NoiseKey key = new NoiseKey(seed, salt);
        NormalNoise noise = TERRAIN_NOISES.computeIfAbsent(key, ignored -> NormalNoise.create(
            RandomSource.create(mixedNoiseSeed(seed, salt)),
            -1,
            1.0D, 1.0D, 0.5D, 0.25D
        ));
        double normalized = noise.getValue(x / scale, 0.0D, z / scale)
            / Math.max(0.0001D, noise.maxValue());
        return Math.max(-1.0D, Math.min(1.0D, normalized));
    }

    private static long mixedNoiseSeed(long seed, String salt) {
        long value = seed ^ ((long) salt.hashCode() * 0x9E3779B97F4A7C15L);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
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
        for (int sectionMinX = minX; sectionMinX <= maxX; sectionMinX += 128) {
            int sectionMaxX = Math.min(maxX, sectionMinX + 127);
            try {
                level.getServer().getCommands().getDispatcher().execute(
                    "fillbiome " + sectionMinX + " 55 " + minZ + " "
                        + sectionMaxX + " 96 " + maxZ + " " + biome,
                    level.getServer().createCommandSourceStack()
                        .withLevel(level)
                        .withPermission(4)
                        .withSuppressedOutput()
                );
            } catch (CommandSyntaxException error) {
                throw new IllegalStateException("Biome painting failed for " + biome, error);
            }
        }
    }

    private static void paintBiomes(
        ServerLevel level, HexWorldPlan world, HexBounds bounds, GenerationProgress progress
    ) {
        int biomeRows = Math.max(1, ((bounds.maxZ() - bounds.minZ()) / 4) + 1);
        int biomeRow = 0;
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 4) {
            String runBiome = null;
            int runStart = bounds.minX();
            for (int x = bounds.minX(); x <= bounds.maxX() + 4; x += 4) {
                TerrainSample sample = x <= bounds.maxX()
                    ? terrainAt(world, x + 1.5D, z + 1.5D)
                    : null;
                String biome = x > bounds.maxX()
                    ? null
                    : sample == null
                        ? emptyTerrainBiome(emptyTerrainAt(world, x + 1.5D, z + 1.5D))
                        : sample.biome();
                if ((runBiome == null && biome == null)
                    || (runBiome != null && runBiome.equals(biome))) {
                    continue;
                }
                if (runBiome != null) {
                    fillBiome(
                        level, runStart, z, x - 1,
                        Math.min(z + 3, bounds.maxZ()), runBiome
                    );
                }
                runBiome = biome;
                runStart = x;
            }
            progress.update(12 + (++biomeRow * 10 / biomeRows), "바이옴 구역 지정 중");
        }
    }

    private static void paintSurface(
        ServerLevel level, HexWorldPlan world, int x, int z, TerrainSample sample,
        boolean cleanExisting, TerrainWriteStats stats
    ) {
        stats.columns++;
        int groundY = terrainGroundY(world, sample, x, z);
        boolean aquatic = isAquatic(sample);
        boolean coastalWater = isCoastalWater(world, sample, x, z, groundY);
        boolean sandyShore = coastalWater || isSandyShore(world, sample, x, z, groundY);
        BlockState surface = sandyShore
            ? Blocks.SAND.defaultBlockState()
            : surfaceBlock(sample.biome());
        BlockState filler = sandyShore
            ? Blocks.SAND.defaultBlockState()
            : fillerBlock(sample.biome());
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(x, 0, z);
        if (aquatic && cleanExisting) {
            for (int y = DEEP_FOUNDATION_MIN_Y; y <= DEEP_FOUNDATION_MAX_Y; y++) {
                setTerrainBlock(level, position.setY(y), Blocks.BEDROCK.defaultBlockState(), stats);
            }
        }
        int stoneTop = Math.max(DEEP_FOUNDATION_MAX_Y + 1, groundY - 3);
        int stoneStart = cleanExisting ? DEEP_FOUNDATION_MAX_Y + 1 : 64;
        for (int y = stoneStart; y < stoneTop; y++) {
            setTerrainBlock(level, position.setY(y), Blocks.STONE.defaultBlockState(), stats);
        }
        for (int y = stoneTop; y < groundY; y++) {
            setTerrainBlock(level, position.setY(y), filler, stats);
        }
        setTerrainBlock(level, position.setY(groundY), surface, stats);
        int waterTop = aquatic || coastalWater ? WATER_SURFACE_Y : groundY;
        for (int y = groundY + 1; y <= waterTop; y++) {
            setTerrainBlock(level, position.setY(y), Blocks.WATER.defaultBlockState(), stats);
        }
        int clearTop = cleanExisting ? 112 : FLAT_GENERATOR_SURFACE_Y;
        for (int y = waterTop + 1; y <= clearTop; y++) {
            setTerrainBlock(level, position.setY(y), Blocks.AIR.defaultBlockState(), stats);
        }
    }

    private static void paintSealedOuterTerrain(
        ServerLevel level, HexWorldPlan world, int x, int z,
        boolean cleanExisting, TerrainWriteStats stats
    ) {
        stats.columns++;
        String type = emptyTerrainAt(world, x + 0.5D, z + 0.5D);
        if (type.equals("ocean")) {
            paintEmptyOceanColumn(level, world, x, z, cleanExisting, stats);
            return;
        }
        double broad = layeredNoise(world.seed(), "world:sealed-outer:broad", x, z, 72.0D);
        double detail = layeredNoise(world.seed(), "world:sealed-outer:detail", x, z, 24.0D);
        int baseY = type.equals("stone_mountain") || type.equals("snow_mountain") ? 100 : 94;
        int topY = baseY + (int) Math.round(broad * 7.0D + detail * 3.0D);
        topY = Math.max(88, Math.min(112, topY));
        paintBlockedOuterColumn(level, world, type, x, z, topY, cleanExisting, stats);
    }

    private static void paintEmptyOceanColumn(
        ServerLevel level, HexWorldPlan world, int x, int z,
        boolean cleanExisting, TerrainWriteStats stats
    ) {
        double floorNoise = layeredNoise(world.seed(), "world:empty-ocean:floor", x, z, 42.0D);
        int floorY = 42 + (int) Math.round(floorNoise * 5.0D);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(x, 0, z);
        if (cleanExisting) {
            for (int y = DEEP_FOUNDATION_MAX_Y + 1; y <= floorY - 1; y++) {
                setTerrainBlock(level, position.setY(y), Blocks.STONE.defaultBlockState(), stats);
            }
        }
        setTerrainBlock(level, position.setY(floorY), oceanFloorBlock(world, x, floorY, z), stats);
        for (int y = floorY + 1; y <= WATER_SURFACE_Y; y++) {
            setTerrainBlock(level, position.setY(y), Blocks.WATER.defaultBlockState(), stats);
        }
        int clearTop = cleanExisting ? 128 : FLAT_GENERATOR_SURFACE_Y;
        for (int y = WATER_SURFACE_Y + 1; y <= clearTop; y++) {
            setTerrainBlock(level, position.setY(y), Blocks.AIR.defaultBlockState(), stats);
        }
    }

    private static BlockState oceanFloorBlock(HexWorldPlan world, int x, int y, int z) {
        double noise = layeredNoise(world.seed(), "world:empty-ocean:material", x, z, 13.0D);
        return noise > 0.45D ? Blocks.GRAVEL.defaultBlockState()
            : noise < -0.5D ? Blocks.CLAY.defaultBlockState()
            : Blocks.SAND.defaultBlockState();
    }

    private static int distanceToPlayableTerrain(
        HexWorldPlan world, int x, int z, int maximumDistance
    ) {
        double[][] directions = {
            {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
            {0.707D, 0.707D}, {0.707D, -0.707D}, {-0.707D, 0.707D}, {-0.707D, -0.707D}
        };
        for (int distance = 2; distance <= maximumDistance; distance += 2) {
            for (double[] direction : directions) {
                if (terrainAt(
                    world, x + direction[0] * distance, z + direction[1] * distance
                ) != null) {
                    return distance;
                }
            }
        }
        return maximumDistance + 1;
    }

    static int terrainGroundY(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainSample supportingTerrain = supportingTerrainBelowRoute(world, sample, x, z);
        if (supportingTerrain != null) {
            return terrainGroundY(world, supportingTerrain, x, z);
        }
        int rawHeight = rawTerrainHeight(world, sample, x, z);
        if (isAquatic(sample)) {
            return aquaticGroundY(world, sample, x, z, rawHeight);
        }
        rawHeight = settlementPadGroundY(world, sample, x, z, rawHeight);
        if (sample.kind().equals("town") && sample.biome().contains("beach")) {
            rawHeight = Math.max(rawHeight, LEGACY_SURFACE_Y);
        }
        if ("cobbleventure:field_move/rock_climb".equals(sample.accessRequirement())) {
            return rawHeight;
        }
        return blendLandTowardWater(world, x, z, rawHeight);
    }

    private static TerrainSample supportingTerrainBelowRoute(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        if (!sample.kind().equals("route") || !sample.surfaceStyle().equals("road")) {
            return null;
        }
        TerrainSample town = strongestCellInfluence(world, x, z, "town");
        if (town != null) {
            return town;
        }
        return strongestCellInfluence(world, x, z, "surrounding");
    }

    private static int blendLandTowardWater(
        HexWorldPlan world, double x, double z, int naturalHeight
    ) {
        int distance = distanceToAquaticTerrain(world, x, z, SHORE_BLEND_WIDTH);
        if (distance > SHORE_BLEND_WIDTH) {
            return naturalHeight;
        }
        double progress = fade(Math.max(0.0D, Math.min(
            1.0D, distance / (double) SHORE_BLEND_WIDTH
        )));
        return (int) Math.round(
            SHORE_LAND_TARGET_Y + (naturalHeight - SHORE_LAND_TARGET_Y) * progress
        );
    }

    private static int distanceToAquaticTerrain(
        HexWorldPlan world, double x, double z, int maximumDistance
    ) {
        return distanceToTerrainType(world, x, z, maximumDistance, true);
    }

    private static int distanceToNonAquaticTerrain(
        HexWorldPlan world, double x, double z, int maximumDistance
    ) {
        return distanceToTerrainType(world, x, z, maximumDistance, false);
    }

    private static int distanceToTerrainType(
        HexWorldPlan world,
        double x,
        double z,
        int maximumDistance,
        boolean aquatic
    ) {
        ShoreDistanceField distances = activeShoreDistances;
        if (distances != null) {
            int distance = distances.distance(
                (int) Math.floor(x), (int) Math.floor(z), aquatic
            );
            if (distance >= 0) {
                return Math.min(maximumDistance + 1, distance);
            }
        }
        HexCoord current = world.grid().worldToHex(x, z);
        boolean matchingCellNearby = false;
        List<HexCoord> candidates = new ArrayList<>(current.neighbors());
        candidates.add(current);
        for (HexCoord candidate : candidates) {
            CellPlan plan = world.cells().get(candidate);
            if (plan == null) {
                continue;
            }
            boolean candidateAquatic = plan.surfaceStyle().equals("water")
                || plan.biome().contains("ocean")
                || plan.biome().contains("river");
            if (candidateAquatic == aquatic) {
                matchingCellNearby = true;
                break;
            }
        }
        if (!matchingCellNearby) {
            return maximumDistance + 1;
        }
        double[][] directions = {
            {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
            {0.707D, 0.707D}, {0.707D, -0.707D}, {-0.707D, 0.707D}, {-0.707D, -0.707D}
        };
        for (int distance = 2; distance <= maximumDistance; distance += 2) {
            for (double[] direction : directions) {
                TerrainSample nearby = terrainAt(
                    world, x + direction[0] * distance, z + direction[1] * distance
                );
                if (nearby != null && isAquatic(nearby) == aquatic) {
                    return distance;
                }
            }
        }
        return maximumDistance + 1;
    }

    private static boolean isCoastalWater(
        HexWorldPlan world, TerrainSample sample, double x, double z, int groundY
    ) {
        return !isAquatic(sample)
            && !sample.surfaceStyle().equals("road")
            && groundY < WATER_SURFACE_Y
            && hasNearbyOcean(world, x, z, 10)
            && !hasNearbyWorldEdge(world, x, z, 12);
    }

    private static boolean isSandyShore(
        HexWorldPlan world, TerrainSample sample, double x, double z, int groundY
    ) {
        return !isAquatic(sample)
            && !sample.surfaceStyle().equals("road")
            && groundY <= WATER_SURFACE_Y + SHORE_SAND_HEIGHT_BLOCKS
            && distanceToAquaticTerrain(world, x, z, SHORE_SAND_WIDTH_BLOCKS)
                <= SHORE_SAND_WIDTH_BLOCKS
            && !hasNearbyWorldEdge(world, x, z, 12);
    }

    private static boolean hasNearbyOcean(
        HexWorldPlan world, double x, double z, int maximumDistance
    ) {
        HexCoord current = world.grid().worldToHex(x, z);
        boolean oceanCellNearby = false;
        List<HexCoord> candidateCells = new ArrayList<>(current.neighbors());
        candidateCells.add(current);
        for (HexCoord candidate : candidateCells) {
            CellPlan plan = world.cells().get(candidate);
            if (plan != null && plan.biome().contains("ocean")) {
                oceanCellNearby = true;
                break;
            }
        }
        if (!oceanCellNearby) {
            return false;
        }
        double[][] directions = {
            {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
            {0.707D, 0.707D}, {0.707D, -0.707D}, {-0.707D, 0.707D}, {-0.707D, -0.707D}
        };
        int[] distances = {1, 2, 4, 7, maximumDistance};
        for (int distance : distances) {
            for (double[] direction : directions) {
                TerrainSample nearby = terrainAt(
                    world, x + direction[0] * distance, z + direction[1] * distance
                );
                if (nearby != null && nearby.biome().contains("ocean")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNearbyWorldEdge(
        HexWorldPlan world, double x, double z, int maximumDistance
    ) {
        double[][] directions = {
            {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
            {0.707D, 0.707D}, {0.707D, -0.707D}, {-0.707D, 0.707D}, {-0.707D, -0.707D}
        };
        int[] distances = {2, 6, maximumDistance};
        for (int distance : distances) {
            for (double[] direction : directions) {
                if (terrainAt(
                    world, x + direction[0] * distance, z + direction[1] * distance
                ) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int settlementPadGroundY(
        HexWorldPlan world,
        TerrainSample sample,
        double x,
        double z,
        int naturalHeight
    ) {
        if (!sample.kind().equals("town")) {
            return naturalHeight;
        }
        HexSettlement settlement = world.settlements().get(sample.owner());
        if (settlement == null) {
            return naturalHeight;
        }
        Point center = townFootprintWorldCenter(world.grid(), settlement);
        TerrainSample centerSample = terrainAt(
            world, center.x() + 0.5D, center.z() + 0.5D
        );
        if (centerSample == null) {
            return naturalHeight;
        }
        int referenceHeight = rawTerrainHeight(
            world, centerSample, center.x(), center.z()
        );
        int subduedHeight = referenceHeight + (int) Math.round(
            (naturalHeight - referenceHeight) * TOWN_RELIEF_SCALE
        );
        int edgeDistance = distanceToTownEdge(
            world, sample.owner(), x, z, TOWN_EDGE_RELIEF_BLEND_BLOCKS
        );
        double reliefReduction = fade(Math.max(0.0D, Math.min(
            1.0D, edgeDistance / (double) TOWN_EDGE_RELIEF_BLEND_BLOCKS
        )));
        return (int) Math.round(
            naturalHeight + (subduedHeight - naturalHeight) * reliefReduction
        );
    }

    private static int distanceToTownEdge(
        HexWorldPlan world,
        String settlementId,
        double x,
        double z,
        int maximumDistance
    ) {
        double[][] directions = {
            {1.0D, 0.0D}, {-1.0D, 0.0D}, {0.0D, 1.0D}, {0.0D, -1.0D},
            {0.707D, 0.707D}, {0.707D, -0.707D},
            {-0.707D, 0.707D}, {-0.707D, -0.707D}
        };
        for (int distance = 2; distance <= maximumDistance; distance += 2) {
            for (double[] direction : directions) {
                TerrainSample nearby = terrainAt(
                    world,
                    x + direction[0] * distance,
                    z + direction[1] * distance
                );
                if (nearby == null || !nearby.kind().equals("town")
                    || !nearby.owner().equals(settlementId)) {
                    return distance;
                }
            }
        }
        return maximumDistance;
    }

    private static int rawTerrainHeight(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainProfile terrain = sample.terrainProfile();
        double density = terrainDensity(world, sample, x, z);
        double maximumRelief = terrain.heightVariation() * 3.0D;
        double displacement = Math.max(
            -maximumRelief,
            Math.min(maximumRelief, density * terrain.heightVariation() * 8.0D)
        );
        int minimumY = isAquatic(sample)
            ? DEEP_FOUNDATION_MAX_Y + 1
            : "cobbleventure:field_move/rock_climb".equals(sample.accessRequirement())
                ? 74
                : NORMAL_TERRAIN_MIN_Y;
        int naturalBaseY = 68 + terrain.baseHeightOffset();
        int minimumPossibleY = (int) Math.floor(naturalBaseY - maximumRelief);
        int raisedBaseY = naturalBaseY + Math.max(0, minimumY - minimumPossibleY);
        int height = raisedBaseY + (int) Math.round(displacement);
        return Math.min(94, height);
    }

    private static double terrainDensity(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainProfile terrain = sample.terrainProfile();
        double continentalness = centeredTerrainNoise(
            world, "world:height:continentalness", x, z,
            Math.max(220.0D, terrain.noiseScaleBlocks() * 3.2D)
        );
        double erosion = centeredTerrainNoise(
            world, "world:height:erosion", x, z,
            Math.max(96.0D, terrain.noiseScaleBlocks() * 1.35D)
        );
        double ridgeSource = centeredTerrainNoise(
            world, "world:height:ridges", x, z,
            Math.max(52.0D, terrain.noiseScaleBlocks() * 0.72D)
        );
        double ridges = Math.copySign(ridgeSource * ridgeSource, ridgeSource);
        double detail = centeredTerrainNoise(
            world, "world:height:detail", x, z,
            Math.max(22.0D, terrain.noiseScaleBlocks() * 0.28D)
        );
        return
            continentalness * 0.22D
                + erosion * 0.62D
                + ridges * 0.48D
                + detail * 1.45D;
    }

    private static double centeredTerrainNoise(
        HexWorldPlan world, String salt, double x, double z, double scale
    ) {
        BlockPoint origin = world.grid().origin();
        return layeredNoise(world.seed(), salt, x, z, scale)
            - layeredNoise(world.seed(), salt, origin.x(), origin.z(), scale);
    }

    private static int aquaticGroundY(
        HexWorldPlan world, TerrainSample sample, double x, double z, int deepFloor
    ) {
        boolean ocean = sample.biome().contains("ocean");
        int transitionWidth = ocean ? 48 : 20;
        int distanceToShore = Math.min(
            transitionWidth,
            distanceToNonAquaticTerrain(world, x, z, transitionWidth)
        );
        int shoreFloor = WATER_SURFACE_Y - 1;
        int targetFloor = Math.min(
            deepFloor, WATER_SURFACE_Y - minimumWaterDepth(sample)
        );
        double progress = Math.max(0.0D, Math.min(
            1.0D, (distanceToShore - 1.0D) / Math.max(1.0D, transitionWidth - 1.0D)
        ));
        progress = fade(progress);
        return (int) Math.round(shoreFloor + (targetFloor - shoreFloor) * progress);
    }

    static boolean isAquatic(TerrainSample sample) {
        return sample.surfaceStyle().equals("water")
            || sample.biome().contains("ocean")
            || sample.biome().contains("river");
    }

    private static int minimumWaterDepth(TerrainSample sample) {
        return sample.biome().contains("ocean") ? 20 : 6;
    }

    static BlockState fillerBlock(String biome) {
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

    static BlockState surfaceBlock(String biome) {
        if (biome.contains("ocean")) {
            return biome.contains("warm")
                ? Blocks.SAND.defaultBlockState()
                : Blocks.GRAVEL.defaultBlockState();
        }
        if (biome.contains("beach")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (biome.contains("river")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (biome.contains("badlands")) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        if (biome.contains("desert")) {
            return Blocks.SAND.defaultBlockState();
        }
        if (biome.contains("snow") || biome.contains("ice")) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /**
     * Produces the immutable column description consumed by the native chunk
     * generator. Keeping this calculation here makes the legacy renderer and
     * the chunk generator share the same JSON map and terrain rules.
     */
    static NativeTerrainColumn nativeTerrainColumn(
        HexWorldPlan world, int x, int z
    ) {
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample == null) {
            String type = emptyTerrainAt(world, x + 0.5D, z + 0.5D);
            String biome = emptyTerrainBiome(type);
            PlayableEdge nearestPlayable = nearestPlayableTerrain(
                world, x, z, OUTER_TERRAIN_TRANSITION_WIDTH
            );
            boolean oceanBoundary = nearestPlayable != null
                && nearestPlayable.aquatic()
                && nearestPlayable.distance() <= OUTER_TERRAIN_TRANSITION_WIDTH;
            if (type.equals("ocean") || oceanBoundary) {
                if (oceanBoundary) {
                    biome = "minecraft:deep_ocean";
                }
                double floorNoise = layeredNoise(
                    world.seed(), "world:empty-ocean:floor", x, z, 42.0D
                );
                int floorY = 42 + (int) Math.round(floorNoise * 5.0D);
                int playableDistance = nearestPlayable == null
                    ? OUTER_TERRAIN_TRANSITION_WIDTH + 1
                    : nearestPlayable.distance();
                boolean rockyBoundary = isSparseOceanBoundaryRock(
                    world, x, z, playableDistance
                );
                if (rockyBoundary) {
                    double rockHeight = layeredNoise(
                        world.seed(), "world:native-ocean-rock-height", x, z, 8.0D
                    );
                    int topY = Math.max(OCEAN_CLIFF_MIN_Y, Math.min(
                        OCEAN_CLIFF_MAX_Y,
                        WATER_SURFACE_Y + 3 + (int) Math.round(
                            (rockHeight + 1.0D) * 3.5D - playableDistance * 0.18D
                        )
                    ));
                    return new NativeTerrainColumn(
                        topY, topY,
                        oceanCliffRock(world, x, topY, z),
                        Blocks.STONE.defaultBlockState(), biome, true, true
                    );
                }
                return new NativeTerrainColumn(
                    floorY, WATER_SURFACE_Y,
                    oceanFloorBlock(world, x, floorY, z),
                    Blocks.STONE.defaultBlockState(), biome, true, false
                );
            }
            double broad = layeredNoise(
                world.seed(), "world:sealed-outer:broad", x, z, 72.0D
            );
            double detail = layeredNoise(
                world.seed(), "world:sealed-outer:detail", x, z, 24.0D
            );
            int baseY = type.equals("stone_mountain")
                || type.equals("snow_mountain") ? 100 : 94;
            int topY = Math.max(88, Math.min(
                112,
                baseY + (int) Math.round(broad * 7.0D + detail * 3.0D)
            ));
            if (nearestPlayable != null && !nearestPlayable.aquatic()) {
                double transition = fade(Math.max(0.0D, Math.min(
                    1.0D,
                    (nearestPlayable.distance() - 1.0D)
                        / Math.max(1.0D, OUTER_TERRAIN_TRANSITION_WIDTH - 1.0D)
                )));
                topY = (int) Math.round(
                    nearestPlayable.groundY()
                        + (topY - nearestPlayable.groundY()) * transition
                );
            }
            return new NativeTerrainColumn(
                topY, topY,
                surfaceBlock(biome), fillerBlock(biome), biome, true, false
            );
        }

        int groundY = terrainGroundY(world, sample, x, z);
        boolean aquatic = isAquatic(sample);
        boolean coastalWater = isCoastalWater(world, sample, x, z, groundY);
        boolean sandyShore = isSandyShore(world, sample, x, z, groundY);
        BlockState surface = sample.surfaceStyle().equals("road") && !aquatic
            ? roadSurfaceBlock(world, sample, x, z)
            : sandyShore ? Blocks.SAND.defaultBlockState()
            : surfaceBlock(sample.biome());
        BlockState filler = sandyShore
            ? Blocks.SAND.defaultBlockState() : fillerBlock(sample.biome());
        return new NativeTerrainColumn(
            groundY,
            aquatic || coastalWater ? WATER_SURFACE_Y : groundY,
            surface,
            filler,
            sample.biome(),
            false,
            false
        );
    }

    private static PlayableEdge nearestPlayableTerrain(
        HexWorldPlan world, int x, int z, int radius
    ) {
        HexCoord current = world.grid().worldToHex(x + 0.5D, z + 0.5D);
        boolean playableCellNearby = world.cells().containsKey(current)
            || current.neighbors().stream().anyMatch(world.cells()::containsKey);
        if (!playableCellNearby) {
            return null;
        }
        double[][] directions = {
            {1.0D, 0.0D}, {0.9239D, 0.3827D}, {0.7071D, 0.7071D},
            {0.3827D, 0.9239D}, {0.0D, 1.0D}, {-0.3827D, 0.9239D},
            {-0.7071D, 0.7071D}, {-0.9239D, 0.3827D}, {-1.0D, 0.0D},
            {-0.9239D, -0.3827D}, {-0.7071D, -0.7071D},
            {-0.3827D, -0.9239D}, {0.0D, -1.0D}, {0.3827D, -0.9239D},
            {0.7071D, -0.7071D}, {0.9239D, -0.3827D}
        };
        for (int distance = 1; distance <= radius; distance++) {
            for (double[] direction : directions) {
                double sampleX = x + direction[0] * distance + 0.5D;
                double sampleZ = z + direction[1] * distance + 0.5D;
                TerrainSample nearby = terrainAt(
                    world, sampleX, sampleZ
                );
                if (nearby != null) {
                    return new PlayableEdge(
                        distance,
                        terrainGroundY(world, nearby, sampleX, sampleZ),
                        isAquatic(nearby)
                    );
                }
            }
        }
        return null;
    }

    private static boolean isSparseOceanBoundaryRock(
        HexWorldPlan world, int x, int z, int playableDistance
    ) {
        if (playableDistance > OCEAN_CLIFF_WIDTH) {
            return false;
        }
        double cluster = layeredNoise(
            world.seed(), "world:ocean-boundary-rock:cluster", x, z, 11.0D
        );
        double breakup = layeredNoise(
            world.seed(), "world:ocean-boundary-rock:breakup", x, z, 4.5D
        );
        double threshold = 0.38D + playableDistance * 0.018D;
        return cluster > threshold && breakup > -0.28D;
    }

    private static void drawOuterTerrainTransition(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<BoundaryEdge> boundaryEdges = new ArrayList<>();
        Set<Point> oceanCliffColumns = new HashSet<>();
        Set<Point> emptyOceanRockColumns = new HashSet<>();
        Set<Point> collisionColumns = new HashSet<>();
        Set<Point> requiredCollisionColumns = new HashSet<>();
        Set<Point> staleCollisionColumns = new HashSet<>();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null) {
                    continue;
                }
                List<int[]> outwardDirections = new ArrayList<>(4);
                for (int[] direction : directions) {
                    if (terrainAt(
                        world,
                        x + 0.5D + direction[0] * 2.0D,
                        z + 0.5D + direction[1] * 2.0D
                    ) == null) {
                        outwardDirections.add(direction);
                    }
                }
                if (outwardDirections.isEmpty()) {
                    continue;
                }
                collectHiddenCollisionShell(
                    world, x, z, directions, collisionColumns, requiredCollisionColumns
                );
                int edgeGroundY = terrainGroundY(world, sample, x, z) + 1;
                for (int[] outward : outwardDirections) {
                    int outwardX = outward[0];
                    int outwardZ = outward[1];
                    collectLegacyCollisionColumns(
                        x, z, outwardX, outwardZ, staleCollisionColumns
                    );
                    String outerType = emptyTerrainAt(
                        world, x + 0.5D + outwardX * 3.0D,
                        z + 0.5D + outwardZ * 3.0D
                    );
                    if (outerType.equals("ocean")) {
                        collectEmptyOceanRockColumns(
                            world, bounds, x, z, outwardX, outwardZ,
                            emptyOceanRockColumns
                        );
                        continue;
                    }
                    if (isAquatic(sample)) {
                        collectOceanCliffColumns(
                            world, bounds, x, z, outwardX, outwardZ,
                            oceanCliffColumns
                        );
                        continue;
                    }
                    boundaryEdges.add(new BoundaryEdge(
                        x, z, edgeGroundY, outwardX, outwardZ
                    ));
                }
            }
        }
        Map<Point, HeightAccumulator> outerTerrainHeights =
            buildHiddenOuterTerrainTransition(world, boundaryEdges, bounds);
        int filledOuterTerrainGaps = fillHiddenOuterTerrainGaps(
            world, outerTerrainHeights, 8
        );
        OuterTerrainStats outerTerrainStats = paintSmoothedHiddenOuterTerrain(
            level, world, outerTerrainHeights
        );
        paintOceanCliffs(level, world, oceanCliffColumns);
        paintEmptyOceanRocks(level, world, emptyOceanRockColumns);
        if (!collisionColumns.containsAll(requiredCollisionColumns)) {
            throw new IllegalStateException("Hidden collision shell contains an open terrain edge");
        }
        staleCollisionColumns.addAll(collisionColumns);
        int removedStaleBarrierBlocks = 0;
        for (Point column : staleCollisionColumns) {
            removedStaleBarrierBlocks += clearStaleBarrierColumn(
                level, column.x(), column.z()
            );
        }
        int barrierBlocks = 0;
        int preservedBlocks = 0;
        for (Point column : collisionColumns) {
            CollisionPlacement placement = drawHiddenBoundaryCollision(
                level, column.x(), column.z()
            );
            barrierBlocks += placement.placed();
            preservedBlocks += placement.preserved();
        }
        LOGGER.info(
            "Hidden terrain transition completed: outerTerrainColumns={}, oceanCliffColumns={}, emptyOceanRockColumns={}, filledTerrainGaps={}, outerTerrainY={}..{}, maximumOuterSlope={}, maximumPlayableSeam={}, collisionColumns={}, requiredEdgeColumns={}, removedStaleBarrierBlocks={}, barrierAirBlocks={}, preservedExistingBlocks={}",
            outerTerrainHeights.size(), oceanCliffColumns.size(), emptyOceanRockColumns.size(),
            filledOuterTerrainGaps,
            outerTerrainStats.minimumY(),
            outerTerrainStats.maximumY(), outerTerrainStats.maximumSlope(),
            outerTerrainStats.maximumPlayableSeam(),
            collisionColumns.size(), requiredCollisionColumns.size(),
            removedStaleBarrierBlocks, barrierBlocks, preservedBlocks
        );
        if (isFullHexWorldTest()
            && outerTerrainStats.maximumSlope() > 3) {
            throw new IllegalStateException(
                "Hidden outer terrain contains an abrupt slope: "
                    + outerTerrainStats.maximumSlope()
            );
        }
        if (isFullHexWorldTest()
            && outerTerrainStats.maximumPlayableSeam() > 1) {
            throw new IllegalStateException(
                "Hidden outer terrain is detached from the playable surface: "
                    + outerTerrainStats.maximumPlayableSeam()
            );
        }
        if (isFullHexWorldTest()
            && outerTerrainStats.maximumY() < SEALED_OUTER_MIN_Y) {
            throw new IllegalStateException(
                "Hidden outer terrain never reached its sealed background height: "
                    + outerTerrainStats.maximumY()
            );
        }
        if (isFullHexWorldTest()
            && (collisionColumns.isEmpty() || barrierBlocks == 0 || preservedBlocks == 0)) {
            throw new IllegalStateException(
                "Hidden collision boundary did not preserve terrain while placing its air-only band"
            );
        }
    }

    private static boolean isFullHexWorldTest() {
        return Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY)
            && Integer.getInteger(TEST_RENDER_RADIUS_PROPERTY, 0) <= 0;
    }

    private static void collectEmptyOceanRockColumns(
        HexWorldPlan world, HexBounds bounds, int edgeX, int edgeZ,
        int outwardX, int outwardZ, Set<Point> columns
    ) {
        int tangentX = -outwardZ;
        int tangentZ = outwardX;
        for (int distance = 1; distance <= 9; distance++) {
            for (int tangent = -3; tangent <= 3; tangent++) {
                int x = edgeX + outwardX * distance + tangentX * tangent;
                int z = edgeZ + outwardZ * distance + tangentZ * tangent;
                Point point = new Point(x, z);
                if (!bounds.contains(point)
                    || terrainAt(world, x + 0.5D, z + 0.5D) != null
                    || !emptyTerrainAt(world, x + 0.5D, z + 0.5D).equals("ocean")) {
                    continue;
                }
                if (isSparseOceanBoundaryRock(world, x, z, distance)) {
                    columns.add(point);
                }
            }
        }
    }

    private static void paintEmptyOceanRocks(
        ServerLevel level, HexWorldPlan world, Set<Point> columns
    ) {
        for (Point point : columns) {
            double heightNoise = layeredNoise(
                world.seed(), "world:empty-ocean:rock-height", point.x(), point.z(), 10.0D
            );
            int topY = WATER_SURFACE_Y + 3 + (int) Math.round((heightNoise + 1.0D) * 4.0D);
            int baseY = WATER_SURFACE_Y - 5;
            for (int y = baseY; y <= topY; y++) {
                double taper = (y - baseY) / (double) Math.max(1, topY - baseY);
                double shape = layeredNoise(
                    world.seed(), "world:empty-ocean:rock-shape",
                    point.x() + y * 0.17D, point.z() - y * 0.13D, 7.0D
                );
                if (taper < 0.72D || shape > taper - 0.9D) {
                    level.setBlock(
                        new BlockPos(point.x(), y, point.z()),
                        oceanCliffRock(world, point.x(), y, point.z()), 2
                    );
                }
            }
        }
        LOGGER.info("Blocked ocean rock formations completed: columns={}", columns.size());
    }

    private static void collectOceanCliffColumns(
        HexWorldPlan world,
        HexBounds bounds,
        int edgeX,
        int edgeZ,
        int outwardX,
        int outwardZ,
        Set<Point> columns
    ) {
        int tangentX = -outwardZ;
        int tangentZ = outwardX;
        for (int distance = 1; distance <= OCEAN_CLIFF_WIDTH; distance++) {
            int tangentRadius = distance <= 3 ? 2 : 1;
            for (int tangent = -tangentRadius; tangent <= tangentRadius; tangent++) {
                Point point = new Point(
                    edgeX + outwardX * distance + tangentX * tangent,
                    edgeZ + outwardZ * distance + tangentZ * tangent
                );
                if (bounds.contains(point)
                    && terrainAt(world, point.x() + 0.5D, point.z() + 0.5D) == null
                    && isSparseOceanBoundaryRock(
                        world, point.x(), point.z(), distance
                    )) {
                    columns.add(point);
                }
            }
        }
    }

    private static void paintOceanCliffs(
        ServerLevel level, HexWorldPlan world, Set<Point> columns
    ) {
        int ledgeBlocks = 0;
        for (Point point : columns) {
            double broad = layeredNoise(
                world.seed(), "world:ocean-cliff:broad", point.x(), point.z(), 46.0D
            );
            double ridge = layeredNoise(
                world.seed(), "world:ocean-cliff:ridge",
                point.x() * 0.72D, point.z() * 0.72D, 21.0D
            );
            double detail = layeredNoise(
                world.seed(), "world:ocean-cliff:detail", point.x(), point.z(), 12.0D
            );
            int topY = WATER_SURFACE_Y + 6 + (int) Math.round(
                broad * 3.0D + ridge * 2.5D + detail * 1.5D
            );
            topY = Math.max(OCEAN_CLIFF_MIN_Y, Math.min(OCEAN_CLIFF_MAX_Y, topY));
            ledgeBlocks += paintOceanCliffColumn(
                level, world, point.x(), point.z(), topY
            );
        }
        LOGGER.info(
            "Rocky ocean cliffs completed: columns={}, ledgeBlocks={}",
            columns.size(), ledgeBlocks
        );
    }

    private static int paintOceanCliffColumn(
        ServerLevel level, HexWorldPlan world, int x, int z, int topY
    ) {
        CliffFace face = findOceanCliffFace(world, x, z);
        int ledgeBlocks = 0;
        for (int y = DEEP_FOUNDATION_MAX_Y + 1; y <= topY; y++) {
            double faceNoise = layeredNoise(
                world.seed(), "world:ocean-cliff:face",
                x + y * 0.18D, z - y * 0.14D, 13.0D
            );
            boolean recessed = face != null
                && face.distance() == 1
                && y >= WATER_SURFACE_Y + 5
                && y <= topY - 3
                && faceNoise < -0.72D
                && cliffLedgeDepth(world, x, y, z, topY) == 0;
            BlockPos position = new BlockPos(x, y, z);
            level.setBlock(
                position,
                recessed ? Blocks.AIR.defaultBlockState() : oceanCliffRock(world, x, y, z),
                2
            );

            if (!recessed && face != null && face.distance() == 1
                && y >= WATER_SURFACE_Y + 3 && y <= topY - 2) {
                int projection = cliffLedgeDepth(world, x, y, z, topY);
                for (int depth = 1; depth <= projection; depth++) {
                    int projectionX = x + face.inwardX() * depth;
                    int projectionZ = z + face.inwardZ() * depth;
                    level.setBlock(
                        new BlockPos(projectionX, y, projectionZ),
                        oceanCliffRock(world, projectionX, y, projectionZ),
                        2
                    );
                    ledgeBlocks++;
                }
                if (projection >= 2) {
                    int tangentX = -face.inwardZ();
                    int tangentZ = face.inwardX();
                    double sideNoise = layeredNoise(
                        world.seed(), "world:ocean-cliff:ledge-side",
                        x + y * 0.31D, z + y * 0.23D, 7.0D
                    );
                    int sideReach = projection >= 4 ? 2 : 1;
                    for (int side = -sideReach; side <= sideReach; side++) {
                        if (side == 0 || (side < 0 && sideNoise > 0.72D)
                            || (side > 0 && sideNoise < -0.72D)) {
                            continue;
                        }
                        int ledgeX = x + face.inwardX() * Math.max(1, projection - 1)
                            + tangentX * side;
                        int ledgeZ = z + face.inwardZ() * Math.max(1, projection - 1)
                            + tangentZ * side;
                        level.setBlock(
                            new BlockPos(ledgeX, y, ledgeZ),
                            oceanCliffRock(world, ledgeX, y, ledgeZ), 2
                        );
                        ledgeBlocks++;
                    }
                }
            }
        }
        for (int y = topY + 1; y <= 128; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return ledgeBlocks;
    }

    private static int cliffLedgeDepth(
        HexWorldPlan world, int x, int y, int z, int topY
    ) {
        double contour = layeredNoise(
            world.seed(), "world:ocean-cliff:ledge-contour", x, z, 28.0D
        );
        double local = layeredNoise(
            world.seed(), "world:ocean-cliff:ledge-local",
            x + y * 0.23D, z - y * 0.19D, 9.0D
        );
        double lowerCenter = WATER_SURFACE_Y + 7.0D + contour * 2.5D;
        double middleCenter = WATER_SURFACE_Y + 16.0D - contour * 3.0D;
        double upperCenter = topY - 7.0D + contour * 1.5D;
        double lowerBand = Math.max(0.0D, 1.0D - Math.abs(y - lowerCenter) / 3.2D);
        double middleBand = Math.max(0.0D, 1.0D - Math.abs(y - middleCenter) / 3.8D);
        double upperBand = Math.max(0.0D, 1.0D - Math.abs(y - upperCenter) / 2.8D);
        double strength = Math.max(lowerBand * 0.72D, Math.max(
            middleBand, upperBand * 0.82D
        ));
        if (strength < 0.2D || local < -0.56D) {
            return 0;
        }
        return Math.max(1, Math.min(5, (int) Math.round(
            strength * 4.2D + Math.max(0.0D, local) * 1.6D
        )));
    }

    private static CliffFace findOceanCliffFace(
        HexWorldPlan world, int x, int z
    ) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int distance = 1; distance <= OCEAN_CLIFF_WIDTH + 2; distance++) {
            for (int[] direction : directions) {
                TerrainSample sample = terrainAt(
                    world,
                    x + direction[0] * distance + 0.5D,
                    z + direction[1] * distance + 0.5D
                );
                if (sample != null && isAquatic(sample)) {
                    return new CliffFace(direction[0], direction[1], distance);
                }
            }
        }
        return null;
    }

    static BlockState oceanCliffRock(
        HexWorldPlan world, int x, int y, int z
    ) {
        double strata = layeredNoise(
            world.seed(), "world:ocean-cliff:strata",
            x * 0.42D + y * 0.16D, z * 0.42D - y * 0.13D, 19.0D
        );
        double detail = layeredNoise(
            world.seed(), "world:ocean-cliff:material",
            x + y * 0.31D, z - y * 0.27D, 7.0D
        );
        double vein = layeredNoise(
            world.seed(), "world:ocean-cliff:vein",
            x * 0.7D - y * 0.22D, z * 0.7D + y * 0.19D, 11.0D
        );
        if (y <= WATER_SURFACE_Y + 2 && detail > 0.18D) {
            return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        }
        if (vein > 0.78D && detail > 0.12D) {
            return Blocks.CALCITE.defaultBlockState();
        }
        if (strata < -0.5D) {
            return detail < -0.25D
                ? Blocks.TUFF.defaultBlockState()
                : Blocks.COBBLESTONE.defaultBlockState();
        }
        if (strata > 0.52D || detail > 0.48D) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (y < WATER_SURFACE_Y - 8 && detail < -0.38D) {
            return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        }
        if (detail < -0.58D) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static void collectLegacyCollisionColumns(
        int edgeX,
        int edgeZ,
        int outwardX,
        int outwardZ,
        Set<Point> staleCollisionColumns
    ) {
        int tangentX = -outwardZ;
        int tangentZ = outwardX;
        for (int distance = 1; distance <= LEGACY_VISIBLE_BOUNDARY_CLEANUP_RADIUS; distance++) {
            for (int tangent = -2; tangent <= 2; tangent++) {
                staleCollisionColumns.add(new Point(
                    edgeX + outwardX * distance + tangentX * tangent,
                    edgeZ + outwardZ * distance + tangentZ * tangent
                ));
            }
        }
    }

    private static void collectHiddenCollisionShell(
        HexWorldPlan world,
        int edgeX,
        int edgeZ,
        int[][] cardinalDirections,
        Set<Point> collisionColumns,
        Set<Point> requiredCollisionColumns
    ) {
        for (int[] direction : cardinalDirections) {
            int x = edgeX + direction[0];
            int z = edgeZ + direction[1];
            if (terrainAt(world, x + 0.5D, z + 0.5D) == null) {
                requiredCollisionColumns.add(new Point(x, z));
            }
        }
        for (int offsetX = -COLLISION_SHELL_RADIUS;
             offsetX <= COLLISION_SHELL_RADIUS; offsetX++) {
            for (int offsetZ = -COLLISION_SHELL_RADIUS;
                 offsetZ <= COLLISION_SHELL_RADIUS; offsetZ++) {
                int x = edgeX + offsetX;
                int z = edgeZ + offsetZ;
                if (terrainAt(world, x + 0.5D, z + 0.5D) == null) {
                    collisionColumns.add(new Point(x, z));
                }
            }
        }
    }

    private static Map<Point, HeightAccumulator> buildHiddenOuterTerrainTransition(
        HexWorldPlan world, List<BoundaryEdge> edges, HexBounds bounds
    ) {
        Map<Point, Integer> distances = new HashMap<>();
        Map<Point, HeightAccumulator> edgeHeights = new HashMap<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        for (BoundaryEdge edge : edges) {
            Point seed = new Point(
                edge.x() + edge.outwardX(), edge.z() + edge.outwardZ()
            );
            if (!bounds.contains(seed)
                || terrainAt(world, seed.x() + 0.5D, seed.z() + 0.5D) != null) {
                continue;
            }
            edgeHeights.computeIfAbsent(
                seed, ignored -> new HeightAccumulator()
            ).add(Math.max(WATER_SURFACE_Y, edge.edgeGroundY() - 1));
            if (distances.putIfAbsent(seed, 1) == null) {
                queue.addLast(seed);
            }
        }

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Point point = queue.removeFirst();
            int distance = distances.get(point);
            if (distance >= OUTER_TERRAIN_TRANSITION_WIDTH) {
                continue;
            }
            int inheritedEdgeY = edgeHeights.get(point).average();
            for (int[] direction : directions) {
                Point next = point.translate(direction[0], direction[1]);
                if (!bounds.contains(next)
                    || terrainAt(world, next.x() + 0.5D, next.z() + 0.5D) != null) {
                    continue;
                }
                int nextDistance = distance + 1;
                Integer previousDistance = distances.get(next);
                if (previousDistance != null && previousDistance < nextDistance) {
                    continue;
                }
                edgeHeights.computeIfAbsent(
                    next, ignored -> new HeightAccumulator()
                ).add(inheritedEdgeY);
                if (previousDistance == null) {
                    distances.put(next, nextDistance);
                    queue.addLast(next);
                }
            }
        }

        Map<Point, HeightAccumulator> heights = new HashMap<>();
        for (Map.Entry<Point, Integer> entry : distances.entrySet()) {
            Point point = entry.getKey();
            double progress = fade(
                entry.getValue() / (double) OUTER_TERRAIN_TRANSITION_WIDTH
            );
            double heightNoise = layeredNoise(
                world.seed(), "world:hidden-rise-height", point.x(), point.z(), 54.0D
            );
            int transitionStartY = edgeHeights.get(point).average();
            int outerTopY = Math.max(
                SEALED_OUTER_MIN_Y,
                SEALED_OUTER_SURFACE_Y + (int) Math.round(heightNoise * 4.0D)
            );
            int topY = (int) Math.round(
                transitionStartY * (1.0D - progress) + outerTopY * progress
            );
            heights.computeIfAbsent(
                point, ignored -> new HeightAccumulator()
            ).add(topY);
        }
        return heights;
    }

    private static int fillHiddenOuterTerrainGaps(
        HexWorldPlan world,
        Map<Point, HeightAccumulator> heights,
        int maximumGapWidth
    ) {
        Map<Point, Integer> snapshot = new HashMap<>();
        heights.forEach((point, accumulator) -> snapshot.put(point, accumulator.average()));
        Map<Point, HeightAccumulator> filled = new HashMap<>();
        int[][] axes = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (Map.Entry<Point, Integer> entry : snapshot.entrySet()) {
            Point start = entry.getKey();
            for (int[] axis : axes) {
                for (int span = 2; span <= maximumGapWidth + 1; span++) {
                    Point end = start.translate(axis[0] * span, axis[1] * span);
                    Integer endHeight = snapshot.get(end);
                    if (endHeight == null) {
                        continue;
                    }
                    boolean clearGap = true;
                    for (int offset = 1; offset < span; offset++) {
                        Point point = start.translate(axis[0] * offset, axis[1] * offset);
                        if (snapshot.containsKey(point)
                            || terrainAt(world, point.x() + 0.5D, point.z() + 0.5D) != null) {
                            clearGap = false;
                            break;
                        }
                    }
                    if (!clearGap) {
                        break;
                    }
                    for (int offset = 1; offset < span; offset++) {
                        Point point = start.translate(axis[0] * offset, axis[1] * offset);
                        double progress = offset / (double) span;
                        int height = (int) Math.round(
                            entry.getValue() * (1.0D - progress) + endHeight * progress
                        );
                        filled.computeIfAbsent(
                            point, ignored -> new HeightAccumulator()
                        ).add(Math.max(WATER_SURFACE_Y, height));
                    }
                    break;
                }
            }
        }
        filled.forEach((point, accumulator) ->
            heights.computeIfAbsent(point, ignored -> new HeightAccumulator())
                .add(accumulator.average())
        );
        return filled.size();
    }

    private static OuterTerrainStats paintSmoothedHiddenOuterTerrain(
        ServerLevel level,
        HexWorldPlan world,
        Map<Point, HeightAccumulator> accumulatedHeights
    ) {
        Map<Point, Integer> heights = new HashMap<>();
        for (Map.Entry<Point, HeightAccumulator> entry : accumulatedHeights.entrySet()) {
            heights.put(entry.getKey(), entry.getValue().average());
        }
        int[][] neighbors = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},            {0, 1},
            {1, -1},  {1, 0},  {1, 1}
        };
        Map<Point, Integer> playableAnchors = new HashMap<>();
        for (Point point : heights.keySet()) {
            for (int[] neighbor : neighbors) {
                Point neighborPoint = point.translate(neighbor[0], neighbor[1]);
                if (heights.containsKey(neighborPoint)
                    || playableAnchors.containsKey(neighborPoint)) {
                    continue;
                }
                TerrainSample sample = terrainAt(
                    world, neighborPoint.x() + 0.5D, neighborPoint.z() + 0.5D
                );
                if (sample != null) {
                    playableAnchors.put(
                        neighborPoint,
                        terrainGroundY(world, sample, neighborPoint.x(), neighborPoint.z())
                    );
                }
            }
        }
        for (int pass = 0; pass < 5; pass++) {
            Map<Point, Integer> smoothed = new HashMap<>(heights.size());
            for (Map.Entry<Point, Integer> entry : heights.entrySet()) {
                int sum = entry.getValue() * 3;
                int weight = 3;
                for (int[] neighbor : neighbors) {
                    Integer neighborHeight = heights.get(entry.getKey().translate(
                        neighbor[0], neighbor[1]
                    ));
                    if (neighborHeight == null) {
                        neighborHeight = playableAnchors.get(entry.getKey().translate(
                            neighbor[0], neighbor[1]
                        ));
                    }
                    if (neighborHeight != null) {
                        sum += neighborHeight;
                        weight++;
                    }
                }
                smoothed.put(entry.getKey(), (int) Math.round(sum / (double) weight));
            }
            heights = smoothed;
        }
        for (int pass = 0; pass < 32; pass++) {
            Map<Point, Integer> limited = new HashMap<>(heights.size());
            boolean changed = false;
            for (Map.Entry<Point, Integer> entry : heights.entrySet()) {
                int height = entry.getValue();
                int limitedHeight = height;
                for (int[] neighbor : neighbors) {
                    Point neighborPoint = entry.getKey().translate(neighbor[0], neighbor[1]);
                    Integer neighborHeight = heights.get(neighborPoint);
                    if (neighborHeight == null) {
                        neighborHeight = playableAnchors.get(neighborPoint);
                    }
                    if (neighborHeight != null) {
                        int maximumStep = Math.abs(neighbor[0]) + Math.abs(neighbor[1]);
                        limitedHeight = Math.min(limitedHeight, neighborHeight + maximumStep);
                    }
                }
                limited.put(entry.getKey(), limitedHeight);
                changed |= limitedHeight != height;
            }
            heights = limited;
            if (!changed) {
                break;
            }
        }
        int maximumSlope = 0;
        int maximumPlayableSeam = 0;
        int minimumY = Integer.MAX_VALUE;
        int maximumY = Integer.MIN_VALUE;
        for (Map.Entry<Point, Integer> entry : heights.entrySet()) {
            Point point = entry.getKey();
            int height = entry.getValue();
            for (int[] neighbor : neighbors) {
                Point neighborPoint = point.translate(neighbor[0], neighbor[1]);
                Integer neighborHeight = heights.get(neighborPoint);
                if (neighborHeight != null) {
                    maximumSlope = Math.max(maximumSlope, Math.abs(height - neighborHeight));
                }
                Integer playableHeight = playableAnchors.get(neighborPoint);
                if (playableHeight != null
                    && Math.abs(neighbor[0]) + Math.abs(neighbor[1]) == 1) {
                    maximumPlayableSeam = Math.max(
                        maximumPlayableSeam, Math.abs(height - playableHeight)
                    );
                }
            }
            minimumY = Math.min(minimumY, height);
            maximumY = Math.max(maximumY, height);
            paintOuterTransitionColumn(level, point.x(), point.z(), height);
        }
        if (heights.isEmpty()) {
            minimumY = SEALED_OUTER_MIN_Y;
            maximumY = SEALED_OUTER_MIN_Y;
        }
        return new OuterTerrainStats(
            maximumSlope, maximumPlayableSeam, minimumY, maximumY
        );
    }

    private static void paintBlockedOuterColumn(
        ServerLevel level, HexWorldPlan world, String type, int x, int z, int topY,
        boolean cleanExisting, TerrainWriteStats stats
    ) {
        topY = Math.max(SEALED_OUTER_MIN_Y, Math.min(112, topY));
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = 69; y <= topY - 3; y++) {
            BlockState filler = switch (type) {
                case "desert" -> y >= topY - 7
                    ? Blocks.SANDSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                case "stone_mountain", "snow_mountain" -> oceanCliffRock(world, x, y, z);
                default -> Blocks.STONE.defaultBlockState();
            };
            setTerrainBlock(level, position.setY(y), filler, stats);
        }
        BlockState subsurface = type.equals("desert")
            ? Blocks.SANDSTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        BlockState surface = switch (type) {
            case "desert" -> Blocks.SAND.defaultBlockState();
            case "stone_mountain" -> oceanCliffRock(world, x, topY, z);
            case "snow_mountain" -> Blocks.SNOW_BLOCK.defaultBlockState();
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
        setTerrainBlock(level, position.setY(topY - 2), subsurface, stats);
        setTerrainBlock(level, position.setY(topY - 1), subsurface, stats);
        setTerrainBlock(level, position.setY(topY), surface, stats);
        if (cleanExisting) {
            for (int y = topY + 1; y <= 128; y++) {
                setTerrainBlock(level, position.setY(y), Blocks.AIR.defaultBlockState(), stats);
            }
        }
    }

    private static void setTerrainBlock(
        ServerLevel level, BlockPos position, BlockState state, TerrainWriteStats stats
    ) {
        stats.attempted++;
        if (level.getBlockState(position).equals(state)) {
            stats.skipped++;
            return;
        }
        level.setBlock(position, state, 2);
        stats.changed++;
    }

    private static void paintOuterTransitionColumn(
        ServerLevel level, int x, int z, int topY
    ) {
        topY = Math.max(WATER_SURFACE_Y, Math.min(98, topY));
        paintOuterColumn(level, x, z, topY);
    }

    private static void paintOuterColumn(
        ServerLevel level, int x, int z, int topY
    ) {
        for (int y = 69; y <= topY - 3; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
        }
        level.setBlock(new BlockPos(x, topY - 2, z), Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x, topY - 1, z), Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(new BlockPos(x, topY, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        // The sealed background is painted first at roughly Y=88..98.  A low
        // transition column can therefore have more than fourteen blocks of old
        // terrain above its new surface.  Clear the entire generated/decorated
        // range so that no second floating shelf survives above the slope.
        for (int y = topY + 1; y <= 128; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void decorateSealedOuterForest(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        int trees = 0;
        int spacing = OUTER_FOREST_TREE.spacing();
        for (int gridX = bounds.minX(); gridX <= bounds.maxX(); gridX += spacing) {
            for (int gridZ = bounds.minZ(); gridZ <= bounds.maxZ(); gridZ += spacing) {
                long seed = world.seed() ^ gridX * 341873128712L ^ gridZ * 132897987541L;
                int x = gridX + Math.floorMod((int) (seed >>> 17), 5) - 2;
                int z = gridZ + Math.floorMod((int) (seed >>> 33), 5) - 2;
                if (terrainAt(world, x + 0.5D, z + 0.5D) != null
                    || !emptyTerrainAt(world, x + 0.5D, z + 0.5D).equals("high_forest")
                    || distanceToPlayableTerrain(world, x, z, 12) <= 12) {
                    continue;
                }
                int groundY = sealedForestGroundY(level, x, z);
                if (groundY < 0 || !level.getBlockState(new BlockPos(x, groundY, z)).is(Blocks.GRASS_BLOCK)) {
                    continue;
                }
                if (placeVanillaTree(
                    level, new BlockPos(x, groundY + 1, z), OUTER_FOREST_TREE, seed
                )) {
                    trees++;
                }
            }
        }
        LOGGER.info("Sealed dark forest completed: trees={}", trees);
        if (Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY) && trees == 0) {
            throw new IllegalStateException("Sealed dark forest did not place any trees");
        }
    }

    private static int sealedForestGroundY(ServerLevel level, int x, int z) {
        for (int y = 104; y >= 60; y--) {
            if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.GRASS_BLOCK)) {
                return y;
            }
        }
        return -1;
    }

    private static CollisionPlacement drawHiddenBoundaryCollision(
        ServerLevel level, int x, int z
    ) {
        int placed = 0;
        int preserved = 0;
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            BlockPos position = new BlockPos(x, y, z);
            if (placeBarrierInAir(level, position)) {
                placed++;
            } else {
                preserved++;
            }
        }
        return new CollisionPlacement(placed, preserved);
    }

    private static int clearStaleBarrierColumn(ServerLevel level, int x, int z) {
        int removed = 0;
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            BlockPos position = new BlockPos(x, y, z);
            if (!level.getBlockState(position).is(Blocks.BARRIER)) {
                continue;
            }
            BlockState replacement;
            if (y <= DEEP_FOUNDATION_MAX_Y) {
                replacement = Blocks.BEDROCK.defaultBlockState();
            } else if (y <= BCA_REFERENCE_SURFACE_Y) {
                replacement = Blocks.STONE.defaultBlockState();
            } else {
                replacement = Blocks.AIR.defaultBlockState();
            }
            level.setBlock(position, replacement, 2);
            removed++;
        }
        return removed;
    }

    private static boolean placeBarrierInAir(ServerLevel level, BlockPos position) {
        if (!level.getBlockState(position).isAir()) {
            return false;
        }
        level.setBlock(position, Blocks.BARRIER.defaultBlockState(), 2);
        return true;
    }

    private static boolean placeVanillaTree(
        ServerLevel level, BlockPos position, TreeProfile tree, long seed
    ) {
        List<String> featureIds;
        if (tree.log().contains("dark_oak")) {
            featureIds = List.of("dark_oak_checked", "fancy_oak_checked", "oak_checked");
        } else if (tree.log().contains("spruce")) {
            featureIds = List.of("spruce_checked", "pine_checked");
        } else if (tree.log().contains("birch")) {
            featureIds = List.of("birch_checked", "oak_checked");
        } else if (tree.log().contains("acacia")) {
            featureIds = List.of("acacia_checked", "oak_checked");
        } else if (tree.log().contains("cherry")) {
            featureIds = List.of("cherry_checked", "birch_checked");
        } else {
            featureIds = List.of("oak_checked");
        }
        var registry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        for (String featureId : featureIds) {
            ResourceKey<PlacedFeature> key = ResourceKey.create(
                Registries.PLACED_FEATURE,
                ResourceLocation.withDefaultNamespace(featureId)
            );
            var feature = registry.getHolder(key);
            if (feature.isPresent() && feature.get().value().place(
                level,
                level.getChunkSource().getGenerator(),
                RandomSource.create(seed ^ position.asLong() ^ featureId.hashCode()),
                position
            )) {
                return true;
            }
        }
        return false;
    }

    private static void decorateTownLandscape(
        ServerLevel level, HexWorldPlan world, SettlementPlan settlement
    ) {
        HexSettlement hexSettlement = world.settlements().get(settlement.id());
        if (hexSettlement == null) {
            LOGGER.warn("Cannot landscape missing hex settlement: {}", settlement.id());
            return;
        }
        String biome = hexSettlement.townBiome();
        int spacing = biome.contains("forest") ? 12
            : biome.contains("badlands") ? 20
                : biome.contains("beach") ? 17
                    : 14;
        HexBounds bounds = townLandscapeBounds(world, settlement.id());
        if (bounds == null) {
            LOGGER.warn("Cannot find town landscape cells: {}", settlement.id());
            return;
        }
        int trees = 0;
        long baseSeed = world.seed() ^ ((long) settlement.id().hashCode() << 32);
        for (int gridX = bounds.minX(); gridX <= bounds.maxX(); gridX += spacing) {
            for (int gridZ = bounds.minZ(); gridZ <= bounds.maxZ(); gridZ += spacing) {
                long seed = baseSeed
                    ^ (long) gridX * 341873128712L
                    ^ (long) gridZ * 132897987541L;
                int jitter = Math.max(2, spacing / 3);
                int x = gridX + Math.floorMod(
                    (int) (seed >>> 17), jitter * 2 + 1
                ) - jitter;
                int z = gridZ + Math.floorMod(
                    (int) (seed >>> 37), jitter * 2 + 1
                ) - jitter;
                if (isNearSettlementAnchor(settlement, x, z, 8.0D)
                    || isNearGeneratedTownBuilding(settlement, x, z, 7)
                    || Math.floorMod((int) (seed ^ seed >>> 32), 100)
                        >= townTreeChance(biome)) {
                    continue;
                }
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || !sample.kind().equals("town")
                    || !sample.owner().equals(settlement.id())) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                if (!isOpenTownLandscapeSite(level, world, sample, x, groundY, z, 3, 10)) {
                    continue;
                }
                if (placeTownTree(level, biome, new BlockPos(x, groundY, z), seed)) {
                    trees++;
                }
            }
        }
        int groundDecorations = decorateTownGroundCover(
            level, world, settlement, biome, bounds, baseSeed
        );
        LOGGER.info(
            "Town landscaping completed: settlement={}, biome={}, cells={}, trees={}, groundDecorations={}",
            settlement.id(), biome, townCellCount(world, settlement.id()), trees,
            groundDecorations
        );
    }

    private static HexBounds townLandscapeBounds(
        HexWorldPlan world, String settlementId
    ) {
        Set<HexCoord> cells = new HashSet<>();
        for (Map.Entry<HexCoord, CellPlan> entry : world.cells().entrySet()) {
            CellPlan plan = entry.getValue();
            if (plan.kind().equals("town") && plan.owner().equals(settlementId)) {
                cells.add(entry.getKey());
            }
        }
        return cells.isEmpty() ? null : world.grid().bounds(cells);
    }

    private static int townCellCount(HexWorldPlan world, String settlementId) {
        int count = 0;
        for (CellPlan plan : world.cells().values()) {
            if (plan.kind().equals("town") && plan.owner().equals(settlementId)) {
                count++;
            }
        }
        return count;
    }

    private static int townTreeChance(String biome) {
        if (biome.contains("forest")) {
            return 72;
        }
        if (biome.contains("beach")) {
            return 34;
        }
        if (biome.contains("badlands") || biome.contains("desert")) {
            return 14;
        }
        if (biome.contains("peak") || biome.contains("mountain")
            || biome.contains("windswept")) {
            return 32;
        }
        return 24;
    }

    private static boolean isNearSettlementAnchor(
        SettlementPlan settlement, int x, int z, double radius
    ) {
        double radiusSquared = radius * radius;
        for (BlockPoint anchor : settlement.anchors().values()) {
            double dx = x - anchor.x();
            double dz = z - anchor.z();
            if (dx * dx + dz * dz <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNearGeneratedTownBuilding(
        SettlementPlan settlement, int x, int z, int clearance
    ) {
        Point center = new Point(settlement.center().x(), settlement.center().z());
        TownLayout layout = generateTownLayout(settlement);
        for (TownPlot plot : layout.houses()) {
            if (isInsideExpandedTownPlot(center, plot, x, z, clearance)) return true;
        }
        for (TownPlot plot : layout.facilities().values()) {
            if (isInsideExpandedTownPlot(center, plot, x, z, clearance)) return true;
        }
        return false;
    }

    private static boolean isInsideExpandedTownPlot(
        Point center, TownPlot plot, int x, int z, int clearance
    ) {
        int minX = center.x() + (int) Math.round(plot.x()) - clearance;
        int minZ = center.z() + (int) Math.round(plot.z()) - clearance;
        int maxX = minX + plot.width() + clearance * 2 - 1;
        int maxZ = minZ + plot.depth() + clearance * 2 - 1;
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static boolean isOpenTownLandscapeSite(
        ServerLevel level,
        HexWorldPlan world,
        TerrainSample sample,
        int x,
        int groundY,
        int z,
        int clearance,
        int clearHeight
    ) {
        if (!isNaturalTownGround(level.getBlockState(new BlockPos(x, groundY, z)))) {
            return false;
        }
        for (int offsetX = -clearance; offsetX <= clearance; offsetX++) {
            for (int offsetZ = -clearance; offsetZ <= clearance; offsetZ++) {
                if (offsetX * offsetX + offsetZ * offsetZ > clearance * clearance) {
                    continue;
                }
                int columnX = x + offsetX;
                int columnZ = z + offsetZ;
                TerrainSample neighbor = terrainAt(world, columnX + 0.5D, columnZ + 0.5D);
                if (neighbor == null || !neighbor.kind().equals("town")
                    || !neighbor.owner().equals(sample.owner())) {
                    return false;
                }
                int neighborGroundY = terrainGroundY(world, neighbor, columnX, columnZ);
                if (Math.abs(neighborGroundY - groundY) > 2) {
                    return false;
                }
                for (int y = neighborGroundY + 1; y <= neighborGroundY + clearHeight; y++) {
                    BlockState state = level.getBlockState(new BlockPos(columnX, y, columnZ));
                    if (!state.isAir() && !state.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isNaturalTownGround(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.DIRT)
            || state.is(Blocks.SAND)
            || state.is(Blocks.RED_SAND)
            || state.is(Blocks.STONE)
            || state.is(Blocks.SNOW_BLOCK);
    }

    private static boolean placeTownTree(
        ServerLevel level, String biome, BlockPos ground, long seed
    ) {
        if (biome.contains("beach")) {
            return placeBeachPalm(level, ground.above(), seed);
        }
        TreeProfile tree;
        if (biome.contains("badlands")) {
            tree = new TreeProfile("minecraft:acacia_log", "minecraft:acacia_leaves", 1, 5, 8);
        } else if (biome.contains("peak") || biome.contains("mountain")) {
            tree = new TreeProfile("minecraft:spruce_log", "minecraft:spruce_leaves", 1, 6, 10);
        } else if (biome.contains("forest")) {
            tree = (seed & 1L) == 0L
                ? new TreeProfile("minecraft:oak_log", "minecraft:oak_leaves", 1, 5, 9)
                : new TreeProfile("minecraft:birch_log", "minecraft:birch_leaves", 1, 5, 8);
        } else {
            tree = Math.floorMod(seed, 4L) == 0L
                ? new TreeProfile("minecraft:birch_log", "minecraft:birch_leaves", 1, 5, 8)
                : new TreeProfile("minecraft:oak_log", "minecraft:oak_leaves", 1, 5, 9);
        }
        BlockState previousGround = level.getBlockState(ground);
        if (biome.contains("badlands")) {
            level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), 2);
        }
        boolean placed = placeVanillaTree(level, ground.above(), tree, seed);
        if (!placed && biome.contains("badlands")) {
            level.setBlock(ground, previousGround, 2);
        }
        return placed;
    }

    private static boolean placeBeachPalm(
        ServerLevel level, BlockPos base, long seed
    ) {
        int height = 6 + Math.floorMod((int) (seed >>> 21), 3);
        BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, true);
        for (int y = 0; y < height; y++) {
            level.setBlock(base.above(y), Blocks.JUNGLE_LOG.defaultBlockState(), 2);
        }
        BlockPos crown = base.above(height);
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int distance = Math.abs(offsetX) + Math.abs(offsetZ);
                if (distance <= 2 || (distance == 3 && ((offsetX ^ offsetZ) & 1) == 0)) {
                    level.setBlock(crown.offset(offsetX, 0, offsetZ), leaves, 2);
                }
            }
        }
        level.setBlock(crown.above(), leaves, 2);
        return true;
    }

    private static int decorateTownGroundCover(
        ServerLevel level,
        HexWorldPlan world,
        SettlementPlan settlement,
        String biome,
        HexBounds bounds,
        long baseSeed
    ) {
        int decorations = 0;
        for (int gridX = bounds.minX(); gridX <= bounds.maxX(); gridX += 4) {
            for (int gridZ = bounds.minZ(); gridZ <= bounds.maxZ(); gridZ += 4) {
                long seed = baseSeed ^ gridX * 91815541L ^ gridZ * 689287499L;
                int x = gridX + Math.floorMod((int) seed, 5) - 2;
                int z = gridZ + Math.floorMod((int) (seed >>> 29), 5) - 2;
                if (isNearSettlementAnchor(settlement, x, z, 4.0D)
                    || isNearGeneratedTownBuilding(settlement, x, z, 5)) {
                    continue;
                }
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || !sample.kind().equals("town")
                    || !sample.owner().equals(settlement.id())) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                BlockPos ground = new BlockPos(x, groundY, z);
                BlockPos position = ground.above();
                if (!isNaturalTownGround(level.getBlockState(ground))
                    || !level.getBlockState(position).isAir()) {
                    continue;
                }
                BlockState decoration = townGroundDecoration(
                    level, biome, ground, seed
                );
                if (decoration != null) {
                    level.setBlock(position, decoration, 2);
                    decorations++;
                }
            }
        }
        return decorations;
    }

    private static BlockState townGroundDecoration(
        ServerLevel level, String biome, BlockPos ground, long seed
    ) {
        int choice = Math.floorMod((int) (seed ^ seed >>> 32), 10);
        if (biome.contains("badlands")) {
            return level.getBlockState(ground).is(Blocks.RED_SAND)
                ? Blocks.DEAD_BUSH.defaultBlockState() : null;
        }
        if (biome.contains("beach")) {
            for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (level.getBlockState(ground.offset(direction[0], 0, direction[1])).is(Blocks.WATER)) {
                    return Blocks.SUGAR_CANE.defaultBlockState();
                }
            }
            return null;
        }
        if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)) {
            return null;
        }
        if (biome.contains("forest")) {
            return choice < 5 ? Blocks.FERN.defaultBlockState()
                : choice < 8 ? Blocks.SHORT_GRASS.defaultBlockState()
                    : Blocks.LILY_OF_THE_VALLEY.defaultBlockState();
        }
        if (biome.contains("peak") || biome.contains("mountain")) {
            return choice < 6 ? Blocks.FERN.defaultBlockState()
                : Blocks.SHORT_GRASS.defaultBlockState();
        }
        return choice < 5 ? Blocks.SHORT_GRASS.defaultBlockState()
            : choice < 7 ? Blocks.DANDELION.defaultBlockState()
                : choice < 9 ? Blocks.POPPY.defaultBlockState()
                    : Blocks.AZURE_BLUET.defaultBlockState();
    }

    private static BlockState blockState(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    private static void drawHexRoads(ServerLevel level, HexWorldPlan world) {
        for (ConnectionPath connection : world.paths()) {
            List<Point> centerline = connection.centerline();
            if (connection.surfaceStyle().equals("water")) {
                Point firstLanding = drawShoreApproach(
                    level, world, connection, centerline, false
                );
                Point secondLanding = drawShoreApproach(
                    level, world, connection, centerline, true
                );
                LOGGER.info(
                    "Shore access roads completed: route={}, fromLanding={}, toLanding={}",
                    connection.id(), firstLanding, secondLanding
                );
                continue;
            }
            if (!connection.surfaceStyle().equals("road")) {
                continue;
            }
            for (int index = 1; index < centerline.size(); index++) {
                Point start = centerline.get(index - 1);
                Point end = centerline.get(index);
                if (insideConnectionTownCore(world, connection, start)
                    || insideConnectionTownCore(world, connection, end)) {
                    continue;
                }
                drawRoadSegment(
                    level, world, connection, start, end
                );
            }
        }
    }

    private static boolean insideConnectionTownCore(
        HexWorldPlan world, ConnectionPath connection, Point point
    ) {
        return insideSettlementRoadClip(world, connection.from(), point)
            || insideSettlementRoadClip(world, connection.to(), point);
    }

    private static boolean insideSettlementRoadClip(
        HexWorldPlan world, String settlementId, Point point
    ) {
        HexSettlement settlement = world.settlements().get(settlementId);
        if (settlement == null) {
            return false;
        }
        Point center = townFootprintWorldCenter(world.grid(), settlement);
        return Math.hypot(point.x() - center.x(), point.z() - center.z())
            < TOWN_ROUTE_CLIP_RADIUS_BLOCKS;
    }

    private static List<Point> buildRouteCenterline(
        HexGrid grid,
        long seed,
        Map<String, HexSettlement> settlements,
        HexConnection connection,
        List<HexCoord> cells
    ) {
        List<WarpedPoint> controls = new ArrayList<>();
        for (HexCoord cell : cells) {
            Point center = grid.worldCenter(cell);
            WarpedPoint point = new WarpedPoint(center.x(), center.z());
            if (controls.isEmpty() || !controls.getLast().equals(point)) {
                controls.add(point);
            }
        }
        controls = anchorRouteAtTownEdge(
            grid, settlements.get(connection.from()), controls, false
        );
        controls = anchorRouteAtTownEdge(
            grid, settlements.get(connection.to()), controls, true
        );
        for (int pass = 0; pass < 2 && controls.size() > 2; pass++) {
            controls = smoothRouteControls(controls);
        }

        List<Point> base = densifyRouteControls(controls, 12.0D);
        if (base.size() < 3) {
            return List.copyOf(base);
        }
        List<Point> curved = new ArrayList<>(base.size());
        int last = base.size() - 1;
        for (int index = 0; index <= last; index++) {
            Point point = base.get(index);
            Point previous = base.get(Math.max(0, index - 1));
            Point next = base.get(Math.min(last, index + 1));
            double tangentX = next.x() - previous.x();
            double tangentZ = next.z() - previous.z();
            double length = Math.max(1.0D, Math.hypot(tangentX, tangentZ));
            double fadeAtEnds = Math.min(
                1.0D, Math.min(index / 10.0D, (last - index) / 10.0D)
            );
            double broad = layeredNoise(
                seed, connection.id() + ":road-meander:broad",
                point.x(), point.z(), 52.0D
            );
            double detail = layeredNoise(
                seed, connection.id() + ":road-meander:detail",
                point.x(), point.z(), 18.0D
            );
            double offset = (broad * 9.0D + detail * 3.5D) * fadeAtEnds;
            Point displaced = new Point(
                (int) Math.round(point.x() - tangentZ / length * offset),
                (int) Math.round(point.z() + tangentX / length * offset)
            );
            if (curved.isEmpty() || !curved.get(curved.size() - 1).equals(displaced)) {
                curved.add(displaced);
            }
        }
        return List.copyOf(curved);
    }

    private static List<WarpedPoint> anchorRouteAtTownEdge(
        HexGrid grid,
        HexSettlement settlement,
        List<WarpedPoint> controls,
        boolean reverse
    ) {
        if (settlement == null || controls.isEmpty()) {
            return controls;
        }
        List<WarpedPoint> ordered = new ArrayList<>(controls);
        if (reverse) {
            java.util.Collections.reverse(ordered);
        }
        Point centerPoint = townFootprintWorldCenter(grid, settlement);
        WarpedPoint center = new WarpedPoint(centerPoint.x(), centerPoint.z());
        double edgeRadius = TOWN_STRUCTURE_MAX_RADIUS_BLOCKS
            + TOWN_BOUNDARY_CLEARANCE_BLOCKS + 2.0D;
        int firstOutside = -1;
        for (int index = 0; index < ordered.size(); index++) {
            WarpedPoint point = ordered.get(index);
            if (Math.hypot(point.x() - center.x(), point.z() - center.z()) > edgeRadius) {
                firstOutside = index;
                break;
            }
        }
        WarpedPoint target = firstOutside >= 0
            ? ordered.get(firstOutside) : ordered.getLast();
        double dx = target.x() - center.x();
        double dz = target.z() - center.z();
        double length = Math.hypot(dx, dz);
        if (length < 1.0D) {
            return controls;
        }
        WarpedPoint edge = new WarpedPoint(
            center.x() + dx / length * edgeRadius,
            center.z() + dz / length * edgeRadius
        );
        List<WarpedPoint> anchored = new ArrayList<>();
        anchored.add(edge);
        int retainedFrom = firstOutside >= 0 ? firstOutside : ordered.size();
        for (int index = retainedFrom; index < ordered.size(); index++) {
            WarpedPoint point = ordered.get(index);
            if (Math.hypot(point.x() - edge.x(), point.z() - edge.z()) > 1.0D) {
                anchored.add(point);
            }
        }
        if (reverse) {
            java.util.Collections.reverse(anchored);
        }
        return anchored;
    }

    private static List<WarpedPoint> smoothRouteControls(List<WarpedPoint> controls) {
        List<WarpedPoint> smoothed = new ArrayList<>(controls.size() * 2);
        smoothed.add(controls.getFirst());
        for (int index = 1; index < controls.size(); index++) {
            WarpedPoint start = controls.get(index - 1);
            WarpedPoint end = controls.get(index);
            smoothed.add(new WarpedPoint(
                start.x() * 0.75D + end.x() * 0.25D,
                start.z() * 0.75D + end.z() * 0.25D
            ));
            smoothed.add(new WarpedPoint(
                start.x() * 0.25D + end.x() * 0.75D,
                start.z() * 0.25D + end.z() * 0.75D
            ));
        }
        smoothed.add(controls.getLast());
        return smoothed;
    }

    private static List<Point> densifyRouteControls(
        List<WarpedPoint> controls, double spacing
    ) {
        if (controls.isEmpty()) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (int segment = 1; segment < controls.size(); segment++) {
            WarpedPoint start = controls.get(segment - 1);
            WarpedPoint end = controls.get(segment);
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / spacing));
            for (int step = segment == 1 ? 0 : 1; step <= steps; step++) {
                double factor = step / (double) steps;
                Point point = new Point(
                    (int) Math.round(start.x() + dx * factor),
                    (int) Math.round(start.z() + dz * factor)
                );
                if (points.isEmpty() || !points.getLast().equals(point)) {
                    points.add(point);
                }
            }
        }
        if (points.isEmpty()) {
            WarpedPoint point = controls.getFirst();
            points.add(new Point((int) Math.round(point.x()), (int) Math.round(point.z())));
        }
        return List.copyOf(points);
    }

    private static TownGateConfig townGateConfig(
        SettlementPlan settlement, String targetSettlement
    ) {
        for (TownGateConfig gate : settlement.gates()) {
            if (gate.targetSettlement().equals(targetSettlement)) {
                return gate;
            }
        }
        return new TownGateConfig(
            "auto_" + targetSettlement.substring(targetSettlement.lastIndexOf('/') + 1),
            targetSettlement, "toward_target", "east", 0, 9, 3
        );
    }

    private static void connectTownRoadsToRegionalRoutes(
        ServerLevel level, SettlementPlan settlement
    ) {
        HexWorldPlan world = activeHexWorld;
        if (world == null) {
            return;
        }
        Point townCenter = new Point(settlement.center().x(), settlement.center().z());
        int connected = 0;
        Set<Point> usedGateRoads = new HashSet<>();
        for (ConnectionPath connection : world.paths()) {
            boolean fromTown = settlement.id().equals(connection.from());
            boolean toTown = settlement.id().equals(connection.to());
            if ((!fromTown && !toTown) || connection.centerline().isEmpty()) {
                continue;
            }
            String targetSettlement = fromTown ? connection.to() : connection.from();
            if (targetSettlement == null) {
                continue;
            }
            Point approach = fromTown
                ? connection.centerline().getFirst()
                : connection.centerline().getLast();
            TownGateConfig gateConfig = townGateConfig(settlement, targetSettlement);
            Point gateRoad = findTownGateRoad(
                settlement, townCenter, approach, gateConfig, usedGateRoads
            );
            if (gateRoad == null) {
                LOGGER.warn(
                    "Town route could not find an outer village road: settlement={}, route={}",
                    settlement.id(), connection.id()
                );
                continue;
            }
            int[] direction = townGateDirection(townCenter, approach, gateConfig);
            drawConfiguredRoad(
                level, gateRoad, approach, settlement.roadProfile()
            );
            drawTownGate(
                level, world, connection, settlement, gateRoad, direction,
                gateConfig.gateWidth(), gateConfig.pathWidth()
            );
            usedGateRoads.add(gateRoad);
            connected++;
        }
        LOGGER.info(
            "Town roads connected to regional centerlines: settlement={}, routes={}",
            settlement.id(), connected
        );
    }

    private static Point settlementRouteApproach(
        HexWorldPlan world, List<Point> centerline, Point townCenter, boolean reverse
    ) {
        Point lastLand = null;
        for (int offset = 0; offset < centerline.size(); offset++) {
            int index = reverse ? centerline.size() - 1 - offset : offset;
            Point point = centerline.get(index);
            TerrainSample sample = terrainAt(world, point.x() + 0.5D, point.z() + 0.5D);
            if (sample == null || isAquatic(sample)) {
                break;
            }
            lastLand = point;
            if (Math.hypot(point.x() - townCenter.x(), point.z() - townCenter.z()) >= 70.0D) {
                return point;
            }
        }
        return lastLand;
    }

    private static Point findTownGateRoad(
        SettlementPlan settlement,
        Point townCenter,
        Point target,
        TownGateConfig gateConfig,
        Set<Point> usedGateRoads
    ) {
        if (target == null) {
            return null;
        }
        int[] direction = townGateDirection(townCenter, target, gateConfig);
        int perpendicularX = -direction[1];
        int perpendicularZ = direction[0];
        Point best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        TownLayout layout = generateTownLayout(settlement);
        Set<Point> endpoints = new HashSet<>();
        if (!layout.externalExits().isEmpty()) {
            for (Point exit : layout.externalExits()) endpoints.add(townCenter.translate(exit.x(), exit.z()));
        } else {
            for (TownRoad road : layout.roads()) {
                endpoints.add(townCenter.translate(road.x1(), road.z1()));
                endpoints.add(townCenter.translate(road.x2(), road.z2()));
            }
        }
        for (Point endpoint : endpoints) {
            if (usedGateRoads.contains(endpoint)) continue;
            int x = endpoint.x();
            int z = endpoint.z();
            int relativeX = x - townCenter.x();
            int relativeZ = z - townCenter.z();
            double outwardProjection = relativeX * direction[0] + relativeZ * direction[1];
            if (outwardProjection < 8.0D) {
                continue;
            }
            double lateral = relativeX * perpendicularX + relativeZ * perpendicularZ;
            double lateralError = Math.abs(lateral - gateConfig.offset());
            double targetDistance = Math.hypot(x - target.x(), z - target.z());
            // Strongly prefer the outermost road on the requested side. Target and
            // offset only break ties so an inner crossroads cannot become the gate.
            double score = -outwardProjection * 8.0D
                + lateralError * 1.35D
                + targetDistance * 0.04D;
            if (score < bestScore) {
                best = new Point(x, z);
                bestScore = score;
            }
        }
        return best;
    }

    private static int[] townGateDirection(
        Point townCenter, Point target, TownGateConfig gateConfig
    ) {
        if (gateConfig.mode().equals("fixed_side")) {
            return cardinalDirection(gateConfig.preferredSide());
        }
        int deltaX = target.x() - townCenter.x();
        int deltaZ = target.z() - townCenter.z();
        if (Math.abs(deltaX) > Math.abs(deltaZ)) {
            return new int[] {deltaX >= 0 ? 1 : -1, 0};
        }
        if (Math.abs(deltaZ) > 0) {
            return new int[] {0, deltaZ >= 0 ? 1 : -1};
        }
        return cardinalDirection(gateConfig.preferredSide());
    }

    private static int[] cardinalDirection(String side) {
        return switch (side) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "west" -> new int[] {-1, 0};
            default -> new int[] {1, 0};
        };
    }

    private static String cardinalSide(int[] direction) {
        if (direction[0] < 0) return "west";
        if (direction[0] > 0) return "east";
        return direction[1] < 0 ? "north" : "south";
    }

    private static void drawTownGate(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        SettlementPlan settlement,
        Point gate,
        int[] direction,
        int configuredGateWidth,
        int pathWidth
    ) {
        int perpendicularX = -direction[1];
        int perpendicularZ = direction[0];
        int halfOpening = Math.max(2, Math.min(5, configuredGateWidth / 2));
        BlockState pillar = townGateMaterial(world, settlement);
        for (int side : new int[] {-1, 1}) {
            int x = gate.x() + perpendicularX * halfOpening * side;
            int z = gate.z() + perpendicularZ * halfOpening * side;
            TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
            if (sample == null || isAquatic(sample)) {
                continue;
            }
            int groundY = terrainGroundY(world, sample, x, z);
            for (int y = 1; y <= 3; y++) {
                level.setBlock(new BlockPos(x, groundY + y, z), pillar, 2);
            }
            level.setBlock(
                new BlockPos(x, groundY + 4, z), Blocks.LANTERN.defaultBlockState(), 2
            );
        }
        drawRoadDisk(
            level, world, connection,
            gate, Math.max(1.48D, (pathWidth - 1) / 2.0D)
        );
    }

    private static BlockState townGateMaterial(
        HexWorldPlan world, SettlementPlan settlement
    ) {
        HexSettlement hex = world.settlements().get(settlement.id());
        String biome = hex == null ? "" : hex.townBiome();
        if (biome.contains("badlands")) return Blocks.RED_SANDSTONE.defaultBlockState();
        if (biome.contains("beach") || biome.contains("desert")) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (biome.contains("peak") || biome.contains("mountain")) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static Point drawShoreApproach(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        List<Point> centerline,
        boolean reverse
    ) {
        Point landing = null;
        Point previous = null;
        String settlementId = reverse ? connection.to() : connection.from();
        int size = centerline.size();
        for (int offset = 0; offset < size; offset++) {
            int index = reverse ? size - 1 - offset : offset;
            Point point = centerline.get(index);
            TerrainSample sample = terrainAt(world, point.x() + 0.5D, point.z() + 0.5D);
            if (sample == null || isAquatic(sample)) {
                break;
            }
            if (insideSettlementRoadClip(world, settlementId, point)) {
                continue;
            }
            if (previous != null) {
                drawRoadSegment(level, world, connection, previous, point);
            }
            previous = point;
            landing = point;
        }
        if (landing != null) {
            drawRoadDisk(level, world, connection, landing, 4.2D);
        }
        return landing;
    }

    private static void decorateVanillaBiomes(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        List<GenerationStep.Decoration> stages = List.of(
            GenerationStep.Decoration.LOCAL_MODIFICATIONS,
            GenerationStep.Decoration.VEGETAL_DECORATION,
            GenerationStep.Decoration.TOP_LAYER_MODIFICATION
        );
        int placedFeatures = 0;
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                Set<ResourceKey<Biome>> biomes = surroundingBiomesInChunk(world, chunkX, chunkZ);
                if (biomes.isEmpty()) {
                    continue;
                }
                Heightmap.primeHeightmaps(
                    level.getChunk(chunkX, chunkZ),
                    EnumSet.of(
                        Heightmap.Types.WORLD_SURFACE_WG,
                        Heightmap.Types.OCEAN_FLOOR_WG
                    )
                );
                BlockPos origin = new ChunkPos(chunkX, chunkZ).getWorldPosition();
                for (ResourceKey<Biome> biomeKey : biomes) {
                    Holder<Biome> biome = biomeRegistry.getHolderOrThrow(biomeKey);
                    List<net.minecraft.core.HolderSet<PlacedFeature>> features =
                        biome.value().getGenerationSettings().features();
                    for (GenerationStep.Decoration stage : stages) {
                        int step = stage.ordinal();
                        if (step >= features.size()) {
                            continue;
                        }
                        int featureIndex = 0;
                        for (Holder<PlacedFeature> feature : features.get(step)) {
                            RandomSource random = RandomSource.create(vanillaDecorationSeed(
                                world.seed(), chunkX, chunkZ, biomeKey.location(), step, featureIndex
                            ));
                            if (feature.value().placeWithBiomeCheck(level, generator, random, origin)) {
                                placedFeatures++;
                            }
                            featureIndex++;
                        }
                    }
                }
            }
        }
        LOGGER.info("Vanilla biome decoration completed: placedFeatureRuns={}", placedFeatures);
        if (Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY) && placedFeatures == 0) {
            throw new IllegalStateException("Vanilla biome decoration did not place any features");
        }
    }

    private static void decorateOpenBiomeGroundCover(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        int decorations = 0;
        int clusters = 0;
        for (int gridX = bounds.minX(); gridX <= bounds.maxX(); gridX += 5) {
            for (int gridZ = bounds.minZ(); gridZ <= bounds.maxZ(); gridZ += 5) {
                long seed = world.seed()
                    ^ (long) gridX * 341873128712L
                    ^ (long) gridZ * 132897987541L
                    ^ 0x4F50454E5F47524FL;
                if (Math.floorMod((int) (seed >>> 25), 10) >= 8) {
                    continue;
                }
                int clusterSize = 1 + Math.floorMod((int) (seed >>> 41), 3);
                int placedInCluster = 0;
                for (int index = 0; index < clusterSize; index++) {
                    long memberSeed = seed ^ index * 0x9E3779B97F4A7C15L;
                    int x = gridX + Math.floorMod((int) memberSeed, 5) - 2;
                    int z = gridZ + Math.floorMod((int) (memberSeed >>> 32), 5) - 2;
                    TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                    if (sample == null || !sample.kind().equals("surrounding")
                        || isAquatic(sample) || sample.surfaceStyle().equals("road")) {
                        continue;
                    }
                    int groundY = terrainGroundY(world, sample, x, z);
                    BlockPos ground = new BlockPos(x, groundY, z);
                    BlockPos position = ground.above();
                    BlockState decoration = openBiomeGroundDecoration(
                        level, sample.biome(), ground, memberSeed
                    );
                    if (decoration == null || !level.getBlockState(position).isAir()
                        || !decoration.canSurvive(level, position)) {
                        continue;
                    }
                    level.setBlock(position, decoration, 2);
                    decorations++;
                    placedInCluster++;
                }
                if (placedInCluster > 0) {
                    clusters++;
                }
            }
        }
        LOGGER.info(
            "Open biome ground cover completed: clusters={}, decorations={}",
            clusters, decorations
        );
    }

    private static void decorateOpenBiomeTrees(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        int trees = 0;
        int attempted = 0;
        // Vanilla placed features are still run above, but their terrain lookup is
        // not guaranteed to resolve against columns painted after chunk generation.
        // This deterministic pass guarantees that playable surrounding biomes do
        // not become empty lawns when vanilla tree features decline placement.
        for (int gridX = bounds.minX(); gridX <= bounds.maxX(); gridX += 18) {
            for (int gridZ = bounds.minZ(); gridZ <= bounds.maxZ(); gridZ += 18) {
                long seed = world.seed()
                    ^ (long) gridX * 341873128712L
                    ^ (long) gridZ * 132897987541L
                    ^ 0x4F50454E5F545245L;
                int x = gridX + Math.floorMod((int) (seed >>> 17), 13) - 6;
                int z = gridZ + Math.floorMod((int) (seed >>> 37), 13) - 6;
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || !sample.kind().equals("surrounding")
                    || isAquatic(sample) || sample.surfaceStyle().equals("road")) {
                    continue;
                }
                int chance = openBiomeTreeChance(sample.biome());
                if (Math.floorMod((int) (seed ^ seed >>> 32), 100) >= chance) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                if (!isOpenSurroundingTreeSite(
                    level, world, sample, x, groundY, z
                )) {
                    continue;
                }
                attempted++;
                if (placeTownTree(
                    level, sample.biome(), new BlockPos(x, groundY, z), seed
                )) {
                    trees++;
                }
            }
        }
        LOGGER.info(
            "Open biome trees completed: trees={}, attempted={}", trees, attempted
        );
        if (Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY) && attempted > 0 && trees == 0) {
            throw new IllegalStateException("Open biome tree pass did not place any trees");
        }
    }

    private static int openBiomeTreeChance(String biome) {
        if (biome.contains("forest")) {
            return 86;
        }
        if (biome.contains("beach")) {
            return 34;
        }
        if (biome.contains("badlands") || biome.contains("desert")) {
            return 12;
        }
        if (biome.contains("peak") || biome.contains("mountain")
            || biome.contains("windswept")) {
            return 30;
        }
        return 22;
    }

    private static boolean isOpenSurroundingTreeSite(
        ServerLevel level,
        HexWorldPlan world,
        TerrainSample sample,
        int x,
        int groundY,
        int z
    ) {
        BlockPos ground = new BlockPos(x, groundY, z);
        if (!isNaturalTownGround(level.getBlockState(ground))) {
            return false;
        }
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                if (offsetX * offsetX + offsetZ * offsetZ > 4) {
                    continue;
                }
                int columnX = x + offsetX;
                int columnZ = z + offsetZ;
                TerrainSample neighbor = terrainAt(
                    world, columnX + 0.5D, columnZ + 0.5D
                );
                if (neighbor == null || !neighbor.kind().equals("surrounding")
                    || !neighbor.owner().equals(sample.owner()) || isAquatic(neighbor)) {
                    return false;
                }
                int neighborGroundY = terrainGroundY(
                    world, neighbor, columnX, columnZ
                );
                if (Math.abs(neighborGroundY - groundY) > 2) {
                    return false;
                }
                for (int y = neighborGroundY + 1; y <= neighborGroundY + 10; y++) {
                    BlockState state = level.getBlockState(
                        new BlockPos(columnX, y, columnZ)
                    );
                    if (!state.isAir() && !state.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static BlockState openBiomeGroundDecoration(
        ServerLevel level, String biome, BlockPos ground, long seed
    ) {
        BlockState groundState = level.getBlockState(ground);
        int choice = Math.floorMod((int) (seed ^ seed >>> 32), 16);
        if (biome.contains("badlands") || biome.contains("desert")) {
            return groundState.is(Blocks.RED_SAND) || groundState.is(Blocks.SAND)
                ? Blocks.DEAD_BUSH.defaultBlockState() : null;
        }
        if (biome.contains("snow") || biome.contains("ice") || biome.contains("beach")) {
            return null;
        }
        if (!groundState.is(Blocks.GRASS_BLOCK)) {
            return null;
        }
        if (biome.contains("forest")) {
            return choice < 8 ? Blocks.FERN.defaultBlockState()
                : choice < 13 ? Blocks.SHORT_GRASS.defaultBlockState()
                    : choice < 15 ? Blocks.LILY_OF_THE_VALLEY.defaultBlockState()
                        : Blocks.BROWN_MUSHROOM.defaultBlockState();
        }
        return choice < 10 ? Blocks.SHORT_GRASS.defaultBlockState()
            : choice < 12 ? Blocks.DANDELION.defaultBlockState()
                : choice < 14 ? Blocks.POPPY.defaultBlockState()
                    : choice == 14 ? Blocks.AZURE_BLUET.defaultBlockState()
                        : Blocks.CORNFLOWER.defaultBlockState();
    }

    private static Set<ResourceKey<Biome>> surroundingBiomesInChunk(
        HexWorldPlan world, int chunkX, int chunkZ
    ) {
        Set<ResourceKey<Biome>> biomes = new HashSet<>();
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        for (int offsetX = 2; offsetX < 16; offsetX += 4) {
            for (int offsetZ = 2; offsetZ < 16; offsetZ += 4) {
                TerrainSample sample = terrainAt(world, startX + offsetX, startZ + offsetZ);
                if (sample != null && sample.kind().equals("surrounding")) {
                    biomes.add(ResourceKey.create(
                        Registries.BIOME, ResourceLocation.parse(sample.biome())
                    ));
                }
            }
        }
        return biomes;
    }

    private static long vanillaDecorationSeed(
        long seed,
        int chunkX,
        int chunkZ,
        ResourceLocation biome,
        int step,
        int featureIndex
    ) {
        long mixed = seed;
        mixed ^= chunkX * 341873128712L;
        mixed ^= chunkZ * 132897987541L;
        mixed ^= (long) biome.hashCode() * 31L;
        mixed ^= (long) step << 32;
        mixed ^= featureIndex * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        return mixed ^ mixed >>> 33;
    }

    private static void clearReservedTerrain(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || (!sample.kind().equals("town")
                    && !(sample.kind().equals("route") && sample.surfaceStyle().equals("road")))) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                for (int y = groundY + 1; y <= 96; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void drawRoadSegment(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        Point start,
        Point end
    ) {
        drawRoadSegment(level, world, connection, start, end, 1.48D);
    }

    private static void drawRoadSegment(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        Point start,
        Point end,
        double radius
    ) {
        int dx = end.x() - start.x();
        int dz = end.z() - start.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.x() + dx * factor);
            int z = (int) Math.round(start.z() + dz * factor);
            drawRoadDisk(level, world, connection, new Point(x, z), radius);
        }
    }

    private static void drawRoadDisk(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        Point center,
        double radius
    ) {
        int range = (int) Math.ceil(radius + 1.0D);
        for (int offsetX = -range; offsetX <= range; offsetX++) {
            for (int offsetZ = -range; offsetZ <= range; offsetZ++) {
                double edgeNoise = layeredNoise(
                    world.seed(), connection.id() + ":road-edge",
                    center.x() + offsetX, center.z() + offsetZ, 15.0D
                ) * 0.04D;
                if (Math.hypot(offsetX, offsetZ) > radius + edgeNoise) {
                    continue;
                }
                int x = center.x() + offsetX;
                int z = center.z() + offsetZ;
                TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                if (sample == null || isAquatic(sample)) {
                    continue;
                }
                int groundY = terrainGroundY(world, sample, x, z);
                clearVegetationColumn(level, x, groundY, z, 32);
                level.setBlock(
                    new BlockPos(x, groundY, z),
                    roadSurfaceBlock(world, sample, x, z),
                    2
                );
                for (int y = groundY + 1; y <= groundY + 4; y++) {
                    level.setBlock(
                        new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2
                    );
                }
            }
        }
    }

    private static BlockState roadSurfaceBlock(
        HexWorldPlan world, TerrainSample sample, int x, int z
    ) {
        long pattern = Double.doubleToLongBits(layeredNoise(
            world.seed(), "world:road-material", x, z, 9.0D
        ));
        int choice = Math.floorMod((int) (pattern ^ pattern >>> 32), 20);
        // Match the palette embedded in BCA's default village path pieces so
        // the three-block regional road reads as a continuation of the town.
        return choice < 7
            ? Blocks.STONE_BRICKS.defaultBlockState()
            : choice < 12
                ? Blocks.COBBLESTONE.defaultBlockState()
                : choice < 15
                    ? Blocks.ANDESITE.defaultBlockState()
                    : choice < 18
                        ? Blocks.STONE.defaultBlockState()
                        : Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
    }

    private static BlockPos createWaitingArea(ServerLevel level) {
        BlockPos center = new BlockPos(WAITING_AREA_X, WAITING_AREA_Y, WAITING_AREA_Z);
        level.setChunkForced(center.getX() >> 4, center.getZ() >> 4, true);
        level.getChunk(center.getX() >> 4, center.getZ() >> 4);
        for (int offsetX = -WAITING_AREA_RADIUS; offsetX <= WAITING_AREA_RADIUS; offsetX++) {
            for (int offsetZ = -WAITING_AREA_RADIUS; offsetZ <= WAITING_AREA_RADIUS; offsetZ++) {
                boolean edge = Math.abs(offsetX) == WAITING_AREA_RADIUS
                    || Math.abs(offsetZ) == WAITING_AREA_RADIUS;
                int x = center.getX() + offsetX;
                int z = center.getZ() + offsetZ;
                level.setBlock(new BlockPos(x, WAITING_AREA_Y - 2, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, WAITING_AREA_Y - 1, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(
                    new BlockPos(x, WAITING_AREA_Y, z),
                    edge ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(),
                    2
                );
                for (int y = WAITING_AREA_Y + 1; y <= WAITING_AREA_Y + 4; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                if (edge) {
                    level.setBlock(
                        new BlockPos(x, WAITING_AREA_Y + 1, z),
                        Blocks.OAK_FENCE.defaultBlockState(),
                        2
                    );
                }
            }
        }
        for (int offsetX : new int[] {-WAITING_AREA_RADIUS + 1, WAITING_AREA_RADIUS - 1}) {
            for (int offsetZ : new int[] {-WAITING_AREA_RADIUS + 1, WAITING_AREA_RADIUS - 1}) {
                level.setBlock(
                    center.offset(offsetX, 0, offsetZ),
                    Blocks.GLOWSTONE.defaultBlockState(),
                    2
                );
            }
        }
        LOGGER.info("Temporary generation waiting area prepared at {}", center);
        return center;
    }

    private static void movePlayerToWaitingArea(
        ServerPlayer player, ServerLevel level, BlockPos center
    ) {
        boolean firstArrival = !player.getPersistentData().getBoolean(PLAYER_WAITING);
        // Set this before teleporting: entering generation_1 emits another entity-join
        // event on some server paths, and the marker prevents duplicate user feedback.
        player.getPersistentData().putBoolean(PLAYER_WAITING, true);
        player.teleportTo(
            level,
            center.getX() + 0.5D,
            center.getY() + 1.0D,
            center.getZ() + 0.5D,
            0.0F,
            0.0F
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        if (firstArrival) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 지도가 완성될 때까지 임시 대기 장소에서 기다려 주세요."
            ));
        }
    }

    private static void removeWaitingArea(ServerLevel level, BlockPos center) {
        for (int offsetX = -WAITING_AREA_RADIUS; offsetX <= WAITING_AREA_RADIUS; offsetX++) {
            for (int offsetZ = -WAITING_AREA_RADIUS; offsetZ <= WAITING_AREA_RADIUS; offsetZ++) {
                for (int y = WAITING_AREA_Y - 2; y <= WAITING_AREA_Y + 1; y++) {
                    level.setBlock(
                        new BlockPos(center.getX() + offsetX, y, center.getZ() + offsetZ),
                        Blocks.AIR.defaultBlockState(),
                        2
                    );
                }
            }
        }
        level.setChunkForced(center.getX() >> 4, center.getZ() >> 4, false);
        LOGGER.info("Temporary generation waiting area removed at {}", center);
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
        player.getPersistentData().remove(PLAYER_WAITING);
    }

    private static void moveWaitingPlayersToStart(ServerLevel level, BlockPos spawnPos) {
        for (ServerPlayer player : level.players()) {
            if (player.getPersistentData().getBoolean(PLAYER_WAITING)) {
                movePlayerToStart(player, level, spawnPos);
            }
        }
    }

    private static final class GenerationProgress {
        private final ServerPlayer player;
        private int lastPercent = -1;
        private String lastDetail = "";
        private int lastMilestone = 0;

        private GenerationProgress(ServerPlayer player) {
            this.player = player;
        }

        private void update(int percent, String detail) {
            int boundedPercent = Math.max(0, Math.min(100, percent));
            if (boundedPercent == lastPercent && detail.equals(lastDetail)) {
                return;
            }
            lastPercent = boundedPercent;
            lastDetail = detail;
            LOGGER.info("World generation progress: {}% - {}", boundedPercent, detail);
            if (player == null) {
                return;
            }
            Component message = Component.literal(
                "[Cobbleventure] 지도 생성 " + boundedPercent + "% - " + detail
            );
            player.displayClientMessage(message, true);
            int milestone = boundedPercent / 25;
            if (milestone > lastMilestone) {
                lastMilestone = milestone;
                player.sendSystemMessage(message);
            }
        }
    }

    private static final class WorldInitializationJob {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final BootstrapSavedData data;
        private final BlockPos waitingArea;
        private final RuntimeWorld runtime;
        private final BlockPos spawnPos;
        private final BlockPos villagePos;
        private final List<SettlementPlan> settlements;
        private final GenerationProgress progress;
        private final boolean initialGeneration;
        private final List<ChunkPos> townChunks = new ArrayList<>();
        private int index;
        private int phase = -1;
        private int nextTownChunk;
        private int lastReportedReadyChunks = -1;
        private long chunkPreparationStartedAt;

        private WorldInitializationJob(
            ServerLevel level,
            ServerPlayer player,
            BootstrapSavedData data,
            BlockPos waitingArea,
            RuntimeWorld runtime,
            BlockPos spawnPos,
            BlockPos villagePos,
            List<SettlementPlan> settlements,
            GenerationProgress progress,
            boolean initialGeneration
        ) {
            this.level = level;
            this.player = player;
            this.data = data;
            this.waitingArea = waitingArea;
            this.runtime = runtime;
            this.spawnPos = spawnPos;
            this.villagePos = villagePos;
            this.settlements = settlements;
            this.progress = progress;
            this.initialGeneration = initialGeneration;
        }
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

    record PlayableEdge(int distance, int groundY, boolean aquatic) {}

    record TownConnector(int x, int z, int direction, int depth) {}

    record TownRoad(int x1, int z1, int x2, int z2) {}

    record TownSlot(int roadIndex, double ratio, int side) {}

    record TownTemplatePlacement(String structure, BlockPoint position, String rotation) {}

    record FacilityWorkerPlacement(String structure, BlockPoint offset) {}

    record TownPlot(
        double x,
        double z,
        int width,
        int depth,
        String id,
        String structure,
        String rotation,
        int roadConnectionX,
        int roadConnectionZ
    ) {
        TownPlot(double x, double z, int width, int depth, String id) {
            this(x, z, width, depth, id, null, "none", 0, 0);
        }
    }

    record TownLayout(
        List<TownRoad> roads,
        List<TownRoad> accessRoads,
        Map<String, TownPlot> facilities,
        List<TownPlot> houses,
        List<Point> externalExits
    ) {}

    private static final class PreviewRandom {
        private int value;

        private PreviewRandom(int seed) {
            value = seed;
        }

        private double nextDouble() {
            value += 0x6d2b79f5;
            int result = value;
            result = (result ^ result >>> 15) * (result | 1);
            result ^= result + (result ^ result >>> 7) * (result | 61);
            int output = result ^ result >>> 14;
            return Integer.toUnsignedLong(output) / 4294967296.0D;
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

    record HexBounds(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(Point point) {
            return point.x() >= minX && point.x() <= maxX
                && point.z() >= minZ && point.z() <= maxZ;
        }
    }

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
        String townFootprintShape,
        List<HexCoord> customFootprint,
        String townBiome,
        List<SurroundingRegion> surroundings,
        String boundaryProfile,
        TerrainProfile terrainProfile,
        String accessRequirement
    ) {}

    record PlacedTile(
        HexCoord coordinate,
        String biome,
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
        String accessRequirement,
        List<HexCoord> cells
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
        String from,
        String to,
        String biome,
        String boundaryProfile,
        double corridorWidthBlocks,
        double edgeNoise,
        TerrainProfile terrainProfile,
        String surfaceStyle,
        String accessRequirement,
        List<HexCoord> cells,
        List<Point> centerline
    ) {}

    record TerrainProfile(int baseHeightOffset, int heightVariation, double noiseScaleBlocks) {}

    record NoiseKey(long seed, String salt) {}

    record WarpedPoint(double x, double z) {}

    record TerrainSample(
        String biome,
        String boundaryProfile,
        String kind,
        String owner,
        TerrainProfile terrainProfile,
        String accessRequirement,
        String surfaceStyle
    ) {}

    record NativeTerrainColumn(
        int groundY,
        int waterTopY,
        BlockState surface,
        BlockState filler,
        String biome,
        boolean blocked,
        boolean rocky
    ) {}

    record TerrainSamplePoint(Point point, TerrainSample sample) {}

    static final class ShoreDistanceField {
        private final int minX;
        private final int minZ;
        private final int width;
        private final int height;
        private final int maximumDistance;
        private final boolean[] playable;
        private final byte[] toAquatic;
        private final byte[] toLand;

        private ShoreDistanceField(
            int minX,
            int minZ,
            int width,
            int height,
            int maximumDistance,
            boolean[] playable,
            byte[] toAquatic,
            byte[] toLand
        ) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.maximumDistance = maximumDistance;
            this.playable = playable;
            this.toAquatic = toAquatic;
            this.toLand = toLand;
        }

        static ShoreDistanceField build(
            HexWorldPlan world, HexBounds bounds, int maximumDistance
        ) {
            int width = bounds.maxX() - bounds.minX() + 1;
            int height = bounds.maxZ() - bounds.minZ() + 1;
            int size = Math.multiplyExact(width, height);
            int unreachable = maximumDistance + 1;
            boolean[] playable = new boolean[size];
            byte[] toAquatic = new byte[size];
            byte[] toLand = new byte[size];
            Arrays.fill(toAquatic, (byte) unreachable);
            Arrays.fill(toLand, (byte) unreachable);
            LongAdder aquaticColumns = new LongAdder();
            LongAdder landColumns = new LongAdder();
            IntStream.range(0, height).parallel().forEach(localZ -> {
                int z = bounds.minZ() + localZ;
                for (int localX = 0; localX < width; localX++) {
                    int x = bounds.minX() + localX;
                    int index = localZ * width + localX;
                    TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                    if (sample == null) {
                        continue;
                    }
                    playable[index] = true;
                    if (isAquatic(sample)) {
                        toAquatic[index] = 0;
                        aquaticColumns.increment();
                    } else {
                        toLand[index] = 0;
                        landColumns.increment();
                    }
                }
            });
            propagate(playable, toAquatic, width, height, unreachable);
            propagate(playable, toLand, width, height, unreachable);
            LOGGER.info(
                "Shore distance field completed: size={}x{}, landColumns={}, aquaticColumns={}, radius={}",
                width, height, landColumns.sum(), aquaticColumns.sum(), maximumDistance
            );
            return new ShoreDistanceField(
                bounds.minX(), bounds.minZ(), width, height, maximumDistance,
                playable, toAquatic, toLand
            );
        }

        private static void propagate(
            boolean[] playable,
            byte[] distances,
            int width,
            int height,
            int unreachable
        ) {
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    relax(playable, distances, width, height, x, z, -1, 0, unreachable);
                    relax(playable, distances, width, height, x, z, 0, -1, unreachable);
                    relax(playable, distances, width, height, x, z, -1, -1, unreachable);
                    relax(playable, distances, width, height, x, z, 1, -1, unreachable);
                }
            }
            for (int z = height - 1; z >= 0; z--) {
                for (int x = width - 1; x >= 0; x--) {
                    relax(playable, distances, width, height, x, z, 1, 0, unreachable);
                    relax(playable, distances, width, height, x, z, 0, 1, unreachable);
                    relax(playable, distances, width, height, x, z, 1, 1, unreachable);
                    relax(playable, distances, width, height, x, z, -1, 1, unreachable);
                }
            }
        }

        private static void relax(
            boolean[] playable,
            byte[] distances,
            int width,
            int height,
            int x,
            int z,
            int offsetX,
            int offsetZ,
            int unreachable
        ) {
            int index = z * width + x;
            if (!playable[index]) {
                return;
            }
            int neighborX = x + offsetX;
            int neighborZ = z + offsetZ;
            if (neighborX < 0 || neighborX >= width || neighborZ < 0 || neighborZ >= height) {
                return;
            }
            int neighborIndex = neighborZ * width + neighborX;
            if (!playable[neighborIndex]) {
                return;
            }
            int current = Byte.toUnsignedInt(distances[index]);
            int neighbor = Byte.toUnsignedInt(distances[neighborIndex]);
            distances[index] = (byte) Math.min(current, Math.min(unreachable, neighbor + 1));
        }

        int distance(int x, int z, boolean aquatic) {
            int localX = x - minX;
            int localZ = z - minZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= height) {
                return -1;
            }
            int index = localZ * width + localX;
            if (!playable[index]) {
                return -1;
            }
            int value = Byte.toUnsignedInt((aquatic ? toAquatic : toLand)[index]);
            return Math.min(maximumDistance + 1, value);
        }
    }

    record CollisionPlacement(int placed, int preserved) {}

    record GymLot(
        BlockPoint origin,
        Point road,
        int roadSides,
        int roadDistance,
        int obstructions,
        int score
    ) {}

    record GymEntranceGeometry(Point doorway, int outwardX, int outwardZ) {}

    record GymLotAssessment(int groundY, int obstructions) {}

    record OuterTerrainStats(
        int maximumSlope,
        int maximumPlayableSeam,
        int minimumY,
        int maximumY
    ) {}

    record BoundaryEdge(
        int x,
        int z,
        int edgeGroundY,
        int outwardX,
        int outwardZ
    ) {}

    record CliffFace(int inwardX, int inwardZ, int distance) {}

    static final class HeightAccumulator {
        private long total;
        private int samples;

        void add(int height) {
            total += height;
            samples++;
        }

        int average() {
            return samples == 0 ? 0 : (int) Math.round(total / (double) samples);
        }
    }

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
        Map<String, BoundaryProfile> boundaryProfiles,
        String defaultEmptyTerrain,
        Map<HexCoord, String> emptyTerrainTiles
    ) {}

    record PathNode(HexCoord cell, int cost, int score) {}

    record RuntimeWorld(
        Map<String, SettlementPlan> settlements,
        HexWorldPlan hexWorld
    ) {}

    static final class TerrainWriteStats {
        private long columns;
        private long attempted;
        private long changed;
        private long skipped;
    }

    static final class GenerationProfiler {
        private final long startedNanos = System.nanoTime();
        private long phaseStartedNanos = startedNanos;

        void finishPhase(String phase) {
            long finishedNanos = System.nanoTime();
            LOGGER.info(
                "World generation phase completed: phase={}, elapsedMs={}, totalMs={}",
                phase,
                (finishedNanos - phaseStartedNanos) / 1_000_000L,
                (finishedNanos - startedNanos) / 1_000_000L
            );
            phaseStartedNanos = finishedNanos;
        }
    }

    record FacilityPlacement(
        String id,
        String mode,
        String structure,
        String facilityType,
        String label,
        String anchor,
        String entryAnchor,
        String returnAnchor,
        BlockPoint instanceOrigin,
        BlockPoint instanceEntryOffset,
        BlockPoint instanceExitOffset,
        double triggerRadius,
        int footprintWidth,
        int footprintDepth,
        int footprintHeight,
        int clearance
    ) {}

    record FacilityPortal(
        BlockPoint entry,
        BlockPoint returnPoint,
        BlockPoint instanceEntry,
        BlockPoint instanceExit,
        double radiusSquared
    ) {}

    record FacilitySite(FacilityPlacement facility, BlockPoint origin) {}

    record RoadProfile(int width, String material) {}

    record SettlementPlan(
        String id,
        boolean enabled,
        int townRadiusCells,
        String structure,
        String houseStyle,
        boolean disableCommercialOneOff,
        String layoutShape,
        RoadProfile roadProfile,
        int generationSeed,
        int generationDepth,
        String buildingDensity,
        List<String> basicBuildings,
        BlockPoint center,
        BlockPoint structurePoint,
        BlockPoint playerSpawn,
        Map<String, BlockPoint> anchors,
        List<FacilityPlacement> facilities,
        List<TownGateConfig> gates,
        TownLayout compiledLayout
    ) {}

    record TownGateConfig(
        String id,
        String targetSettlement,
        String mode,
        String preferredSide,
        int offset,
        int gateWidth,
        int pathWidth
    ) {}

    static final class BootstrapSavedData extends SavedData {
        private boolean complete;
        private int mapVersion;
        private BlockPos spawnPos = BlockPos.ZERO;
        private BlockPos villagePos = BlockPos.ZERO;
        private final Set<String> generatedSettlements = new HashSet<>();

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
            String generated = tag.getString("generatedSettlements");
            if (!generated.isBlank()) {
                data.generatedSettlements.addAll(Arrays.asList(generated.split(",")));
            }
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

        boolean isSettlementGenerated(String settlementId) {
            return generatedSettlements.contains(settlementId);
        }

        void markSettlementGenerated(String settlementId) {
            if (generatedSettlements.add(settlementId)) {
                setDirty();
            }
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
            tag.putString("generatedSettlements", String.join(",", generatedSettlements));
            return tag;
        }
    }
}
