package dev.buizz.cobbleventure.bootstrap;

import static dev.buizz.cobbleventure.bootstrap.WorldPlanModels.*;

import dev.buizz.cobbleventure.adventure.AdventureWorldContext;
import dev.buizz.cobbleventure.adventure.CobbleventureAdventure;
import dev.buizz.cobbleventure.adventure.FieldMoveRidingAccess;
import dev.buizz.cobbleventure.adventure.PokemonCenterDefeatReturn;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventMovementFailureReason;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import dev.buizz.cobbleventure.adventure.event.EventBoundaryProviderRegistry;
import dev.buizz.cobbleventure.playermenu.LocationAnnouncement;
import dev.buizz.cobbleventure.playermenu.MusicPlayback;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(CobbleventureBootstrap.MOD_ID)
public final class CobbleventureBootstrap {
    public static final String MOD_ID = "cobbleventure_bootstrap";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation NPC_WORLD_FONT =
        ResourceLocation.withDefaultNamespace("uniform");
    private static final ResourceLocation COBBLE_MERCHANT =
        ResourceLocation.fromNamespaceAndPath("cobbledollars", "cobble_merchant");
    private static final int INITIAL_SPAWN_DIAGNOSTIC_EVENTS = 20;
    private static int blockedPursuitZonePokemon;
    private static int blockedOutsideTerrainPokemon;
    private static final String DATA_FILE = "cobbleventure_world_bootstrap";
    private static final Set<EntityType<?>> BLOCKED_VANILLA_MOBS = Set.of(
        EntityType.AXOLOTL,
        EntityType.BAT,
        EntityType.BEE,
        EntityType.COD,
        EntityType.DOLPHIN,
        EntityType.DROWNED,
        EntityType.ELDER_GUARDIAN,
        EntityType.GLOW_SQUID,
        EntityType.GUARDIAN,
        EntityType.PUFFERFISH,
        EntityType.SALMON,
        EntityType.SQUID,
        EntityType.TADPOLE,
        EntityType.TROPICAL_FISH,
        EntityType.TURTLE
    );
    private static final Set<MobCategory> BLOCKED_VANILLA_MOB_CATEGORIES = Set.of(
        MobCategory.CREATURE,
        MobCategory.AMBIENT,
        MobCategory.AXOLOTLS,
        MobCategory.UNDERGROUND_WATER_CREATURE,
        MobCategory.WATER_CREATURE,
        MobCategory.WATER_AMBIENT
    );
    private static final int BCA_REFERENCE_SURFACE_Y = 68;
    private static final int LEGACY_SURFACE_Y = 69;
    private static final int SEALED_OUTER_SURFACE_Y = 92;
    private static final int SEALED_OUTER_MIN_Y = 88;
    static final int WATER_SURFACE_Y = 64;
    private static final int NORMAL_TERRAIN_MIN_Y = 66;
    private static final int SHORE_LAND_TARGET_Y = WATER_SURFACE_Y;
    private static final int SHORE_BLEND_WIDTH = 24;
    private static final int SHORE_SAND_HEIGHT_BLOCKS = 3;
    private static final int SHORE_SAND_WIDTH_BLOCKS = 6;
    private static final int OUTER_TERRAIN_TRANSITION_WIDTH = 32;
    private static final int OUTER_TERRAIN_DISTANCE_SAMPLE_SPACING = 4;
    private static final double[][] OUTER_TERRAIN_SAMPLE_DIRECTIONS = {
        {1.0D, 0.0D}, {0.9239D, 0.3827D}, {0.7071D, 0.7071D},
        {0.3827D, 0.9239D}, {0.0D, 1.0D}, {-0.3827D, 0.9239D},
        {-0.7071D, 0.7071D}, {-0.9239D, 0.3827D}, {-1.0D, 0.0D},
        {-0.9239D, -0.3827D}, {-0.7071D, -0.7071D},
        {-0.3827D, -0.9239D}, {0.0D, -1.0D}, {0.3827D, -0.9239D},
        {0.7071D, -0.7071D}, {0.9239D, -0.3827D}
    };
    private static final int OCEAN_ROCK_MOUND_SPACING = 9;
    private static final int OCEAN_ROCK_MOUND_CENTER_MARGIN = 2;
    private static final int OCEAN_ROCK_BOUNDARY_BAND = 10;
    private static final int OCEAN_ROCK_CENTER_EDGE_RANGE = 5;
    private static final int MAX_TOWN_PREPARATION_CHUNKS = 320;
    // Regional roads stop at the outer edge of a town (currently about 134 blocks
    // from its center). Start lazy generation well before that endpoint can enter
    // normal view distance, so the town road connectors exist when players arrive.
    private static final int LAZY_TOWN_TRIGGER_DISTANCE = 224;
    private static final int STARTER_TOWN_CHUNKS_PER_TICK = 8;
    private static final int BACKGROUND_TOWN_CHUNKS_PER_TICK = 1;
    private static final TicketType<ChunkPos> TOWN_GENERATION_TICKET = TicketType.create(
        "cobbleventure_town_generation", Comparator.comparingLong(ChunkPos::toLong)
    );
    private static final TicketType<ChunkPos> STRUCTURE_GENERATION_TICKET = TicketType.create(
        "cobbleventure_structure_generation", Comparator.comparingLong(ChunkPos::toLong)
    );
    private static final int OCEAN_CLIFF_MAX_Y = WATER_SURFACE_Y + 4;
    private static final int GYM_LOT_CLEARANCE = 6;
    private static final int GYM_LOT_SEARCH_RADIUS = 86;
    private static final int GYM_ROAD_SEARCH_RADIUS = 42;
    private static final int REGIONAL_NPC_ENTRANCE_CLEARANCE = 12;
    private static final int TOWN_NPC_ENTRANCE_CLEARANCE = 10;
    private static final ResourceLocation BCA_BERRY_TARGET =
        ResourceLocation.fromNamespaceAndPath("bca", "berry_plant");
    private static final int BCA_BERRY_VARIANT_LIMIT = 73;
    // Every RGS Kanto gym uses the same 25x13x26 shell. Rotate its centered north
    // entrance to the south so the public doorway faces downward in the town layout.
    private static final BlockPoint RGS_GYM_ENTRANCE_OFFSET = new BlockPoint(12, 3, 3);
    private static final String RGS_GYM_ROTATION = "clockwise_180";
    private static final int LEGACY_VISIBLE_BOUNDARY_CLEANUP_RADIUS = 8;
    private static final int DEEP_FOUNDATION_MIN_Y = 0;
    private static final int DEEP_FOUNDATION_MAX_Y = 9;
    private static final int FLAT_GENERATOR_SURFACE_Y = 67;
    private static final int PREVIOUS_FOUNDATION_MIN_Y = 50;
    private static final int PREVIOUS_FOUNDATION_MAX_Y = 59;
    private static final int LEGACY_FOUNDATION_MIN_Y = 55;
    private static final int LEGACY_FOUNDATION_MAX_Y = 64;
    private static final int MAP_VERSION = 98;
    private static final double TOWN_RELIEF_SCALE = 0.22D;
    private static final int TOWN_EDGE_RELIEF_BLEND_BLOCKS = 40;
    private static final double TOWN_STRUCTURE_MAX_RADIUS_BLOCKS = 116.0D;
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
    private static final String TOWN_SEQUENCE_PERFORMANCE_TEST_PROPERTY =
        "cobbleventure.townSequencePerformanceTest";
    private static final String TEST_RENDER_RADIUS_PROPERTY = "cobbleventure.testRenderRadius";
    private static final String PLAYER_STARTED = "cobbleventureGenerationOneStarted";
    private static final String PLAYER_WAITING = "cobbleventureGenerationWaiting";
    private static final String FACILITY_PORTAL_COOLDOWN = "cobbleventureFacilityPortalCooldown";
    private static final String FIELD_MOVE_MESSAGE_COOLDOWN = "cobbleventureFieldMoveMessageCooldown";
    private static final String DEEP_WATER_MESSAGE_COOLDOWN =
        "cobbleventureDeepWaterMessageCooldown";
    private static final String VERTICAL_BOUNDARY_MESSAGE_COOLDOWN =
        "cobbleventureVerticalBoundaryMessageCooldown";
    private static final double VERTICAL_BOUNDARY_Y = 252.0D;
    private static final String WHIRLPOOL_MESSAGE_COOLDOWN =
        "cobbleventureWhirlpoolMessageCooldown";
    private static final String WHIRLPOOL_REQUIREMENT =
        "cobbleventure:field_move/whirlpool";
    private static final List<String> SUPPORTED_FIELD_MOVES = List.of(
        "surf", "fly", "flash", "defog", "rock_climb", "whirlpool",
        "strength", "rock_smash"
    );
    private static final String CAVE_ROAD_ANCHOR = "cobbleventure:road_anchor";
    private static final Map<String, TransitionRegion> ACTIVE_SURFACE_ENTRY_REGIONS =
        new ConcurrentHashMap<>();
    private static final Map<String, TransitionRegion> ACTIVE_UNDERGROUND_DUNGEON_EXITS =
        new ConcurrentHashMap<>();
    private static volatile List<FacilityPortal> activeFacilityPortals = List.of();
    private static volatile List<FacilityMusicZone> activeFacilityMusicZones = List.of();
    private static volatile Map<String, SettlementPlan> activeSettlements = Map.of();
    private static volatile DimensionEventLocationResolver.Catalog activeDimensionAnchors;
    private static volatile EventBoundaryCatalog activeEventBoundaries;
    private static volatile HexWorldPlan activeHexWorld;
    private static volatile List<PursuitEncounterZone> activeCaveEncounters = List.of();
    private static volatile Map<String, PursuitEncounterSystem.Config> activeForestEncounters = Map.of();
    private static volatile List<ForestRegion> activeForestRegions = List.of();
    private static volatile Map<String, JsonObject> activeForestDocuments = Map.of();
    private static volatile Map<String, JsonObject> activeCaveDocuments = Map.of();
    private static volatile Map<String, JsonObject> activeUndergroundRoadDocuments = Map.of();
    private static volatile ShoreDistanceField activeShoreDistances;
    private static volatile int integrationShutdownTicks = -1;
    private static volatile UUID pendingInitializationPlayer;
    private static volatile int pendingInitializationTicks = -1;
    private static volatile WorldInitializationJob activeInitialization;
    private static volatile TownGenerationDisplay completedTownGenerationDisplay;
    private static volatile int completedTownGenerationDisplayTicks;
    private static final Map<String, SeededNoise> TERRAIN_NOISES = new ConcurrentHashMap<>();
    private static final GenerationalCache<TerrainColumnKey, NativeTerrainColumn>
        NATIVE_TERRAIN_COLUMNS = new GenerationalCache<>(262_144);
    // Narrow-feature stabilization samples overlapping neighbors for every column.
    // Cache the deterministic base height so adjacent columns share those samples.
    private static final GenerationalCache<TerrainHeightKey, Integer> BASE_TERRAIN_HEIGHTS =
        new GenerationalCache<>(524_288);
    private static final Map<TownFootprintCenterKey, Point> TOWN_FOOTPRINT_CENTERS =
        new ConcurrentHashMap<>();
    private static final Map<OceanMoundKey, Boolean> OCEAN_MOUND_BOUNDARY =
        new ConcurrentHashMap<>();
    private static final Map<CaveMouthCacheKey, CaveMouthGeometry> CAVE_MOUTHS =
        new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3> safeFieldPositions = new HashMap<>();
    private static final Map<UUID, SafeWaterPosition> safeWaterPositions = new HashMap<>();
    private static final Map<UUID, Integer> deepWaterTicks = new HashMap<>();
    private static final Map<UUID, Vec3> safeWhirlpoolPositions = new HashMap<>();
    private static final Map<Long, Long> scheduledTownDebrisCleanup = new HashMap<>();
    private static final Map<GenerationDebrisChunk, Long> scheduledGenerationDebrisCleanup =
        new HashMap<>();
    static final ResourceKey<Level> GENERATION_ONE =
        ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "generation_1")
        );
    private static final ResourceKey<Level> DUNGEONS =
        ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "dungeons")
        );
    private static final ResourceKey<Level> FORESTS =
        ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "forests")
        );
    private static final String CAVE_PORTAL_COOLDOWN = "cobbleventure_cave_portal_cooldown";
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
    private static final ResourceKey<net.minecraft.world.level.biome.Biome> SEALED_FOREST_EDGE =
        ResourceKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("cobbleventure", "sealed_forest_edge")
        );
    public CobbleventureBootstrap(IEventBus modBus) {
        modBus.addListener(CobbleventureBootstrap::onCommonSetup);
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.SETTLEMENT,
            CobbleventureBootstrap::resolveEventSettlement
        );
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.ANCHOR,
            (server, destination) -> EventBoundaryCatalog.resolveAnchor(
                activeEventBoundaries, destination
            )
        );
        EventBoundaryProviderRegistry.register(player -> {
            EventBoundaryCatalog catalog = activeEventBoundaries;
            if (catalog == null) {
                throw new IllegalStateException("event boundary catalog가 아직 준비되지 않았습니다.");
            }
            BlockPos position = player.blockPosition();
            EventBoundaryProviderRegistry.Snapshot indexed = catalog.snapshot(
                player.serverLevel().dimension().location().toString(),
                position.getX(), position.getY(), position.getZ()
            );
            return new EventBoundaryProviderRegistry.Snapshot(
                indexed.regions(), indexed.anchors(),
                BuildingRuntimeSystem.activeEventSpaces(player), indexed.dimensions()
            );
        });
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.SPACE,
            CobbleventureBootstrap::resolveEventSpace
        );
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.ROUTE,
            CobbleventureBootstrap::resolveEventRoute
        );
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.DIMENSION,
            (server, destination) -> DimensionEventLocationResolver.resolve(
                activeDimensionAnchors, destination
            )
        );
        CobbleventureAdventure.registerWorldContext(new AdventureWorldContext() {
            @Override
            public Integer averageWildSpawnLevel(
                ServerLevel level, double x, double z
            ) {
                return CobbleventureBootstrap.averageWildSpawnLevel(level, x, z);
            }

            @Override
            public Set<ResourceLocation> allowedWildSpecies(
                ServerLevel level, double x, double z
            ) {
                return HabitatSpawnRules.allowedSpecies(level, x, z);
            }

            @Override
            public Set<ResourceLocation> allowedWildSpecies(
                ServerLevel level, double x, double y, double z
            ) {
                return HabitatSpawnRules.allowedSpecies(level, x, y, z);
            }

            @Override
            public AdventureWorldContext.WildSpawnRule wildSpawnRule(
                ServerLevel level, double x, double z
            ) {
                return CobbleventureBootstrap.wildSpawnRule(level, x, z);
            }

            @Override
            public AdventureWorldContext.WildSpawnRule wildSpawnRule(
                ServerLevel level, double x, double z,
                AdventureWorldContext.WildEncounterMethod method
            ) {
                return CobbleventureBootstrap.wildSpawnRule(
                    level, x, z, method
                );
            }

            @Override
            public String authoredWeatherAt(ServerPlayer player) {
                return CobbleventureBootstrap.authoredWeatherAt(player);
            }
        });
        NativeWorldGeneration.register(modBus);
        TrainerCosmetics.register(modBus);
        StructureMarkerBlocks.register(modBus);
        StrengthPuzzleBlocks.register(modBus);
        RockSmashPuzzleBlocks.register(modBus);
        FlashCaveEffects.register();
        LocalWeatherSystem.register(modBus);
        GateDialogueNetwork.register(modBus);
        GymBlockerVisibilityNetwork.register(modBus);
        CopycatRenderSyncNetwork.register(modBus);
        DungeonSystem.register(modBus);
        GymInteriorSystem.register();
        BuildingRuntimeSystem.register();
        DoorTransitionSound.register();
        StarterSpawnSystem.register();
        BattleMovementBoundary.register();
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onChunkWatch);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onServerTick);
        NeoForge.EVENT_BUS.addListener(CobbleventureBootstrap::onRegisterCommands);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(DeferredXpBarRegistration::register);
    }

    private static EventLocationResolverRegistry.Resolution resolveEventSettlement(
        MinecraftServer server,
        EventLocationRef.Resource destination
    ) {
        Map<String, SettlementPlan> settlements = activeSettlements;
        if (settlements.isEmpty()) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        SettlementPlan settlement = settlements.get(destination.resourceId());
        if (settlement == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_NOT_FOUND
            );
        }
        if (!settlement.enabled()) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_DISABLED
            );
        }
        ServerLevel level = server.getLevel(GENERATION_ONE);
        if (level == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        BootstrapSavedData data = server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (!data.isSettlementGenerated(settlement.id())) {
            LOGGER.warn(
                "Event settlement destination rejected because the settlement is not generated: {}",
                settlement.id()
            );
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        BlockPoint point = destination.anchor() == null
            ? settlement.playerSpawn()
            : settlement.anchors().get(destination.anchor());
        if (point == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        return EventLocationResolverRegistry.Resolution.resolved(
            new EventLocationResolverRegistry.ResolvedLocation(
                GENERATION_ONE.location().toString(),
                point.x() + 0.5D, point.y(), point.z() + 0.5D,
                null, null
            )
        );
    }

    private static EventLocationResolverRegistry.Resolution resolveEventSpace(
        MinecraftServer server,
        EventLocationRef.Resource destination
    ) {
        EventLocationResolverRegistry.Resolution building =
            BuildingRuntimeSystem.resolveEventSpace(server, destination);
        if (building != null) {
            return building;
        }
        Map<String, JsonObject> caves = activeCaveDocuments;
        Map<String, JsonObject> forests = activeForestDocuments;
        if (caves.isEmpty() && forests.isEmpty()) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        JsonObject space = caves.get(destination.resourceId());
        boolean cave = space != null;
        if (space == null) space = forests.get(destination.resourceId());
        if (space == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_NOT_FOUND
            );
        }
        if (space.has("enabled") && !space.get("enabled").getAsBoolean()) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_DISABLED
            );
        }
        if (destination.anchor() == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_REQUIRED
            );
        }
        JsonObject dimension = space.getAsJsonObject("dimension");
        String dimensionId = requiredString(dimension, "id");
        BlockPoint point = cave
            ? caveEventAnchor(space, destination.anchor())
            : forestEventAnchor(space, destination.anchor());
        if (point == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        return EventLocationResolverRegistry.Resolution.resolved(
            new EventLocationResolverRegistry.ResolvedLocation(
                dimensionId, point.x() + 0.5D, point.y(), point.z() + 0.5D,
                null, null
            )
        );
    }

    private static EventLocationResolverRegistry.Resolution resolveEventRoute(
        MinecraftServer server,
        EventLocationRef.Resource destination
    ) {
        HexWorldPlan world = activeHexWorld;
        if (world == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.WORLD_NOT_READY
            );
        }
        String prefix = "cobbleventure:route/";
        String routeId = destination.resourceId().startsWith(prefix)
            ? destination.resourceId().substring(prefix.length())
            : destination.resourceId();
        ConnectionPath route = world.paths().stream()
            .filter(candidate -> candidate.id().equals(routeId))
            .findFirst().orElse(null);
        if (route == null || route.centerline().isEmpty()) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_NOT_FOUND
            );
        }
        int progress = switch (destination.anchor()) {
            case "start" -> 0;
            case "middle" -> 50;
            case "end" -> 100;
            case null -> -1;
            default -> -2;
        };
        if (progress == -1) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_REQUIRED
            );
        }
        if (progress == -2) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.ANCHOR_NOT_FOUND
            );
        }
        ServerLevel level = server.getLevel(GENERATION_ONE);
        if (level == null) {
            return EventLocationResolverRegistry.Resolution.failed(
                EventMovementFailureReason.DESTINATION_UNAVAILABLE
            );
        }
        RouteNpcPoint point = routeNpcPoint(route.centerline(), progress);
        int x = (int) Math.round(point.x());
        int z = (int) Math.round(point.z());
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        float yaw = (float) Math.toDegrees(Math.atan2(-point.tangentX(), point.tangentZ()));
        return EventLocationResolverRegistry.Resolution.resolved(
            new EventLocationResolverRegistry.ResolvedLocation(
                GENERATION_ONE.location().toString(), x + 0.5D, y, z + 0.5D, yaw, null
            )
        );
    }

    private static BlockPoint caveEventAnchor(JsonObject cave, String anchorId) {
        if (cave.has("entrances")) {
            for (JsonElement element : cave.getAsJsonArray("entrances")) {
                JsonObject entrance = element.getAsJsonObject();
                if (anchorId.equals(requiredString(entrance, "id"))) {
                    return blockPointFrom(entrance.getAsJsonObject("fallback_anchor"));
                }
            }
        }
        if (!cave.has("generator")) return null;
        JsonObject generator = cave.getAsJsonObject("generator");
        if (!generator.has("manual_layout")) return null;
        JsonObject layout = generator.getAsJsonObject("manual_layout");
        if (!layout.has("anchors")) return null;
        for (JsonElement element : layout.getAsJsonArray("anchors")) {
            JsonObject anchor = element.getAsJsonObject();
            if (!anchorId.equals(requiredString(anchor, "id"))) continue;
            BlockPoint floor = blockPointFrom(anchor.getAsJsonObject("position"));
            return new BlockPoint(floor.x(), floor.y() + 1, floor.z());
        }
        return null;
    }

    private static BlockPoint forestEventAnchor(JsonObject forest, String anchorId) {
        if (!forest.has("entrances")) return null;
        JsonObject origin = forest.getAsJsonObject("dimension").getAsJsonObject("origin");
        int originX = origin.get("x").getAsInt();
        int originY = origin.get("y").getAsInt();
        int originZ = origin.get("z").getAsInt();
        for (JsonElement element : forest.getAsJsonArray("entrances")) {
            JsonObject entrance = element.getAsJsonObject();
            if (!anchorId.equals(requiredString(entrance, "id"))) continue;
            JsonObject position = entrance.getAsJsonObject("position");
            int portalX = originX + position.get("x").getAsInt();
            int portalZ = originZ + position.get("z").getAsInt();
            return inwardDestination(portalX, originY, portalZ, originX, originZ);
        }
        return null;
    }

    private static BlockPoint inwardDestination(
        int portalX, int y, int portalZ, int centerX, int centerZ
    ) {
        double towardCenterX = centerX - portalX;
        double towardCenterZ = centerZ - portalZ;
        double length = Math.hypot(towardCenterX, towardCenterZ);
        int insetX = length < 0.01D ? 0
            : (int) Math.round(towardCenterX / length * 6.0D);
        int insetZ = length < 0.01D ? 0
            : (int) Math.round(towardCenterZ / length * 6.0D);
        return new BlockPoint(portalX + insetX, y, portalZ + insetZ);
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        ResourceLocation joinedType = BuiltInRegistries.ENTITY_TYPE.getKey(
            event.getEntity().getType()
        );
        if (isBlockedVanillaMob(event.getEntity().getType(), joinedType)) {
            event.setCanceled(true);
            return;
        }
        if (usesNpcWorldFont(joinedType)) {
            applyNpcWorldFont(event.getEntity());
        }
        if (event.getEntity() instanceof PokemonEntity pokemonEntity
            && pokemonEntity.getTags().contains(BuildingRuntimeSystem.FIXED_POKEMON_TAG)) {
            pokemonEntity.setNoAi(true);
            pokemonEntity.setDeltaMovement(Vec3.ZERO);
        }
        if (joinedType.getNamespace().equals("cobblemon")
            && (!(event.getEntity() instanceof PokemonEntity pokemonEntity)
                || pokemonEntity.getPokemon().isWild())
            && !event.getEntity().getTags().contains(PursuitEncounterSystem.ENTITY_TAG)
            && event.getLevel() instanceof ServerLevel encounterLevel
            && pursuitEncounterAt(encounterLevel, event.getEntity().getX(), event.getEntity().getZ()) != null) {
            logBlockedPokemon(event.getEntity(), "inside-pursuit-zone", ++blockedPursuitZonePokemon);
            event.setCanceled(true);
            return;
        }
        if (!event.getLevel().dimension().equals(GENERATION_ONE)) {
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
            logBlockedPokemon(event.getEntity(), "outside-playable-terrain", ++blockedOutsideTerrainPokemon);
            event.setCanceled(true);
        }
    }

    private static boolean isBlockedVanillaMob(
        EntityType<?> entityType, ResourceLocation entityId
    ) {
        return entityId.getNamespace().equals("minecraft")
            && (BLOCKED_VANILLA_MOBS.contains(entityType)
                || BLOCKED_VANILLA_MOB_CATEGORIES.contains(entityType.getCategory()));
    }

    private static void logBlockedPokemon(Entity entity, String reason, int count) {
        if (count > INITIAL_SPAWN_DIAGNOSTIC_EVENTS && count % 100 != 0) {
            return;
        }
        String species = entity instanceof PokemonEntity pokemonEntity
            ? pokemonEntity.getPokemon().getSpecies().getResourceIdentifier().toString()
            : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String ownership = entity instanceof PokemonEntity pokemonEntity
            ? (pokemonEntity.getPokemon().isWild() ? "wild" : "owned")
            : "unknown";
        LOGGER.warn(
            "[Spawn diagnosis] Cobblemon entity blocked #{}: reason={}, species={}, ownership={}, dimension={}, position=({}, {}, {})",
            count, reason, species, ownership, entity.level().dimension().location(),
            entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()
        );
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PursuitEncounterSystem.forget(player);
            BattleMovementBoundary.forget(player);
        }
    }

    private static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerPlayer player = event.getPlayer();
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(GENERATION_ONE)) {
            return;
        }
        long chunkKey = event.getPos().toLong();
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        if (data.isTownDebrisCleanupPending(chunkKey)) {
            scheduledTownDebrisCleanup.putIfAbsent(chunkKey, level.getGameTime() + 2L);
        }
        StructurePlacementFixes.scheduleCopycatChunkSync(player, event.getPos());
        repairLogBridgeChunk(level, event.getPos());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        pendingInitializationPlayer = null;
        pendingInitializationTicks = -1;
        activeInitialization = null;
        scheduledTownDebrisCleanup.clear();
        scheduledGenerationDebrisCleanup.clear();
        completedTownGenerationDisplay = null;
        completedTownGenerationDisplayTicks = 0;
        activeDimensionAnchors = null;
        activeEventBoundaries = null;
        DoorTransitionSound.reset();
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            throw new IllegalStateException("Cobbleventure generation_1 dimension is missing");
        }
        activeDimensionAnchors = DimensionEventLocationResolver.parse(
            readJsonResource(level, "catalogs/dimension-anchors.json")
        );
        activeEventBoundaries = EventBoundaryCatalog.parse(
            readJsonResource(level, "catalogs/event-boundaries.json")
        );
        BootstrapSavedData data = event.getServer().overworld().getDataStorage()
            .computeIfAbsent(
                new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
                DATA_FILE
            );

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

        ACTIVE_SURFACE_ENTRY_REGIONS.clear();
        ACTIVE_UNDERGROUND_DUNGEON_EXITS.clear();
        RuntimeWorld runtime = loadRuntimeWorld(level);
        activeHexWorld = runtime.hexWorld();
        activeSettlements = runtime.settlements();
        activeFacilityPortals = facilityPortals(runtime.settlements());
        activeFacilityMusicZones = facilityMusicZones(level, runtime.settlements());
        placeCaveEntrances(level, runtime.hexWorld());
        WorldGateSystem.placeAll(level, runtime.hexWorld());
        ServerLevel dungeons = event.getServer().getLevel(DUNGEONS);
        if (dungeons == null) {
            throw new IllegalStateException("Cobbleventure dungeons dimension is missing");
        }
        placeCaveInteriors(dungeons, runtime.hexWorld());
        spawnCaveNpcs(dungeons, activeCaveDocuments);
        ServerLevel forests = event.getServer().getLevel(FORESTS);
        if (forests == null) {
            throw new IllegalStateException("Cobbleventure forests dimension is missing");
        }
        ForestDimensionGenerator.generate(forests, level.getSeed(), activeForestDocuments);
        WorldGateSystem.placeForestDimensionGates(forests, runtime.hexWorld().gates());
        spawnForestNpcs(forests, activeForestDocuments);
        if (!Boolean.getBoolean(TOWN_SEQUENCE_PERFORMANCE_TEST_PROPERTY)
            && !Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY)) {
            GymInteriorSystem.initialize(event.getServer());
        }
        BuildingRuntimeSystem.initialize(event.getServer());
        prepareExistingEntrancePokemonCenterRuntime(level, runtime.hexWorld());
        DungeonSystem.initialize(event.getServer(), runtime.hexWorld());
        WorldStructureSystem.placeAll(level, runtime.hexWorld());
        StarterSpawnSystem.initialize(event.getServer());
        prepareExistingGymExteriors(level, runtime.settlements());
        prepareExistingBuildingRuntime(level, runtime.settlements());
        prepareExistingTownNpcs(level, runtime.settlements());
        refreshExistingConfiguredVendors(level, runtime.settlements());
        // Existing route NPCs are restored only after every structure system
        // has finished. Otherwise a later NBT placement can overwrite their
        // collision space and leave them embedded in a wall.
        spawnRouteNpcs(level, runtime.hexWorld());

        if (Boolean.getBoolean(TOWN_SEQUENCE_PERFORMANCE_TEST_PROPERTY)) {
            SettlementPlan starter = runtime.settlements().get(STARTER_SETTLEMENT);
            if (starter == null) {
                throw new IllegalStateException("Cobbleventure starter settlement is missing");
            }
            SettlementPlan next = runtime.settlements().values().stream()
                .filter(SettlementPlan::enabled)
                .filter(settlement -> !settlement.id().equals(starter.id()))
                .min(Comparator
                    .comparingInt((SettlementPlan settlement) ->
                        settlementDistanceSquared(starter, settlement))
                    .thenComparing(SettlementPlan::id))
                .orElseThrow(() -> new IllegalStateException(
                    "A second enabled settlement is required for town sequence measurement"
                ));
            activeInitialization = new WorldInitializationJob(
                level, null, data, BlockPos.ZERO, runtime,
                starter.playerSpawn().toBlockPos(), BlockPos.ZERO,
                List.of(starter, next), new GenerationProgress(null), false
            );
            LOGGER.info(
                "Town sequence performance measurement scheduled: first={}, second={}",
                starter.id(), next.id()
            );
            return;
        }

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
            String expectedBiome = biomeAt(
                world, center.x() + 0.5D, center.z() + 0.5D, expected
            );
            if (!actual.toString().equals(expectedBiome)) {
                throw new IllegalStateException(
                    "Native biome mismatch at " + surface + ": expected="
                        + expectedBiome + ", actual=" + actual
                );
            }
            verified++;
        }
        int bridgeSamples = 0;
        int bridgeSupports = 0;
        int bridgeLandTransitions = 0;
        for (ConnectionPath route : world.paths()) {
            if (!route.surfaceStyle().equals("log_bridge")) continue;
            boolean deckVerified = false;
            boolean supportVerified = false;
            boolean landTransitionVerified = false;
            for (Point point : route.centerline()) {
                for (int offsetX = -3; offsetX <= 3; offsetX++) {
                    for (int offsetZ = -3; offsetZ <= 3; offsetZ++) {
                        int x = point.x() + offsetX;
                        int z = point.z() + offsetZ;
                        LogBridgeDeckPlan deck = logBridgeDeckAt(world, x, z);
                        if (deck == null) continue;
                        NativeTerrainColumn terrain = nativeTerrainColumn(world, x, z);
                        if (deck.overOcean()
                            && terrain.groundY() > WATER_SURFACE_Y - 20) {
                            throw new IllegalStateException(
                                "Native log bridge raised the seabed: route=" + route.id()
                                    + ", position=" + new Point(x, z)
                                    + ", floorY=" + terrain.groundY()
                            );
                        }
                        BlockState actualDeck = level.getBlockState(
                            new BlockPos(x, deck.y(), z)
                        );
                        if (!actualDeck.equals(deck.state())) {
                            throw new IllegalStateException(
                                "Native log bridge deck is missing: route=" + route.id()
                                    + ", position=" + new Point(x, z)
                                    + ", expected=" + deck.state()
                                + ", actual=" + actualDeck
                            );
                        }
                        if (!deck.overOcean() && !landTransitionVerified) {
                            BlockState expectedRoad = worldRoadSurfaceBlock(world, x, z);
                            BlockState actualRoad = level.getBlockState(
                                new BlockPos(x, terrain.groundY(), z)
                            );
                            if (!actualRoad.equals(expectedRoad)) {
                                throw new IllegalStateException(
                                    "Native log bridge land transition has no road: route="
                                        + route.id() + ", position=" + new Point(x, z)
                                        + ", expected=" + expectedRoad
                                        + ", actual=" + actualRoad
                                );
                            }
                            bridgeLandTransitions++;
                            landTransitionVerified = true;
                        }
                        if (!deckVerified) {
                            bridgeSamples++;
                            deckVerified = true;
                        }
                        if (deck.support() && !supportVerified) {
                            BlockState actualSupport = level.getBlockState(
                                new BlockPos(x, terrain.groundY() + 1, z)
                            );
                            if (!actualSupport.is(Blocks.STRIPPED_SPRUCE_LOG)) {
                                throw new IllegalStateException(
                                    "Native log bridge support is missing: route=" + route.id()
                                        + ", position=" + new Point(x, z)
                                        + ", actual=" + actualSupport
                                );
                            }
                            bridgeSupports++;
                            supportVerified = true;
                        }
                    }
                }
                if (deckVerified && supportVerified && landTransitionVerified) break;
            }
        }
        if (world.paths().stream().anyMatch(route ->
            route.surfaceStyle().equals("log_bridge"))
            && (bridgeSamples == 0 || bridgeSupports == 0)) {
            throw new IllegalStateException(
                "Native log bridge routes have no verifiable deck and support"
            );
        }
        LOGGER.info(
            "Native JSON world verification succeeded: samples={}, logBridgeSamples={}, "
                + "logBridgeSupports={}, logBridgeLandTransitions={}",
            verified, bridgeSamples, bridgeSupports, bridgeLandTransitions
        );
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        LocalWeatherSystem.reset(player);

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
        activeFacilityMusicZones = facilityMusicZones(level, settlements);
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
        placeCaveEntrances(level, runtime.hexWorld());
        WorldGateSystem.placeAll(level, runtime.hexWorld());
        WorldStructureSystem.placeAll(level, runtime.hexWorld());
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
        activeFacilityMusicZones = facilityMusicZones(level, settlements);
        spawnRouteNpcs(level, runtime.hexWorld());

        data.complete(spawnPos, villagePos, MAP_VERSION);
        progress.update(100, "시작 지역 생성 완료");
        moveWaitingPlayersToStart(level, spawnPos);
        removeWaitingArea(level, waitingArea);
        firstPlayer.sendSystemMessage(Component.literal(
            "[Cobbleventure] 마을 데이터로 1세대 시작 지역과 연결 통로를 생성했습니다."
        ));
        return true;
    }

    private static void placeCaveEntrances(ServerLevel level, HexWorldPlan world) {
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            placeCaveEntrance(level, world, entrance);
        }
    }

    private static void placeCaveInteriors(ServerLevel level, HexWorldPlan world) {
        UndergroundRoadSystem.generate(
            level, world.seed(), activeUndergroundRoadDocuments.values()
        );
        List<NaturalCaveGenerator.Entrance> entrances = world.caveEntrances().stream()
            .filter(entrance -> !isUndergroundRoad(entrance))
            .filter(entrance -> entrance.destination() != null && entrance.portalAnchor() != null)
            .map(entrance -> new NaturalCaveGenerator.Entrance(
                entrance.entrance(), entrance.cave(), entrance.destination(), entrance.portalAnchor(),
                entrance.generationSettings()
            ))
            .toList();
        NaturalCaveGenerator.generate(
            level, world.seed(), entrances
        );
    }

    private static boolean isUndergroundRoad(CaveEntrancePlan entrance) {
        return entrance.cave().startsWith("cobbleventure:underground_road/");
    }

    private static void placeCaveEntrance(
        ServerLevel level, HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
        ConnectionPath approachRoad = caveEntranceRoad(world, entrance);
        Point center = mouth.tileCenter();
        double forwardX = mouth.forwardX();
        double forwardZ = mouth.forwardZ();
        int mouthX = mouth.x();
        int mouthZ = mouth.z();
        double mouthDistance = mouth.mouthDistance();
        int plannedFloorY = plannedCaveMouthFloorY(level, mouthX, mouthZ);
        String caveStructure = caveEntranceStructure(world, entrance);
        CaveEntrancePlacement placement = placeCaveEntranceTemplate(
            level, caveStructure, mouthX, plannedFloorY, mouthZ,
            horizontalDirection(forwardX, forwardZ),
            entrance.surfaceTransition(), !isUndergroundRoad(entrance)
        );
        if (placement == null) {
            return;
        }
        ACTIVE_SURFACE_ENTRY_REGIONS.put(
            entrance.id(), placement.surfaceEntryRegion()
        );
        if (!NativeWorldGeneration.usesNativeGenerator(
            level.getChunkSource().getGenerator()
        )) {
            restoreCaveEntranceBarrierRoof(level, world, entrance);
        }
        restoreCaveApproach(
            level, world, approachRoad, center, forwardX, forwardZ,
            mouthDistance, placement.floorY()
        );
        level.setBlock(
            placement.markerPosition().below(2),
            Blocks.LODESTONE.defaultBlockState(), 2
        );
        placeCavePokemonCenter(
            level, world, entrance, center, new Point(mouthX, mouthZ), approachRoad
        );
        LOGGER.info(
            "Cave entrance ensured: id={}, cave={}, pokemonCenter={}, structure={}, position={}",
            entrance.id(), entrance.cave(), entrance.pokemonCenterEnabled(), caveStructure,
            placement.markerPosition()
        );
    }

    private static String caveEntranceStructure(
        HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
        return entrance.structureVariants().getOrDefault(
            mouth.mountainTerrainType(), entrance.structure()
        );
    }

    /**
     * Cave templates use an odd X/Z size. Their exact floor-center is placed on
     * the collision boundary, and the authored +Z direction points into the
     * inaccessible terrain. Barrier blocks inside the NBT are temporary void
     * masks: they overwrite terrain during placement and only those transformed
     * positions are cleared afterwards.
     */
    private static CaveEntrancePlacement placeCaveEntranceTemplate(
        ServerLevel level, String structure,
        int anchorX, int floorY, int anchorZ, Direction inward,
        String surfaceTransition, boolean requireOddHorizontalSize
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) {
            LOGGER.error("Invalid cave entrance structure ID: {}", structure);
            return null;
        }
        var optionalTemplate = level.getStructureManager().get(structureId);
        if (optionalTemplate.isEmpty()) {
            LOGGER.error("Cave entrance template is missing: {}", structure);
            return null;
        }
        StructureTemplate template = optionalTemplate.orElseThrow();
        var size = template.getSize();
        if (requireOddHorizontalSize
            && ((size.getX() & 1) == 0 || (size.getZ() & 1) == 0)) {
            LOGGER.error(
                "Cave entrance template must have odd X/Z dimensions: structure={}, size={}x{}x{}",
                structure, size.getX(), size.getY(), size.getZ()
            );
            return null;
        }
        List<StructureTemplate.StructureBlockInfo> roadAnchors = template.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW
        ).stream().filter(CobbleventureBootstrap::isCaveRoadAnchor).toList();
        if (roadAnchors.size() != 1) {
            LOGGER.error(
                "Cave entrance template requires exactly one {} jigsaw: structure={}, found={}",
                CAVE_ROAD_ANCHOR, structure, roadAnchors.size()
            );
            return null;
        }
        Set<BlockPos> localTransitionBlocks = structureTransitionBlocks(
            level, structure, template, surfaceTransition
        );
        StructureTemplate.StructureBlockInfo localAnchor = roadAnchors.getFirst();
        Direction authoredOutward = JigsawBlock.getFrontFacing(localAnchor.state());
        if (!authoredOutward.getAxis().isHorizontal()) {
            LOGGER.error(
                "Cave road anchor must face horizontally: structure={}, position={}, facing={}",
                structure, localAnchor.pos(), authoredOutward
            );
            return null;
        }
        String rotationName = switch (inward) {
            case WEST -> "clockwise_90";
            case NORTH -> "clockwise_180";
            case EAST -> "counterclockwise_90";
            default -> "none";
        };
        BlockPoint rotatedAnchor = rotatedTemplateOffset(
            new BlockPoint(
                localAnchor.pos().getX(), localAnchor.pos().getY(),
                localAnchor.pos().getZ()
            ),
            size.getX(), size.getZ(), rotationName
        );
        int minX = anchorX - rotatedAnchor.x();
        int minZ = anchorZ - rotatedAnchor.z();
        BlockPoint origin = rotatedTemplateOrigin(
            minX, floorY - rotatedAnchor.y(), minZ,
            size.getX(), size.getZ(), rotationName
        );
        BlockPos blockPos = origin.toBlockPos();
        Rotation rotation = structureRotation(rotationName);
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE);
        ExplicitAirPlacementProcessor.configure(template, settings);
        Set<BlockPos> placedTransitionBlocks = localTransitionBlocks.stream()
            .map(local -> blockPos.offset(StructureTemplate.transform(
                local, Mirror.NONE, rotation, BlockPos.ZERO
            )))
            .collect(Collectors.toUnmodifiableSet());
        List<ChunkPos> forcedChunks = forceTemplateChunks(level, structure, blockPos);
        try {
            boolean placed = template.placeInWorld(
                level, blockPos, blockPos, settings,
                RandomSource.create(level.getSeed() ^ blockPos.asLong()), 2
            );
            if (!placed) {
                LOGGER.error("Cave entrance template placement failed: {} at {}", structure, blockPos);
                return null;
            }
            scheduleGenerationDebrisCleanup(
                level, structure, blockPos, template, rotation
            );
            List<StructureTemplate.StructureBlockInfo> placedRoadAnchors = template.filterBlocks(
                blockPos, settings, Blocks.JIGSAW
            ).stream().filter(CobbleventureBootstrap::isCaveRoadAnchor).toList();
            if (placedRoadAnchors.size() != 1) {
                LOGGER.error(
                    "Rotated cave road anchor is invalid: structure={}, found={}",
                    structure, placedRoadAnchors.size()
                );
                return null;
            }
            StructureTemplate.StructureBlockInfo placedAnchor = placedRoadAnchors.getFirst();
            Direction expectedOutward = inward.getOpposite();
            Direction actualOutward = JigsawBlock.getFrontFacing(placedAnchor.state());
            if (!placedAnchor.pos().equals(new BlockPos(anchorX, floorY, anchorZ))
                || actualOutward != expectedOutward) {
                LOGGER.error(
                    "Cave road anchor transform mismatch: structure={}, expected={} {}, actual={} {}",
                    structure, new BlockPos(anchorX, floorY, anchorZ), expectedOutward,
                    placedAnchor.pos(), actualOutward
                );
                level.setBlock(placedAnchor.pos(), Blocks.AIR.defaultBlockState(), 2);
                return null;
            }
            BlockState anchorFinalState = Blocks.COBBLESTONE.defaultBlockState();
            if (placedAnchor.nbt() != null) {
                try {
                    anchorFinalState = BlockStateParser.parseForBlock(
                        level.holderLookup(Registries.BLOCK),
                        placedAnchor.nbt().getString("final_state"), false
                    ).blockState();
                } catch (CommandSyntaxException error) {
                    LOGGER.warn("Invalid entrance road anchor final_state: {}", structure);
                }
            }
            level.setBlock(placedAnchor.pos(), anchorFinalState, 2);
            LOGGER.info(
                "Cave entrance template placed: structure={}, roadAnchor={}, rotation={}, transitionBlocks={}",
                structure, placedAnchor.pos(), rotationName, placedTransitionBlocks.size()
            );
            TransitionRegion transition = new TransitionRegion(placedTransitionBlocks.stream()
                .map(position -> new BlockPoint(
                    position.getX(), position.getY(), position.getZ()
                )).toList());
            return new CaveEntrancePlacement(placedAnchor.pos(), transition, floorY);
        } finally {
            releaseForcedChunks(level, forcedChunks);
        }
    }

    private static boolean isCaveRoadAnchor(
        StructureTemplate.StructureBlockInfo marker
    ) {
        return marker.nbt() != null
            && CAVE_ROAD_ANCHOR.equals(marker.nbt().getString("name"));
    }

    private static Set<BlockPos> structureTransitionBlocks(
        ServerLevel level, String structure, StructureTemplate template,
        String transitionId
    ) {
        ResourceLocation structureId = ResourceLocation.parse(structure);
        ResourceLocation metadataId = ResourceLocation.fromNamespaceAndPath(
            structureId.getNamespace(),
            "structure_metadata/" + structureId.getPath() + ".structure.json"
        );
        Resource resource = level.getServer().getResourceManager()
            .getResource(metadataId).orElseThrow(() -> new IllegalStateException(
                "Entrance transition metadata is missing: " + metadataId
            ));
        BlockPos seed = null;
        try (Reader reader = resource.openAsReader()) {
            JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement element : metadata.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                if (!anchor.get("type").getAsString().equals("transition")) continue;
                String label = anchor.has("label")
                    ? anchor.get("label").getAsString() : anchor.get("id").getAsString();
                if (!label.equals(transitionId)) continue;
                JsonArray position = anchor.getAsJsonArray("position");
                seed = new BlockPos(
                    position.get(0).getAsInt(), position.get(1).getAsInt(),
                    position.get(2).getAsInt()
                );
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException(
                "Invalid entrance transition metadata: " + metadataId, error
            );
        }
        if (seed == null) {
            throw new IllegalStateException(
                "Entrance transition anchor is missing: " + structure
                    + " / " + transitionId
            );
        }
        Set<BlockPos> barriers = template.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(), Blocks.BARRIER
        ).stream().map(StructureTemplate.StructureBlockInfo::pos)
            .collect(Collectors.toSet());
        if (!barriers.contains(seed)) {
            throw new IllegalStateException(
                "Transition anchor must point to a barrier: " + structure
            );
        }
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(seed);
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            if (!barriers.contains(current) || !connected.add(current)) continue;
            if (connected.size() > 4096) {
                throw new IllegalStateException(
                    "Transition barrier region is too large: " + structure
                );
            }
            for (Direction direction : Direction.values()) pending.add(current.relative(direction));
        }
        return Set.copyOf(connected);
    }

    private static void restoreCaveApproach(
        ServerLevel level, HexWorldPlan world, ConnectionPath approachRoad,
        Point center,
        double forwardX, double forwardZ,
        double mouthDistance, int baseY
    ) {
        double sideX = -forwardZ;
        double sideZ = forwardX;
        int approachLength = Math.max(18, (int) Math.ceil(mouthDistance));
        List<Integer> approachHeights = new ArrayList<>(approachLength + 1);
        for (int step = 0; step <= approachLength; step++) {
            int x = center.x() + (int) Math.round(forwardX * step);
            int z = center.z() + (int) Math.round(forwardZ * step);
            approachHeights.add(plannedTerrainGroundY(level, x, z));
        }
        int landingStart = Math.max(0, approachLength - 6);
        for (int step = landingStart; step <= approachLength; step++) {
            approachHeights.set(step, baseY);
        }
        for (int step = landingStart - 1; step >= 0; step--) {
            int nextY = approachHeights.get(step + 1);
            int naturalY = approachHeights.get(step);
            approachHeights.set(
                step, Math.max(nextY - 1, Math.min(nextY + 1, naturalY))
            );
        }
        Set<Long> painted = new HashSet<>();
        for (int step = 0; step <= approachLength; step++) {
            int x = center.x() + (int) Math.round(forwardX * step);
            int z = center.z() + (int) Math.round(forwardZ * step);
            int clearanceHalfWidth = step >= approachLength - 6 ? 3 : 2;
            for (int lateral = -clearanceHalfWidth;
                 lateral <= clearanceHalfWidth; lateral++) {
                int roadX = x + (int) Math.round(sideX * lateral);
                int roadZ = z + (int) Math.round(sideZ * lateral);
                if (!painted.add(blockColumnKey(roadX, roadZ))) {
                    continue;
                }
                int roadY = approachHeights.get(step);
                clearLegacyElevatedCaveRoad(level, roadX, roadY, roadZ);
                clearVegetationColumn(level, roadX, roadY, roadZ, 12);
                Direction ascent = caveApproachAscentDirection(
                    forwardX, forwardZ, approachHeights, step
                );
                int surfaceY = roadY + (ascent == null ? 0 : 1);
                int clearance = step >= approachLength - 6 ? 7 : 4;
                for (int y = surfaceY + 1; y <= surfaceY + clearance; y++) {
                    level.setBlock(
                        new BlockPos(roadX, y, roadZ),
                        Blocks.AIR.defaultBlockState(), 2
                    );
                }
                if (Math.abs(lateral) > 1) {
                    continue;
                }
                if (ascent != null) {
                    level.setBlock(
                        new BlockPos(roadX, roadY, roadZ),
                        caveRoadSurfaceBlock(
                            world, approachRoad, roadX, roadZ
                        ), 2
                    );
                }
                level.setBlock(
                    new BlockPos(roadX, surfaceY, roadZ),
                    caveRoadSurfaceBlock(
                        world, approachRoad, roadX, roadZ, ascent
                    ), 2
                );
            }
        }
    }

    private static ConnectionPath caveEntranceRoad(
        HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        return world.paths().stream()
            .filter(path -> entrance.id().equals(path.from())
                || entrance.id().equals(path.to()))
            .filter(path -> path.surfaceStyle().equals("road"))
            .findFirst()
            .orElse(null);
    }

    private static BlockState caveRoadSurfaceBlock(
        HexWorldPlan world, ConnectionPath road, int x, int z
    ) {
        return caveRoadSurfaceBlock(world, road, x, z, null);
    }

    private static BlockState caveRoadSurfaceBlock(
        HexWorldPlan world, ConnectionPath road, int x, int z,
        Direction ascent
    ) {
        if (ascent != null) {
            return roadStairBlock(roadSurfaceChoice(world, x, z), ascent);
        }
        if (road == null) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        TerrainSample roadSample = new TerrainSample(
            road.biome(), road.boundaryProfile(), "route", road.id(),
            road.terrainProfile(), road.accessRequirement(), road.surfaceStyle()
        );
        return fullRoadSurfaceBlock(world, roadSample, x, z);
    }

    private static Direction caveApproachAscentDirection(
        double forwardX, double forwardZ, List<Integer> heights, int step
    ) {
        if (step < heights.size() - 1
            && heights.get(step + 1) > heights.get(step)) {
            return horizontalDirection(forwardX, forwardZ);
        }
        if (step > 0 && heights.get(step - 1) > heights.get(step)) {
            return horizontalDirection(-forwardX, -forwardZ);
        }
        return null;
    }

    private static Direction horizontalDirection(double x, double z) {
        return Math.abs(x) >= Math.abs(z)
            ? x >= 0.0D ? Direction.EAST : Direction.WEST
            : z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private static void clearLegacyElevatedCaveRoad(
        ServerLevel level, int x, int groundY, int z
    ) {
        int topY = Math.min(
            level.getMaxBuildHeight() - 1,
            Math.max(groundY + 12, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z))
        );
        for (int y = groundY + 1; y <= topY; y++) {
            BlockPos position = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(position);
            if (isRegionalRoadSurface(state)) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                for (int supportY = y - 1; supportY > groundY; supportY--) {
                    BlockPos support = new BlockPos(x, supportY, z);
                    if (!level.getBlockState(support).is(Blocks.STONE)) {
                        break;
                    }
                    level.setBlock(support, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static boolean isRegionalRoadSurface(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_SLAB)
            || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.STONE_BRICK_SLAB)
            || state.is(Blocks.MOSSY_STONE_BRICKS)
            || state.is(Blocks.MOSSY_STONE_BRICK_SLAB);
    }

    private static int caveMouthOpeningHeight(int lateral) {
        return switch (Math.abs(lateral)) {
            case 3 -> 4;
            case 2 -> 6;
            default -> 7;
        };
    }

    private static CaveMouthGeometry caveMouthGeometry(
        HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        CaveMouthCacheKey key = new CaveMouthCacheKey(
            System.identityHashCode(world), world.seed(), entrance.id()
        );
        return CAVE_MOUTHS.computeIfAbsent(
            key, ignored -> computeCaveMouthGeometry(world, entrance)
        );
    }

    private static CaveMouthGeometry computeCaveMouthGeometry(
        HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        HexGrid grid = world.grid();
        Point center = grid.worldCenter(entrance.anchor());
        Point direction = caveFacingVector(entrance.facing());
        double forwardX = direction.x();
        double forwardZ = direction.z();
        double length = grid.radius() * 2.0D;
        double collisionDistance = actualCaveBoundaryDistance(
            world, center, forwardX, forwardZ, length
        );
        double mouthDistance = Math.max(1.0D, collisionDistance - 2.0D);
        int mouthX = center.x() + (int) Math.round(forwardX * mouthDistance);
        int mouthZ = center.z() + (int) Math.round(forwardZ * mouthDistance);
        int mountainStartDepth = caveMountainStartDepth(
            world, mouthX, mouthZ, forwardX, forwardZ
        );
        int terrainSampleDepth = Math.min(
            OUTER_TERRAIN_TRANSITION_WIDTH * 2,
            mountainStartDepth + 4
        );
        int terrainX = mouthX
            + (int) Math.round(forwardX * terrainSampleDepth);
        int terrainZ = mouthZ
            + (int) Math.round(forwardZ * terrainSampleDepth);
        String mountainTerrainType = emptyTerrainAt(
            world, terrainX + 0.5D, terrainZ + 0.5D
        );
        return new CaveMouthGeometry(
            center, forwardX, forwardZ,
            collisionDistance, mouthDistance, mouthX, mouthZ,
            mountainTerrainType
        );
    }

    private static int caveMountainStartDepth(
        HexWorldPlan world, int mouthX, int mouthZ,
        double forwardX, double forwardZ
    ) {
        int searchLimit = OUTER_TERRAIN_TRANSITION_WIDTH * 2;
        for (int depth = 0; depth <= searchLimit; depth++) {
            int sampleX = mouthX + (int) Math.round(forwardX * depth);
            int sampleZ = mouthZ + (int) Math.round(forwardZ * depth);
            if (terrainAtBlockCenter(world, sampleX, sampleZ) == null) {
                return depth;
            }
        }
        return 0;
    }

    private record CaveMouthCacheKey(
        int worldIdentity, long seed, String entranceId
    ) {}

    private static Point caveFacingVector(String facing) {
        return switch (facing) {
            case "north" -> new Point(0, -1);
            case "east" -> new Point(1, 0);
            case "south" -> new Point(0, 1);
            case "west" -> new Point(-1, 0);
            default -> throw new IllegalStateException(
                "Unsupported cave entrance facing: " + facing
            );
        };
    }

    /** Finds the exact warped boundary column on which the collision shell is
     * generated. The caller derives the visible mouth from this single source. */
    static double actualCaveBoundaryDistance(
        HexWorldPlan world, Point center,
        double forwardX, double forwardZ, double maximumDistance
    ) {
        boolean crossedPlayableTerrain = false;
        int limit = Math.max(1, (int) Math.ceil(maximumDistance));
        for (int distance = 0; distance <= limit; distance++) {
            int x = center.x() + (int) Math.round(forwardX * distance);
            int z = center.z() + (int) Math.round(forwardZ * distance);
            if (terrainAt(world, x + 0.5D, z + 0.5D) != null) {
                crossedPlayableTerrain = true;
                continue;
            }
            if (crossedPlayableTerrain
                && isHiddenBoundaryCollisionColumn(world, x, z)) {
                return distance;
            }
        }
        return Math.max(3.0D, maximumDistance * 0.5D);
    }

    private record CaveMouthGeometry(
        Point tileCenter,
        double forwardX,
        double forwardZ,
        double collisionDistance,
        double mouthDistance,
        int x,
        int z,
        String mountainTerrainType
    ) {}

    private record CaveEntrancePlacement(
        BlockPos markerPosition,
        TransitionRegion surfaceEntryRegion,
        int floorY
    ) {}

    private record TransitionRegion(List<BlockPoint> blocks) {
        boolean contains(BlockPos position) {
            return blocks.stream().anyMatch(block ->
                block.x() == position.getX()
                    && block.y() == position.getY()
                    && block.z() == position.getZ()
            );
        }

        boolean touches(ServerPlayer player) {
            AABB playerBounds = player.getBoundingBox().inflate(0.08D);
            for (BlockPoint block : blocks) {
                if (playerBounds.intersects(new AABB(
                    block.x(), block.y(), block.z(),
                    block.x() + 1.0D, block.y() + 1.0D, block.z() + 1.0D
                ))) return true;
            }
            return false;
        }
    }

    static boolean isCaveEntrancePassage(
        HexWorldPlan world, int x, int z
    ) {
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
            double offsetX = x + 0.5D - mouth.tileCenter().x();
            double offsetZ = z + 0.5D - mouth.tileCenter().z();
            double forward = offsetX * mouth.forwardX() + offsetZ * mouth.forwardZ();
            double lateral = Math.abs(
                offsetX * -mouth.forwardZ() + offsetZ * mouth.forwardX()
            );
            if (forward >= -6.0D
                && forward <= mouth.collisionDistance() + 18.0D
                && lateral <= 5.5D) {
                return true;
            }
        }
        return false;
    }

    static int plannedCaveMouthFloorY(
        ServerLevel level, int x, int z
    ) {
        return plannedTerrainGroundY(level, x, z);
    }

    private static boolean isCaveMountainProtectedColumn(
        HexWorldPlan world, int x, int z
    ) {
        if (world == null || world.caveEntrances().size() < 2) {
            return false;
        }
        Map<String, List<CaveEntrancePlan>> byCave = world.caveEntrances().stream()
            .collect(Collectors.groupingBy(CaveEntrancePlan::cave));
        for (List<CaveEntrancePlan> entrances : byCave.values()) {
            if (entrances.size() < 2) {
                continue;
            }
            Point first = world.grid().worldCenter(entrances.getFirst().anchor());
            Point second = world.grid().worldCenter(entrances.get(1).anchor());
            double centerX = (first.x() + second.x()) * 0.5D;
            double centerZ = (first.z() + second.z()) * 0.5D;
            double radius = world.grid().radius() + 14.0D;
            double dx = x + 0.5D - centerX;
            double dz = z + 0.5D - centerZ;
            if (dx * dx + dz * dz <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRockClimbTerrain(TerrainSample sample) {
        return "cobbleventure:field_move/rock_climb".equals(
            sample.accessRequirement()
        );
    }

    private static void placeCaveMouthLandmark(
        ServerLevel level,
        int centerX, int floorY, int centerZ,
        double forwardX, double forwardZ
    ) {
        double sideX = -forwardZ;
        double sideZ = forwardX;
        for (int lateral = -5; lateral <= 5; lateral++) {
            int absoluteLateral = Math.abs(lateral);
            int outerHeight = caveMouthOuterHeight(lateral);
            int openingHeight = absoluteLateral <= 3
                ? caveMouthOpeningHeight(lateral) : -1;
            for (int vertical = 0; vertical <= outerHeight; vertical++) {
                if (absoluteLateral <= 3 && vertical <= openingHeight) {
                    continue;
                }
                int x = centerX + (int) Math.round(sideX * lateral);
                int z = centerZ + (int) Math.round(sideZ * lateral);
                BlockState frame = (vertical + Math.abs(lateral)) % 4 == 0
                    ? Blocks.TUFF.defaultBlockState()
                    : (vertical + lateral) % 3 == 0
                        ? Blocks.ANDESITE.defaultBlockState()
                        : Blocks.STONE.defaultBlockState();
                level.setBlock(new BlockPos(x, floorY + vertical, z), frame, 2);
            }
        }
        for (int lateral = -3; lateral <= 3; lateral++) {
            int ceiling = caveMouthOpeningHeight(lateral);
            for (int vertical = 1; vertical <= ceiling; vertical++) {
                int x = centerX + (int) Math.round(sideX * lateral);
                int z = centerZ + (int) Math.round(sideZ * lateral);
                level.setBlock(new BlockPos(x, floorY + vertical, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // Rebuild a closed rock shell around the carved passage. The legacy
        // cutout is much wider than the visible arch, so a roof-only repair
        // leaves open sides and a hole behind the facade. Preserve only the
        // central aperture and fill its roof, flanks and rear back into the
        // mountain. Keep the outward part narrower to avoid a new rock snout.
        for (int depth = -2; depth <= 22; depth++) {
            int shellHalfWidth = depth < 0 ? 5 : 8;
            for (int lateral = -shellHalfWidth;
                 lateral <= shellHalfWidth; lateral++) {
                int x = centerX + (int) Math.round(
                    forwardX * depth + sideX * lateral
                );
                int z = centerZ + (int) Math.round(
                    forwardZ * depth + sideZ * lateral
                );
                int absoluteLateral = Math.abs(lateral);
                int openingHeight = absoluteLateral <= 3
                    ? caveMouthOpeningHeight(lateral) : -1;
                int outerHeight = caveMouthOuterHeight(lateral);
                for (int vertical = 0; vertical <= outerHeight; vertical++) {
                    if (absoluteLateral <= 3
                        && vertical <= openingHeight) {
                        continue;
                    }
                    BlockState roof = Math.floorMod(
                        x + z + depth + vertical, 5
                    ) == 0
                        ? Blocks.ANDESITE.defaultBlockState()
                        : Blocks.STONE.defaultBlockState();
                    level.setBlock(
                        new BlockPos(x, floorY + vertical, z), roof, 2
                    );
                }
            }
        }
        // 외부 월드의 동굴 입구는 자연 지형으로 남긴다. 밝은 출구 표시는
        // 동굴 차원 안쪽의 출구 랜드마크에서만 생성한다.
        for (int depth : new int[] {-12, -7, -3, 3, 8}) {
            int x = centerX + (int) Math.round(forwardX * depth);
            int z = centerZ + (int) Math.round(forwardZ * depth);
            BlockPos position = new BlockPos(x, floorY, z);
            if (level.getBlockState(position).is(Blocks.OCHRE_FROGLIGHT)) {
                level.setBlock(
                    position,
                    depth < -2
                        ? Blocks.COBBLESTONE.defaultBlockState()
                        : Blocks.COBBLED_DEEPSLATE.defaultBlockState(),
                    2
                );
            }
        }
    }

    private static int caveMouthOuterHeight(int lateral) {
        return switch (Math.abs(lateral)) {
            case 8 -> 3;
            case 7 -> 4;
            case 6 -> 6;
            case 5 -> 8;
            case 4 -> 9;
            case 3 -> 10;
            case 2 -> 11;
            default -> 12;
        };
    }

    private static void placeCavePokemonCenter(
        ServerLevel level, HexWorldPlan world, CaveEntrancePlan entrance,
        Point entranceCenter, Point caveMouth, ConnectionPath approachRoad
    ) {
        if (!entrance.pokemonCenterEnabled()) {
            return;
        }
        HexGrid grid = world.grid();
        HexCoord offset = entrance.pokemonCenterOffset();
        Point offsetCenter = grid.worldCenter(new HexCoord(
            entrance.anchor().q() + offset.q(), entrance.anchor().r() + offset.r()
        ));
        double deltaX = offsetCenter.x() - entranceCenter.x();
        double deltaZ = offsetCenter.z() - entranceCenter.z();
        double length = Math.max(1.0D, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
        int centerX = entranceCenter.x() + (int) Math.round(deltaX / length * 28.0D);
        int centerZ = entranceCenter.z() + (int) Math.round(deltaZ / length * 28.0D);
        String structure = entrance.pokemonCenterStructure();
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null || level.getStructureManager().get(structureId).isEmpty()) {
            LOGGER.error(
                "Required cave Pokemon Center template is missing: entrance={}, structure={}",
                entrance.id(), structure
            );
            return;
        }
        var size = level.getStructureManager().get(structureId).orElseThrow().getSize();
        Direction roadFacing = horizontalDirection(
            entranceCenter.x() - centerX, entranceCenter.z() - centerZ
        );
        String rotation = pokemonCenterRotation(roadFacing);
        boolean quarterTurn = rotation.equals("clockwise_90")
            || rotation.equals("counterclockwise_90");
        int footprintWidth = quarterTurn ? size.getZ() : size.getX();
        int footprintDepth = quarterTurn ? size.getX() : size.getZ();
        int groundY = plannedTerrainGroundY(level, centerX, centerZ);
        BlockPoint origin = new BlockPoint(
            centerX - footprintWidth / 2,
            groundY - 3,
            centerZ - footprintDepth / 2
        );
        FacilityPlacement facility = new FacilityPlacement(
            "facility_pokemon_center", "direct_template", structure,
            "pokemon_center", "포켓몬센터", "cave_entrance", null, null,
            null, null, null, 0.0D,
            footprintWidth, footprintDepth, size.getY(), 4
        );
        prepareSpecialDistrict(level, facility, origin, rotation);
        if (!placeFacilityTemplate(level, facility, origin, rotation)) {
            LOGGER.error(
                "Cave Pokemon Center NBT placement failed: entrance={}, structure={}, origin={}",
                entrance.id(), structure, origin
            );
            return;
        }
        BlockPoint placedOrigin = facilityPlacementOrigin(
            level, facility, origin, rotation
        );
        BuildingRuntimeSystem.onStructurePlaced(
            level, structure, placedOrigin, rotation
        );
        if (!placeFacilityJigsawDecorations(level, facility, origin, rotation)) {
            LOGGER.warn("Cave Pokemon Center berry decorations were not completed: {}", entrance.id());
        }
        cleanupFacilityTemplateMarkers(level, structure, origin, rotation);
        if (!placeFacilityWorkers(level, null, facility, origin, rotation)) {
            LOGGER.warn("Cave Pokemon Center worker placement was not completed: {}", entrance.id());
        }
        Point facilityEntrance = rotatedPokemonCenterEntrance(
            origin, size.getX(), size.getZ(), facility.clearance(), rotation
        );
        // The cave mountain is raised after the base terrain is generated. A direct
        // diagonal from the mouth to the facility can therefore cut straight through
        // that mountain. Follow the authored approach back into the entrance tile
        // first, then turn toward the facility on ordinary terrain.
        drawCaveAccessRoad(
            level, world, approachRoad, entranceCenter, facilityEntrance
        );
        LOGGER.info(
            "Existing Pokemon Center NBT placed for cave entrance: entrance={}, structure={}, origin={}, facing={}, rotation={}",
            entrance.id(), structure, origin, roadFacing, rotation
        );
    }

    /**
     * Entrance facilities are placed before BuildingRuntimeSystem loads its NBT anchor
     * metadata during server startup. Re-run only the runtime-anchor phase afterwards;
     * replacing the structure NBT here would overwrite the already generated facility.
     */
    private static void prepareExistingEntrancePokemonCenterRuntime(
        ServerLevel level, HexWorldPlan world
    ) {
        int prepared = 0;
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            if (!entrance.pokemonCenterEnabled()) {
                continue;
            }
            Point entranceCenter = world.grid().worldCenter(entrance.anchor());
            HexCoord offset = entrance.pokemonCenterOffset();
            Point offsetCenter = world.grid().worldCenter(new HexCoord(
                entrance.anchor().q() + offset.q(), entrance.anchor().r() + offset.r()
            ));
            double deltaX = offsetCenter.x() - entranceCenter.x();
            double deltaZ = offsetCenter.z() - entranceCenter.z();
            double length = Math.max(1.0D, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
            int centerX = entranceCenter.x() + (int) Math.round(deltaX / length * 28.0D);
            int centerZ = entranceCenter.z() + (int) Math.round(deltaZ / length * 28.0D);
            String structure = entrance.pokemonCenterStructure();
            ResourceLocation structureId = ResourceLocation.tryParse(structure);
            var template = structureId == null
                ? Optional.<StructureTemplate>empty()
                : level.getStructureManager().get(structureId);
            if (template.isEmpty()) {
                LOGGER.error(
                    "Entrance Pokemon Center runtime metadata skipped because its template is missing: entrance={}, structure={}",
                    entrance.id(), structure
                );
                continue;
            }
            var size = template.orElseThrow().getSize();
            Direction roadFacing = horizontalDirection(
                entranceCenter.x() - centerX, entranceCenter.z() - centerZ
            );
            String rotation = pokemonCenterRotation(roadFacing);
            boolean quarterTurn = rotation.equals("clockwise_90")
                || rotation.equals("counterclockwise_90");
            int footprintWidth = quarterTurn ? size.getZ() : size.getX();
            int footprintDepth = quarterTurn ? size.getX() : size.getZ();
            int groundY = plannedTerrainGroundY(level, centerX, centerZ);
            BlockPoint origin = new BlockPoint(
                centerX - footprintWidth / 2,
                groundY - 3,
                centerZ - footprintDepth / 2
            );
            FacilityPlacement facility = new FacilityPlacement(
                "facility_pokemon_center", "direct_template", structure,
                "pokemon_center", "포켓몬센터", "cave_entrance", null, null,
                null, null, null, 0.0D,
                footprintWidth, footprintDepth, size.getY(), 4
            );
            BlockPoint placedOrigin = facilityPlacementOrigin(
                level, facility, origin, rotation
            );
            BuildingRuntimeSystem.onStructurePlaced(
                level, structure, placedOrigin, rotation
            );
            prepared++;
        }
        if (prepared > 0) {
            LOGGER.info(
                "Entrance Pokemon Center runtime anchors restored: centers={}", prepared
            );
        }
    }

    private static String pokemonCenterRotation(Direction facing) {
        return switch (facing) {
            case NORTH -> "clockwise_90";
            case EAST -> "clockwise_180";
            case SOUTH -> "counterclockwise_90";
            default -> "none";
        };
    }

    private static Point rotatedPokemonCenterEntrance(
        BlockPoint origin, int width, int depth, int clearance, String rotation
    ) {
        int localX = -Math.max(2, clearance);
        int localZ = Math.min(10, depth - 1);
        BlockPoint rotated = rotatedTemplateOffset(
            new BlockPoint(localX, 0, localZ), width, depth, rotation
        );
        return new Point(origin.x() + rotated.x(), origin.z() + rotated.z());
    }

    private static void drawCaveAccessRoad(
        ServerLevel level, HexWorldPlan world, ConnectionPath road,
        Point start, Point end
    ) {
        int dx = end.x() - start.x();
        int dz = end.z() - start.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        Set<Long> painted = new HashSet<>();
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int centerX = (int) Math.round(start.x() + dx * factor);
            int centerZ = (int) Math.round(start.z() + dz * factor);
            for (int offsetX = -2; offsetX <= 2; offsetX++) {
                for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                    int x = centerX + offsetX;
                    int z = centerZ + offsetZ;
                    if (!painted.add(blockColumnKey(x, z))) {
                        continue;
                    }
                    int groundY = plannedTerrainGroundY(level, x, z);
                    clearLegacyElevatedCaveRoad(level, x, groundY, z);
                    if (offsetX * offsetX + offsetZ * offsetZ > 2) {
                        continue;
                    }
                    clearVegetationColumn(level, x, groundY, z, 12);
                    level.setBlock(
                        new BlockPos(x, groundY, z),
                        caveRoadSurfaceBlock(world, road, x, z), 2
                    );
                    for (int y = groundY + 1; y <= groundY + 4; y++) {
                        level.setBlock(
                            new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2
                        );
                    }
                }
            }
        }
    }

    private static boolean placeTown(ServerLevel level, SettlementPlan settlement) {
        BlockPos villagePos = surfacePosition(
            level, settlement.center().x(), settlement.center().z()
        ).below();
        drawNativeTownRoadSkeleton(level, settlement);
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
        Map<String, String> houseStructures = resolveTownHouseStructures(
            settlement, layout.houses()
        );
        List<RuntimeAccessRoad> runtimeAccessRoads = new ArrayList<>();
        for (TownRoad accessRoad : layout.accessRoads()) {
            String buildingId = null;
            boolean entranceSegment = false;
            for (Map.Entry<String, List<TownRoad>> entry
                : layout.buildingAccessRoads().entrySet()) {
                List<TownRoad> buildingRoads = entry.getValue();
                if (!buildingRoads.contains(accessRoad)) {
                    continue;
                }
                buildingId = entry.getKey();
                entranceSegment = !buildingRoads.isEmpty()
                    && buildingRoads.get(buildingRoads.size() - 1).equals(accessRoad);
                break;
            }
            int accessWidth = buildingId != null
                && layout.facilities().containsKey(buildingId)
                ? facilityApproachRoadWidth(buildingId, road.width())
                : Math.min(3, road.width());
            runtimeAccessRoads.add(new RuntimeAccessRoad(
                accessRoad, accessWidth, entranceSegment
            ));
        }
        int removedNaturalTrees = clearTownChunkTrees(level, settlement);
        long townTreeClearFinishedAt = System.nanoTime();
        Set<Long> roadColumns = new HashSet<>();
        for (TownRoad generatedRoad : layout.roads()) {
            collectConfiguredRoadColumns(
                roadColumns,
                center.translate(generatedRoad.x1(), generatedRoad.z1()),
                center.translate(generatedRoad.x2(), generatedRoad.z2()),
                road.width()
            );
        }
        for (RuntimeAccessRoad runtimeRoad : runtimeAccessRoads) {
            TownRoad accessRoad = runtimeRoad.road();
            collectConfiguredRoadColumns(
                roadColumns,
                center.translate(accessRoad.x1(), accessRoad.z1()),
                center.translate(accessRoad.x2(), accessRoad.z2()),
                runtimeRoad.width(),
                runtimeRoad.entranceSegment()
            );
        }
        Map<Long, Integer> roadElevations = loadedRoadElevations(level, roadColumns);
        long elevationsFinishedAt = System.nanoTime();
        for (long key : roadColumns) {
            paintConfiguredRoadColumn(
                level, blockColumnX(key), blockColumnZ(key), road.material(),
                roadElevations.get(key),
                configuredRoadStairDirection(key, roadColumns, roadElevations), true, true
            );
        }
        long paintingFinishedAt = System.nanoTime();
        connectTownRoadsToRegionalRoutes(level, settlement);
        long roadsFinishedAt = System.nanoTime();

        List<TownTemplatePlacement> housePlacements = new ArrayList<>();
        for (TownPlot house : layout.houses()) {
            String structure = houseStructures.get(house.id());
            int x = center.x() + (int) Math.round(house.x());
            int z = center.z() + (int) Math.round(house.z());
            TownRoad entranceRoad = townBuildingEntranceRoad(layout, house);
            int roadX = center.x() + entranceRoad.x2();
            int roadZ = center.z() + entranceRoad.z2();
            int groundY = roadElevations.getOrDefault(
                blockColumnKey(roadX, roadZ), loadedRoadSurfaceY(level, roadX, roadZ)
            );
            BlockPoint origin = rotatedTemplateOrigin(
                x, groundY + BuildingRuntimeSystem.placementYOffset(structure), z,
                house.width(), house.depth(), house.rotation()
            );
            housePlacements.add(new TownTemplatePlacement(
                structure, origin, house.rotation()
            ));
        }
        long housePreparationFinishedAt = System.nanoTime();
        preloadTemplateChunks(level, housePlacements);
        long housePreloadFinishedAt = System.nanoTime();
        long houseNbtElapsedNanos = 0L;
        long houseRuntimeElapsedNanos = 0L;
        for (TownTemplatePlacement placement : housePlacements) {
            long nbtStartedAt = System.nanoTime();
            if (!placeTemplateLoaded(
                level, placement.structure(), placement.position(), placement.rotation()
            )) {
                String structure = placement.structure();
                throw new IllegalStateException("Basic building NBT placement failed: " + structure);
            }
            houseNbtElapsedNanos += System.nanoTime() - nbtStartedAt;
            long runtimeStartedAt = System.nanoTime();
            BuildingRuntimeSystem.onStructurePlaced(
                level, placement.structure(), placement.position(), placement.rotation()
            );
            houseRuntimeElapsedNanos += System.nanoTime() - runtimeStartedAt;
        }
        long housePlacementFinishedAt = System.nanoTime();
        long housesFinishedAt = System.nanoTime();
        int roadStairs = 0;
        int roadSlabs = 0;
        int unwalkableRoadEdges = 0;
        for (long key : roadColumns) {
            Direction stairDirection = configuredRoadStairDirection(
                key, roadColumns, roadElevations
            );
            int surfaceY = roadElevations.get(key)
                + (stairDirection == null ? 0 : 1);
            BlockState surface = level.getBlockState(new BlockPos(
                blockColumnX(key), surfaceY, blockColumnZ(key)
            ));
            if (isConfiguredRoadStair(surface)) {
                roadStairs++;
            }
            if (isConfiguredRoadSlab(surface)) {
                roadSlabs++;
            }
            int x = blockColumnX(key);
            int z = blockColumnZ(key);
            for (Direction direction : List.of(Direction.EAST, Direction.SOUTH)) {
                long adjacent = blockColumnKey(
                    x + direction.getStepX(), z + direction.getStepZ()
                );
                if (roadColumns.contains(adjacent)
                    && Math.abs(roadElevations.get(key) - roadElevations.get(adjacent)) > 1) {
                    unwalkableRoadEdges++;
                }
            }
        }
        LOGGER.info(
            "Generated town layout applied: settlement={}, seed={}, depth={}, roads={}, facilities={}, houses={}, roadColumns={}, roadStairs={}, roadSlabs={}, unwalkableRoadEdges={}, removedNaturalTrees={}, roadMs={}, townTreeClearMs={}, roadTerrainMs={}, roadPaintMs={}, houseMs={}, housePreparationMs={}, housePreloadMs={}, houseNbtMs={}, houseRuntimeMs={}, roadRestoreMs={}, totalMs={}",
            settlement.id(), settlement.generationSeed(), settlement.generationDepth(),
            layout.roads().size(), layout.facilities().size(), layout.houses().size(),
            roadColumns.size(), roadStairs, roadSlabs, unwalkableRoadEdges,
            removedNaturalTrees,
            (roadsFinishedAt - startedAt) / 1_000_000L,
            (townTreeClearFinishedAt - startedAt) / 1_000_000L,
            (elevationsFinishedAt - townTreeClearFinishedAt) / 1_000_000L,
            (paintingFinishedAt - elevationsFinishedAt) / 1_000_000L,
            (housesFinishedAt - roadsFinishedAt) / 1_000_000L,
            (housePreparationFinishedAt - roadsFinishedAt) / 1_000_000L,
            (housePreloadFinishedAt - housePreparationFinishedAt) / 1_000_000L,
            houseNbtElapsedNanos / 1_000_000L,
            houseRuntimeElapsedNanos / 1_000_000L,
            (housesFinishedAt - housePlacementFinishedAt) / 1_000_000L,
            (housesFinishedAt - startedAt) / 1_000_000L
        );
    }

    private static Map<String, String> resolveTownHouseStructures(
        SettlementPlan settlement, List<TownPlot> houses
    ) {
        Map<String, String> structures = new LinkedHashMap<>();
        int variant = 0;
        for (TownPlot house : houses) {
            String structure = house.structure();
            if (structure == null || structure.isBlank()) {
                int paletteIndex = Math.min(
                    settlement.basicBuildings().size() - 1,
                    variant % Math.max(1, settlement.basicBuildings().size())
                );
                structure = settlement.basicBuildings().get(paletteIndex);
            }
            structures.put(house.id(), structure);
            variant++;
        }
        return Map.copyOf(structures);
    }

    private static void collectConfiguredRoadColumns(
        Set<Long> columns, Point start, Point end, int width
    ) {
        collectConfiguredRoadColumns(columns, start, end, width, false);
    }

    private static void collectConfiguredRoadColumns(
        Set<Long> columns, Point start, Point end, int width,
        boolean clipBeyondEnd
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
                    if (clipBeyondEnd && TownAccessRoadGeometry.isBeyondEnd(
                        start.x(), start.z(), end.x(), end.z(),
                        x + offsetX, z + offsetZ
                    )) {
                        continue;
                    }
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
        if (settlement.roadLayoutTemplate().equals("cross")) {
            for (int direction : initialDirections) {
                queue.add(new TownConnector(0, 0, direction, 0));
            }
        }
        Set<String> occupiedRoad = new HashSet<>();
        occupiedRoad.add("0,0");
        List<TownRoad> roads = new ArrayList<>();
        int templateSpan = settlement.townRadiusCells() >= 7 ? 96 : 64;
        switch (settlement.roadLayoutTemplate()) {
            case "grid" -> {
                roads.add(new TownRoad(-32, -templateSpan, -32, templateSpan));
                roads.add(new TownRoad(32, -templateSpan, 32, templateSpan));
                roads.add(new TownRoad(-templateSpan, -32, templateSpan, -32));
                roads.add(new TownRoad(-templateSpan, 32, templateSpan, 32));
            }
            case "spine" -> {
                roads.add(new TownRoad(-templateSpan, 0, templateSpan, 0));
                for (int offset : new int[] {-32, 0, 32}) {
                    roads.add(new TownRoad(offset, -64, offset, 64));
                }
            }
            case "ring" -> {
                int ring = settlement.townRadiusCells() >= 7 ? 48 : 32;
                roads.add(new TownRoad(-ring, -ring, ring, -ring));
                roads.add(new TownRoad(ring, -ring, ring, ring));
                roads.add(new TownRoad(ring, ring, -ring, ring));
                roads.add(new TownRoad(-ring, ring, -ring, -ring));
            }
            default -> { }
        }
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
        Map<String, List<TownRoad>> buildingAccessRoads = new LinkedHashMap<>();
        for (TownPlot plot : facilities.values()) {
            buildingAccessRoads.put(plot.id(), List.of(townPlotAccessRoad(plot)));
        }
        for (TownPlot plot : houses) {
            buildingAccessRoads.put(plot.id(), List.of(townPlotAccessRoad(plot)));
        }
        return new TownLayout(
            List.copyOf(roads), List.copyOf(accessRoads),
            Map.copyOf(buildingAccessRoads), Map.copyOf(facilities),
            List.copyOf(houses), List.of(), List.of()
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
            if (!id.startsWith("house_") && !id.contains("gym")
                && !facing.equals(facilityCanonicalEntranceFacing(id))) {
                continue;
            }
            String rotation = id.startsWith("house_") || id.contains("gym") ? switch (facing) {
                case "east" -> "clockwise_90";
                case "south" -> "clockwise_180";
                case "west" -> "counterclockwise_90";
                default -> "none";
            } : "none";
            TownPlot candidate = new TownPlot(
                centerX - width / 2.0D, centerZ - depth / 2.0D,
                width, depth, id, null, rotation,
                (int) Math.round(alongX), (int) Math.round(alongZ), facing
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
            case "normal" -> new double[] {0.15D, 0.32D, 0.50D, 0.68D, 0.85D};
            default -> new double[] {0.06D, 0.17D, 0.28D, 0.39D, 0.50D, 0.61D, 0.72D, 0.83D, 0.94D};
        };
    }

    private static double buildingDensityGap(String density) {
        return switch (density) {
            case "sparse" -> 8.0D;
            case "dense" -> 1.0D;
            case "packed" -> 0.0D;
            case "normal" -> 4.0D;
            default -> 0.0D;
        };
    }

    private static double buildingDensityMultiplier(String density) {
        return switch (density) {
            case "sparse" -> 0.7D;
            case "dense" -> 1.4D;
            case "packed" -> 1.8D;
            case "normal" -> 1.0D;
            default -> 1.8D;
        };
    }

    private static TownRoad townPlotAccessRoad(TownPlot plot) {
        return townPlotAccessRoad(plot, plot.structure());
    }

    private static TownRoad townPlotAccessRoad(
        TownPlot plot, String structure
    ) {
        int x = (int) Math.round(plot.x());
        int z = (int) Math.round(plot.z());
        String facing = plot.entranceFacing();
        BlockPos doorOffset = structure == null ? null
            : BuildingRuntimeSystem.exteriorDoorApproachOffset(
                structure, plot.rotation()
            );
        BlockPoint templateOrigin = rotatedTemplateOrigin(
            x, 0, z, plot.width(), plot.depth(), plot.rotation()
        );
        Point authoredEntrance = doorOffset != null
            ? new Point(
                templateOrigin.x() + doorOffset.getX(),
                templateOrigin.z() + doorOffset.getZ()
            )
            : plot.id().equals("facility_pokemon_center")
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
        Point entrance = projectEntranceOutsideTemplate(
            plot, authoredEntrance, facing
        );
        return new TownRoad(
            plot.roadConnectionX(), plot.roadConnectionZ(), entrance.x(), entrance.z()
        );
    }

    private static Point projectEntranceOutsideTemplate(
        TownPlot plot, Point authoredEntrance, String preferredFacing
    ) {
        int minX = (int) Math.round(plot.x());
        int minZ = (int) Math.round(plot.z());
        boolean quarterTurn = plot.rotation().equals("clockwise_90")
            || plot.rotation().equals("counterclockwise_90");
        int placedWidth = quarterTurn ? plot.depth() : plot.width();
        int placedDepth = quarterTurn ? plot.width() : plot.depth();
        int maxX = minX + placedWidth - 1;
        int maxZ = minZ + placedDepth - 1;
        if (authoredEntrance.x() < minX || authoredEntrance.x() > maxX
            || authoredEntrance.z() < minZ || authoredEntrance.z() > maxZ) {
            return authoredEntrance;
        }
        return switch (preferredFacing) {
            case "east" -> new Point(maxX + 1, authoredEntrance.z());
            case "south" -> new Point(authoredEntrance.x(), maxZ + 1);
            case "west" -> new Point(minX - 1, authoredEntrance.z());
            default -> new Point(authoredEntrance.x(), minZ - 1);
        };
    }

    private static List<TownRoad> townPlotAccessRoads(
        TownPlot plot, String structure
    ) {
        TownRoad direct = townPlotAccessRoad(plot, structure);
        if (direct.x1() == direct.x2() || direct.z1() == direct.z2()) {
            return List.of(direct);
        }
        boolean entranceFacesEastOrWest = switch (plot.rotation()) {
            case "clockwise_90", "counterclockwise_90" -> true;
            default -> false;
        };
        Point corner = new Point(
            TownAccessRoadGeometry.cornerX(
                entranceFacesEastOrWest, direct.x1(), direct.x2()
            ),
            TownAccessRoadGeometry.cornerZ(
                entranceFacesEastOrWest, direct.z1(), direct.z2()
            )
        );
        return List.of(
            new TownRoad(direct.x1(), direct.z1(), corner.x(), corner.z()),
            new TownRoad(corner.x(), corner.z(), direct.x2(), direct.z2())
        );
    }

    private static TownRoad townBuildingEntranceRoad(
        TownLayout layout, TownPlot plot
    ) {
        List<TownRoad> roads = layout.buildingAccessRoads().get(plot.id());
        if (roads != null && !roads.isEmpty()) {
            return roads.get(roads.size() - 1);
        }
        return new TownRoad(
            plot.roadConnectionX(), plot.roadConnectionZ(),
            plot.roadConnectionX(), plot.roadConnectionZ()
        );
    }

    private static String facilityCanonicalEntranceFacing(String facilityId) {
        return switch (facilityId) {
            case "facility_pokemon_center" -> "west";
            case "facility_pokemart" -> "east";
            case "facility_department_store" -> "north";
            default -> facilityId.contains("gym") ? "south" : "north";
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

    private static BlockPoint rotatedTemplateOffset(
        BlockPoint offset, int width, int depth, String rotation
    ) {
        return switch (rotation) {
            case "clockwise_90" -> new BlockPoint(
                depth - 1 - offset.z(), offset.y(), offset.x()
            );
            case "clockwise_180" -> new BlockPoint(
                width - 1 - offset.x(), offset.y(), depth - 1 - offset.z()
            );
            case "counterclockwise_90" -> new BlockPoint(
                offset.z(), offset.y(), width - 1 - offset.x()
            );
            default -> offset;
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
        for (FacilityPlacement facility : settlement.facilities()) {
            String rotation = facilityRuntimeRotation(settlement, facility);
            BlockPoint referencePosition;
            if (facility.mode().equals("instanced_entry")) {
                referencePosition = facility.instanceOrigin();
            } else if (facility.mode().equals("direct_template")
                || facility.mode().equals("placeholder")) {
                referencePosition = resolveDirectFacilityPosition(level, settlement, facility);
            } else {
                LOGGER.error("Unknown facility placement mode: {}", facility.mode());
                return false;
            }
            BlockPoint position = referencePosition == null || facility.mode().equals("placeholder")
                ? referencePosition
                : applyBuildingPlacementYOffset(facility.structure(), referencePosition);
            if (referencePosition != null && (facility.id().equals("special_district_building")
                || facility.id().startsWith("facility_")
                || facility.id().contains("gym"))) {
                prepareSpecialDistrict(level, facility, referencePosition, rotation);
            }
            BlockPoint placedOrigin = position == null ? null
                : facility.mode().equals("direct_template")
                    ? facilityPlacementOrigin(level, facility, position, rotation)
                    : position;
            boolean placed = position != null && (facility.mode().equals("placeholder")
                ? placeFacilityPlaceholder(level, facility, position)
                : facility.mode().equals("direct_template")
                    ? placeFacilityTemplate(level, facility, position, rotation)
                    : placeTemplate(level, facility.structure(), position));
            if (!placed) {
                LOGGER.error(
                    "Facility placement failed for {} / {} at {}",
                    settlement.id(), facility.id(), position
                );
                return false;
            }
            BuildingRuntimeSystem.onStructurePlaced(
                level, facility.structure(), placedOrigin, rotation,
                buildingEventSpaceId(settlement.id(), facility.id()),
                isDepartmentStoreFacility(facility.id())
                    ? settlement.vendorAssignments() : null
            );
            if (facility.mode().equals("direct_template")) {
                if (!placeFacilityJigsawDecorations(level, facility, position, rotation)) {
                    return false;
                }
                cleanupFacilityTemplateMarkers(level, facility.structure(), position, rotation);
                if (!placeFacilityWorkers(level, settlement, facility, position, rotation)) {
                    return false;
                }
            }
            if (facility.mode().equals("direct_template")
                && facility.id().contains("gym")) {
                GymInteriorSystem.prepareExterior(
                    level, settlement.id(), position, rotation
                );
            }
        }
        return true;
    }

    private static String buildingEventSpaceId(
        String settlementId, String facilityId
    ) {
        int separator = settlementId.indexOf(':');
        String namespace = separator < 0 ? "cobbleventure"
            : settlementId.substring(0, separator);
        String path = separator < 0 ? settlementId : settlementId.substring(separator + 1);
        if (path.startsWith("settlement/")) {
            path = path.substring("settlement/".length());
        }
        return namespace + ":building/" + path + "/" + facilityId;
    }

    private static int facilityApproachRoadWidth(
        String facilityId, int defaultWidth
    ) {
        if (isDepartmentStoreFacility(facilityId)) {
            return 3;
        }
        if (facilityId.contains("gym")) {
            return 5;
        }
        return defaultWidth;
    }

    private static void prepareExistingGymExteriors(
        ServerLevel level, Map<String, SettlementPlan> settlements
    ) {
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled() || !data.isSettlementGenerated(settlement.id())) {
                continue;
            }
            for (FacilityPlacement facility : settlement.facilities()) {
                if (!facility.mode().equals("direct_template")
                    || !facility.id().contains("gym")) {
                    continue;
                }
                BlockPoint resolved = resolveDirectFacilityPosition(level, settlement, facility);
                BlockPoint position = resolved == null ? null
                    : applyBuildingPlacementYOffset(facility.structure(), resolved);
                if (position != null) {
                    String rotation = facilityRuntimeRotation(settlement, facility);
                    GymInteriorSystem.prepareExterior(
                        level, settlement.id(), position, rotation
                    );
                }
            }
        }
    }

    private static void prepareExistingBuildingRuntime(
        ServerLevel level, Map<String, SettlementPlan> settlements
    ) {
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled() || !data.isSettlementGenerated(settlement.id())) {
                continue;
            }
            TownLayout layout = generateTownLayout(settlement);
            Map<String, String> houseStructures = resolveTownHouseStructures(
                settlement, layout.houses()
            );
            for (TownPlot house : layout.houses()) {
                TownTemplatePlacement placement = townHouseRuntimePlacement(
                    level, settlement, house, houseStructures.get(house.id())
                );
                BuildingRuntimeSystem.onStructurePlaced(
                    level, placement.structure(), placement.position(), placement.rotation()
                );
            }
            for (FacilityPlacement facility : settlement.facilities()) {
                BlockPoint resolved = facility.mode().equals("instanced_entry")
                    ? facility.instanceOrigin()
                    : resolveDirectFacilityPosition(level, settlement, facility);
                BlockPoint position = resolved == null ? null
                    : applyBuildingPlacementYOffset(facility.structure(), resolved);
                if (position != null) {
                    String rotation = facilityRuntimeRotation(settlement, facility);
                    BlockPoint placedOrigin = facility.mode().equals("direct_template")
                        ? facilityPlacementOrigin(level, facility, position, rotation)
                        : position;
                    BuildingRuntimeSystem.onStructurePlaced(
                        level, facility.structure(), placedOrigin, rotation,
                        buildingEventSpaceId(settlement.id(), facility.id())
                    );
                }
            }
        }
    }

    private static void prepareExistingTownNpcs(
        ServerLevel level, Map<String, SettlementPlan> settlements
    ) {
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        for (SettlementPlan settlement : settlements.values()) {
            if (settlement.enabled() && data.isSettlementGenerated(settlement.id())) {
                placeAutomaticTownNpcs(level, settlement, data);
            }
        }
    }

    private static void placeAutomaticTownNpcs(
        ServerLevel level, SettlementPlan settlement, BootstrapSavedData data
    ) {
        if (settlement.automaticNpcPlacements().isEmpty()) {
            return;
        }
        TownLayout layout = generateTownLayout(settlement);
        Map<String, TownPlot> houses = layout.houses().stream().collect(Collectors.toMap(
            TownPlot::id, house -> house, (left, right) -> left, LinkedHashMap::new
        ));
        Map<String, String> houseStructures = resolveTownHouseStructures(
            settlement, layout.houses()
        );
        Map<String, Long> indoorCounts = settlement.automaticNpcPlacements().stream()
            .filter(placement -> placement.placementArea().equals("indoor")
                && placement.building() != null)
            .collect(Collectors.groupingBy(
                TownNpcPlacement::building, LinkedHashMap::new, Collectors.counting()
            ));
        Set<BlockPos> reservedPositions = new HashSet<>();
        int spawned = 0;
        for (int index = 0; index < settlement.automaticNpcPlacements().size(); index++) {
            TownNpcPlacement placement = settlement.automaticNpcPlacements().get(index);
            String legacySpawnKey = settlement.id() + "|" + settlement.center().x() + ","
                + settlement.center().z() + "|" + index + "|" + placement.npc();
            String spawnKey = placement.placementArea().equals("indoor")
                ? legacySpawnKey + "|interior-v2" : legacySpawnKey;
            ServerLevel spawnLevel = level;
            BlockPos position = null;
            float yaw = 0.0F;
            if (placement.placementArea().equals("indoor")) {
                TownPlot house = placement.building() == null
                    ? null : houses.get(placement.building());
                String structure = house == null ? null : houseStructures.get(house.id());
                TownTemplatePlacement housePlacement = house == null || structure == null
                    ? null : townHouseRuntimePlacement(level, settlement, house, structure);
                BuildingRuntimeSystem.SpawnDestination destination = housePlacement == null
                    ? null : BuildingRuntimeSystem.resolveAutomaticNpcSpawn(
                        level, housePlacement.structure(), housePlacement.position(),
                        housePlacement.rotation(), placement.slot()
                    );
                if (destination != null) {
                    spawnLevel = destination.level();
                    position = destination.position();
                    yaw = destination.yaw();
                    BuildingRuntimeSystem.showAutomaticNpcPresence(
                        level, housePlacement.structure(), housePlacement.position(),
                        housePlacement.rotation(),
                        indoorCounts.getOrDefault(placement.building(), 1L).intValue()
                    );
                }
                if (!data.hasSpawnedTownNpc(spawnKey)
                    && data.hasSpawnedTownNpc(legacySpawnKey) && house != null) {
                    BlockPos legacyPosition = indoorTownNpcPosition(
                        level, settlement, house, placement.slot()
                    );
                    if (legacyPosition != null) {
                        BuildingRuntimeSystem.removeNearbyEasyNpc(level, legacyPosition);
                    }
                }
            } else {
                position = outdoorTownNpcPosition(
                    level, settlement, layout, index, reservedPositions
                );
            }
            if (position == null) {
                LOGGER.warn(
                    "Automatic town NPC has no safe placement: settlement={}, npc={}, area={}, building={}",
                    settlement.id(), placement.npc(), placement.placementArea(), placement.building()
                );
                continue;
            }
            reservedPositions.add(position);
            if (data.hasSpawnedTownNpc(spawnKey)) {
                continue;
            }
            if (BuildingRuntimeSystem.spawnNpc(spawnLevel, placement.npc(), position, yaw)) {
                data.markTownNpcSpawned(spawnKey);
                spawned++;
            }
        }
        if (spawned > 0) {
            LOGGER.info(
                "Automatic town NPCs placed: settlement={}, spawned={}, configured={}",
                settlement.id(), spawned, settlement.automaticNpcPlacements().size()
            );
        }
    }

    private static TownTemplatePlacement townHouseRuntimePlacement(
        ServerLevel level, SettlementPlan settlement, TownPlot house, String structure
    ) {
        int x = settlement.center().x() + (int) Math.round(house.x());
        int z = settlement.center().z() + (int) Math.round(house.z());
        int roadX = settlement.center().x() + house.roadConnectionX();
        int roadZ = settlement.center().z() + house.roadConnectionZ();
        int groundY = runtimeRoadSurfaceY(level, roadX, roadZ);
        BlockPoint origin = rotatedTemplateOrigin(
            x, groundY + BuildingRuntimeSystem.placementYOffset(structure), z,
            house.width(), house.depth(), house.rotation()
        );
        return new TownTemplatePlacement(structure, origin, house.rotation());
    }

    private static BlockPos indoorTownNpcPosition(
        ServerLevel level, SettlementPlan settlement, TownPlot house, int slot
    ) {
        int minX = settlement.center().x() + (int) Math.floor(house.x()) + 1;
        int minZ = settlement.center().z() + (int) Math.floor(house.z()) + 1;
        int maxX = minX + Math.max(1, house.width() - 3);
        int maxZ = minZ + Math.max(1, house.depth() - 3);
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        List<Point> columns = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                columns.add(new Point(x, z));
            }
        }
        columns.sort(Comparator
            .comparingInt((Point point) -> Math.abs(point.x() - centerX)
                + Math.abs(point.z() - centerZ))
            .thenComparingInt(Point::x).thenComparingInt(Point::z));
        int skipped = Math.max(0, slot) * 4;
        for (Point column : columns) {
            int top = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x(), column.z()
            ) - 1;
            for (int floorY = top - 2; floorY >= top - 16; floorY--) {
                BlockPos floor = new BlockPos(column.x(), floorY, column.z());
                BlockPos feet = floor.above();
                if (!supportsTeleport(level, floor, level.getBlockState(floor))
                    || !isOpenForTeleport(level, feet)
                    || !isOpenForTeleport(level, feet.above())) {
                    continue;
                }
                boolean covered = false;
                for (int roofY = floorY + 3; roofY <= top; roofY++) {
                    BlockPos roof = new BlockPos(column.x(), roofY, column.z());
                    if (!level.getBlockState(roof).getCollisionShape(level, roof).isEmpty()) {
                        covered = true;
                        break;
                    }
                }
                if (covered && skipped-- <= 0) {
                    return feet;
                }
            }
        }
        return null;
    }

    private static BlockPos outdoorTownNpcPosition(
        ServerLevel level, SettlementPlan settlement, TownLayout layout, int npcIndex,
        Set<BlockPos> reservedPositions
    ) {
        if (layout.roads().isEmpty()) {
            return safeTeleportPosition(level, settlement.center().x(), settlement.center().z());
        }
        int attempts = Math.max(12, layout.roads().size() * 4);
        for (int attempt = 0; attempt < attempts; attempt++) {
            TownRoad road = layout.roads().get(Math.floorMod(npcIndex + attempt, layout.roads().size()));
            double ratio = 0.25D + 0.25D * Math.floorMod(npcIndex + attempt, 3);
            int x = settlement.center().x() + (int) Math.round(
                road.x1() + (road.x2() - road.x1()) * ratio
            );
            int z = settlement.center().z() + (int) Math.round(
                road.z1() + (road.z2() - road.z1()) * ratio
            );
            int side = ((npcIndex + attempt) & 1) == 0 ? 1 : -1;
            int offset = settlement.roadProfile().width() / 2 + 2
                + attempt / Math.max(1, layout.roads().size());
            if (road.x1() == road.x2()) {
                x += side * offset;
            } else {
                z += side * offset;
            }
            if (insideAnyTownPlot(settlement, layout, x, z)) {
                continue;
            }
            if (isNearTownNpcEntrance(settlement, layout, x, z)) {
                continue;
            }
            BlockPos position = safeTeleportPosition(level, x, z);
            if (position != null && reservedPositions.stream().noneMatch(reserved -> {
                int deltaX = reserved.getX() - position.getX();
                int deltaZ = reserved.getZ() - position.getZ();
                return deltaX * deltaX + deltaZ * deltaZ < 9;
            })) {
                return position;
            }
        }
        return null;
    }

    private static boolean isNearTownNpcEntrance(
        SettlementPlan settlement, TownLayout layout, int x, int z
    ) {
        int clearanceSquared = TOWN_NPC_ENTRANCE_CLEARANCE
            * TOWN_NPC_ENTRANCE_CLEARANCE;
        for (Point exit : layout.externalExits()) {
            int dx = x - settlement.center().x() - exit.x();
            int dz = z - settlement.center().z() - exit.z();
            if (dx * dx + dz * dz < clearanceSquared) return true;
        }
        for (List<TownRoad> roads : layout.buildingAccessRoads().values()) {
            for (TownRoad road : roads) {
                int dx = x - settlement.center().x() - road.x2();
                int dz = z - settlement.center().z() - road.z2();
                if (dx * dx + dz * dz < clearanceSquared) return true;
            }
        }
        return false;
    }

    private static boolean insideAnyTownPlot(
        SettlementPlan settlement, TownLayout layout, int x, int z
    ) {
        int localX = x - settlement.center().x();
        int localZ = z - settlement.center().z();
        return Stream.concat(layout.houses().stream(), layout.facilities().values().stream())
            .anyMatch(plot -> localX >= Math.floor(plot.x()) - 1
                && localX <= Math.ceil(plot.x() + plot.width()) + 1
                && localZ >= Math.floor(plot.z()) - 1
                && localZ <= Math.ceil(plot.z() + plot.depth()) + 1);
    }

    private static void refreshExistingConfiguredVendors(
        ServerLevel level, Map<String, SettlementPlan> settlements
    ) {
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        int refreshed = 0;
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled() || !data.isSettlementGenerated(settlement.id())) {
                continue;
            }
            for (FacilityPlacement facility : settlement.facilities()) {
                if (!isPokemartFacility(facility.id())
                    && !isDepartmentStoreFacility(facility.id())) {
                    continue;
                }
                BlockPoint resolved = facility.mode().equals("instanced_entry")
                    ? facility.instanceOrigin()
                    : resolveDirectFacilityPosition(level, settlement, facility);
                BlockPoint origin = resolved == null ? null
                    : applyBuildingPlacementYOffset(facility.structure(), resolved);
                if (origin == null) {
                    continue;
                }
                int extent = Math.max(facility.footprintWidth(), facility.footprintDepth());
                int cleanupMargin = 12;
                AABB facilityBounds = new AABB(
                    origin.x() - cleanupMargin,
                    origin.y() - 4,
                    origin.z() - cleanupMargin,
                    origin.x() + extent + cleanupMargin,
                    origin.y() + Math.max(16, facility.footprintHeight()) + 4,
                    origin.z() + extent + cleanupMargin
                );
                for (Entity entity : level.getEntities(
                    (Entity) null, facilityBounds, CobbleventureBootstrap::isConfiguredMerchant
                )) {
                    entity.discard();
                }
                for (FacilityWorkerPlacement worker : facilityWorkers(
                    level, facility, settlement.vendorAssignments(), settlement.vendorUnits()
                )) {
                    if (!hasConfiguredVendor(level, worker.vendorUnitId())) {
                        continue;
                    }
                    BlockPoint position = origin.plus(worker.offset());
                    BlockPos blockPosition = new BlockPos(position.x(), position.y(), position.z());
                    level.getChunkAt(blockPosition);
                    if (spawnConfiguredVendor(level, worker.vendorUnitId(), position)) {
                        refreshed++;
                    }
                }
            }
        }
        if (refreshed > 0) {
            LOGGER.info("Configured shop vendors refreshed from economy catalog: {}", refreshed);
        }
    }

    private static boolean isConfiguredMerchant(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(
            ResourceLocation.fromNamespaceAndPath("cobbledollars", "cobble_merchant")
        );
    }

    private static void cleanupFacilityTemplateMarkers(
        ServerLevel level, String structure, BlockPoint origin
    ) {
        cleanupFacilityTemplateMarkers(level, structure, origin, "none");
    }

    private static void cleanupFacilityTemplateMarkers(
        ServerLevel level, String structure, BlockPoint origin, String rotation
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return;
        var size = template.get().getSize();
        boolean quarterTurn = rotation.equals("clockwise_90")
            || rotation.equals("counterclockwise_90");
        int scanWidth = quarterTurn ? size.getZ() : size.getX();
        int scanDepth = quarterTurn ? size.getX() : size.getZ();
        for (int x = 0; x < scanWidth; x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < scanDepth; z++) {
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

    private static boolean placeFacilityJigsawDecorations(
        ServerLevel level, FacilityPlacement facility, BlockPoint origin
    ) {
        return placeFacilityJigsawDecorations(level, facility, origin, "none");
    }

    private static boolean placeFacilityJigsawDecorations(
        ServerLevel level, FacilityPlacement facility, BlockPoint origin,
        String rotation
    ) {
        if (!facility.id().equals("facility_pokemon_center")) {
            return true;
        }
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId == null) {
            return false;
        }
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            return false;
        }
        List<String> berryStructures = new ArrayList<>();
        for (int variant = 1; variant <= BCA_BERRY_VARIANT_LIMIT; variant++) {
            String candidate = "bca:general/berries/berry_" + variant;
            ResourceLocation candidateId = ResourceLocation.tryParse(candidate);
            if (candidateId != null
                && level.getStructureManager().get(candidateId).isPresent()) {
                berryStructures.add(candidate);
            }
        }
        if (berryStructures.isEmpty()) {
            LOGGER.warn("No BCA berry decoration templates are available");
            return true;
        }
        var size = template.get().getSize();
        boolean quarterTurn = rotation.equals("clockwise_90")
            || rotation.equals("counterclockwise_90");
        int scanWidth = quarterTurn ? size.getZ() : size.getX();
        int scanDepth = quarterTurn ? size.getX() : size.getZ();
        int placed = 0;
        int skipped = 0;
        for (int x = 0; x < scanWidth; x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < scanDepth; z++) {
                    BlockPos markerPosition = new BlockPos(
                        origin.x() + x, origin.y() + y, origin.z() + z
                    );
                    if (!(level.getBlockEntity(markerPosition) instanceof JigsawBlockEntity jigsaw)
                        || !BCA_BERRY_TARGET.equals(jigsaw.getTarget())) {
                        continue;
                    }
                    int variantIndex = Math.floorMod(
                        level.getSeed() ^ markerPosition.asLong(), berryStructures.size()
                    );
                    BlockPoint berryOrigin = new BlockPoint(
                        markerPosition.getX(), markerPosition.getY() + 1,
                        markerPosition.getZ()
                    );
                    String placedStructure = null;
                    for (int attempt = 0; attempt < berryStructures.size(); attempt++) {
                        String candidate = berryStructures.get(
                            (variantIndex + attempt) % berryStructures.size()
                        );
                        if (placeOptionalTemplate(level, candidate, berryOrigin)) {
                            placedStructure = candidate;
                            break;
                        }
                    }
                    if (placedStructure == null) {
                        skipped++;
                        LOGGER.warn(
                            "Pokemon Center berry marker skipped after all templates failed: position={}",
                            berryOrigin
                        );
                        continue;
                    }
                    cleanupFacilityTemplateMarkers(level, placedStructure, berryOrigin);
                    placed++;
                }
            }
        }
        if (placed == 0) {
            LOGGER.warn(
                "Pokemon Center berry decorations were not placed: structure={}, skipped={}",
                facility.structure(), skipped
            );
        }
        LOGGER.info(
            "Pokemon Center berry decorations completed: placed={}, skipped={}",
            placed, skipped
        );
        return true;
    }

    private static boolean placeFacilityWorkers(
        ServerLevel level, SettlementPlan settlement,
        FacilityPlacement facility, BlockPoint origin
    ) {
        return placeFacilityWorkers(level, settlement, facility, origin, "none");
    }

    private static boolean placeFacilityWorkers(
        ServerLevel level, SettlementPlan settlement,
        FacilityPlacement facility, BlockPoint origin, String rotation
    ) {
        List<ShopVendorAssignment> assignments = settlement == null ? null : settlement.vendorAssignments();
        List<String> configuredVendors = settlement == null ? null : settlement.vendorUnits();
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        var template = structureId == null
            ? Optional.<StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        int width = template.map(value -> value.getSize().getX())
            .orElse(facility.footprintWidth());
        int depth = template.map(value -> value.getSize().getZ())
            .orElse(facility.footprintDepth());
        for (FacilityWorkerPlacement worker : facilityWorkers(
            level, facility, assignments, configuredVendors
        )) {
            BlockPoint position = origin.plus(rotatedTemplateOffset(
                worker.offset(), width, depth, rotation
            ));
            if (!spawnConfiguredVendor(level, worker.vendorUnitId(), position)
                && !placeTemplate(level, worker.structure(), position)) {
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

    private static List<FacilityWorkerPlacement> facilityWorkers(
        ServerLevel level, FacilityPlacement facility,
        List<ShopVendorAssignment> assignments, List<String> configuredVendors
    ) {
        boolean usesAuthoredWorkerSlots = isPokemartFacility(facility.id())
            || isDepartmentStoreFacility(facility.id())
                && !facility.structure().equals("bca:default/centers/center_department_store");
        if (usesAuthoredWorkerSlots) {
            Map<String, BlockPoint> authoredSlots = facilityWorkerSlots(
                level, facility.structure()
            );
            List<ShopVendorAssignment> resolvedAssignments = assignments != null
                ? assignments
                : configuredVendors != null
                    ? IntStream.range(0, configuredVendors.size())
                        .mapToObj(index -> new ShopVendorAssignment(
                            facilitySlotId(facility.id(), index), configuredVendors.get(index)
                        )).toList()
                    : isPokemartFacility(facility.id())
                        ? List.of(new ShopVendorAssignment(
                            "counter", "bca:pokemart_shopkeeper"
                        ))
                        : List.of();
            List<FacilityWorkerPlacement> selected = new ArrayList<>();
            for (ShopVendorAssignment assignment : resolvedAssignments) {
                BlockPoint slot = authoredSlots.get(assignment.slotId());
                if (slot == null) {
                    LOGGER.info(
                        "Facility vendor awaits an authored NPC anchor: structure={}, slot={}, vendor={}",
                        facility.structure(), assignment.slotId(), assignment.vendorUnit()
                    );
                    continue;
                }
                selected.add(new FacilityWorkerPlacement(
                    assignment.vendorUnit(), vendorStructure(assignment.vendorUnit()), slot
                ));
            }
            return List.copyOf(selected);
        }
        return legacyFacilityWorkers(
            facility.id(), assignments, configuredVendors
        );
    }

    private static Map<String, BlockPoint> facilityWorkerSlots(
        ServerLevel level, String structure
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) {
            return Map.of();
        }
        ResourceLocation metadataId = ResourceLocation.fromNamespaceAndPath(
            structureId.getNamespace(),
            "structure_metadata/" + structureId.getPath() + ".structure.json"
        );
        Resource resource = level.getServer().getResourceManager()
            .getResource(metadataId).orElse(null);
        if (resource == null) {
            return Map.of();
        }
        Map<String, BlockPoint> slots = new LinkedHashMap<>();
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("anchors")) {
                return Map.of();
            }
            for (JsonElement element : root.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                if (!anchor.has("type")
                    || !anchor.get("type").getAsString().equals("npc_position")) {
                    continue;
                }
                String label = anchor.has("label")
                    ? anchor.get("label").getAsString()
                    : anchor.has("id") ? anchor.get("id").getAsString() : "";
                if (label.isBlank() || !anchor.has("position")) {
                    continue;
                }
                JsonArray position = anchor.getAsJsonArray("position");
                BlockPoint previous = slots.putIfAbsent(label, new BlockPoint(
                    position.get(0).getAsInt(), position.get(1).getAsInt(),
                    position.get(2).getAsInt()
                ));
                if (previous != null) {
                    throw new IllegalStateException(
                        "Duplicate department store NPC anchor: " + label
                    );
                }
            }
            return Map.copyOf(slots);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException(
                "Invalid department store metadata: " + metadataId, error
            );
        }
    }

    private static List<FacilityWorkerPlacement> legacyFacilityWorkers(
        String facilityId, List<ShopVendorAssignment> assignments,
        List<String> configuredVendors
    ) {
        List<FacilityWorkerPlacement> defaults = switch (facilityId) {
            // Pokemon Center staff are authored through structure metadata and
            // BuildingRuntimeSystem fixed-NPC anchors. Do not reintroduce the
            // legacy BCA nurse template at a hard-coded local coordinate.
            case "facility_pokemon_center" -> List.of();
            // Pokemart vendors must use the structure metadata's `counter`
            // NPC anchor. A template-local coordinate is not a valid fallback.
            case "facility_pokemart" -> List.of();
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
        if (assignments == null && configuredVendors == null
            || (!facilityId.equals("facility_pokemart")
                && !facilityId.equals("facility_department_store"))) {
            return defaults;
        }
        List<BlockPoint> slots = defaults.stream()
            .map(FacilityWorkerPlacement::offset).toList();
        if (slots.isEmpty()) {
            return List.of();
        }
        List<FacilityWorkerPlacement> selected = new ArrayList<>();
        List<ShopVendorAssignment> resolvedAssignments = assignments != null
            ? assignments
            : IntStream.range(0, configuredVendors.size())
                .mapToObj(index -> new ShopVendorAssignment(
                    facilitySlotId(facilityId, index), configuredVendors.get(index)
                )).toList();
        for (int index = 0; index < resolvedAssignments.size(); index++) {
            ShopVendorAssignment assignment = resolvedAssignments.get(index);
            String structure = vendorStructure(assignment.vendorUnit());
            BlockPoint slot = slotOffset(facilityId, assignment.slotId(), slots, index);
            selected.add(new FacilityWorkerPlacement(
                assignment.vendorUnit(), structure, slot
            ));
        }
        return List.copyOf(selected);
    }

    private static String vendorStructure(String vendorUnitId) {
        ResourceLocation id = ResourceLocation.tryParse(vendorUnitId);
        if (id != null && id.getNamespace().equals("bca") && !id.getPath().contains("/")) {
            return "bca:stores/store_workers/" + id.getPath();
        }
        return vendorUnitId;
    }

    private static String facilitySlotId(String facilityId, int index) {
        if (facilityId.equals("facility_pokemart")) return "counter";
        List<String> slots = List.of(
            "1f_left_a", "1f_left_b", "1f_center_a", "1f_center_b",
            "1f_center_c", "1f_right", "2f_left", "2f_center_a",
            "2f_center_b", "2f_center_c", "2f_right_a", "2f_right_b",
            "3f_left", "3f_center"
        );
        return slots.get(Math.min(index, slots.size() - 1));
    }

    private static BlockPoint slotOffset(
        String facilityId, String slotId, List<BlockPoint> offsets, int fallbackIndex
    ) {
        if (facilityId.equals("facility_pokemart")) return offsets.getFirst();
        List<String> slots = IntStream.range(0, offsets.size())
            .mapToObj(index -> facilitySlotId(facilityId, index)).toList();
        int index = slots.indexOf(slotId);
        return offsets.get(index < 0 ? Math.min(fallbackIndex, offsets.size() - 1) : index);
    }

    static boolean spawnConfiguredVendor(
        ServerLevel level, String vendorUnitId, BlockPoint position
    ) {
        return spawnConfiguredVendor(level, vendorUnitId, position, 0.0F);
    }

    static boolean spawnConfiguredVendor(
        ServerLevel level, String vendorUnitId, BlockPoint position, float yaw
    ) {
        try {
            JsonObject definition = configuredVendorDefinition(level, vendorUnitId);
            if (definition == null) return false;
            CompoundTag merchant = new CompoundTag();
            String exportLanguage = configuredExportLanguage(level);
            String displayName = localizedString(definition, "display_name", exportLanguage)
                .replace("\\", "\\\\").replace("\"", "\\\"");
            merchant.putString("CustomName", "\"" + displayName + "\"");
            merchant.putBoolean("PersistenceRequired", true);
            // Facility vendors are counter clerks, not free-roaming villagers.
            // Keep both the serialized flag and the live entity state fixed so
            // custom merchant implementations cannot walk out of the shop.
            merchant.putBoolean("NoAI", true);
            UUID merchantId = UUID.randomUUID();
            merchant.putUUID("UUID", merchantId);
            ListTag shop = new ListTag();
            for (JsonElement categoryElement : definition.getAsJsonArray("categories")) {
                JsonObject categoryJson = categoryElement.getAsJsonObject();
                CompoundTag category = new CompoundTag();
                category.putString("Category", localizedString(categoryJson, "name", exportLanguage));
                ListTag offers = new ListTag();
                for (JsonElement offerElement : categoryJson.getAsJsonArray("offers")) {
                    JsonObject offerJson = offerElement.getAsJsonObject();
                    CompoundTag offer = new CompoundTag();
                    CompoundTag item = new CompoundTag();
                    item.putString("id", requiredString(offerJson, "item"));
                    item.putInt("count", offerJson.get("count").getAsInt());
                    offer.put("Item", item);
                    offer.putString("Price", requiredString(offerJson, "price"));
                    offers.add(offer);
                }
                category.put("Offers", offers);
                shop.add(category);
            }
            merchant.put("CobbleMerchantShop", shop);
            CompoundTag villagerData = new CompoundTag();
            villagerData.putString("profession", "cobbledollars:cobble_merchant");
            villagerData.putString("type", "minecraft:plains");
            villagerData.putInt("level", 1);
            merchant.put("VillagerData", villagerData);
            String command = "summon cobbledollars:cobble_merchant "
                + (position.x() + 0.5D) + " " + position.y() + " "
                + (position.z() + 0.5D) + " " + merchant;
            int result = level.getServer().getCommands().getDispatcher().execute(
                command, level.getServer().createCommandSourceStack()
                    .withLevel(level).withPermission(4).withSuppressedOutput()
            );
            Entity spawned = level.getEntity(merchantId);
            if (spawned instanceof Mob mob) {
                mob.setNoAi(true);
                mob.moveTo(mob.getX(), mob.getY(), mob.getZ(), yaw, 0.0F);
                mob.setYRot(yaw);
                mob.setYBodyRot(yaw);
                mob.setYHeadRot(yaw);
                applyNpcWorldFont(mob);
            } else if (result != 0) {
                LOGGER.warn(
                    "Configured merchant spawned but could not be fixed in place: {} at {}",
                    vendorUnitId, position
                );
            }
            return result != 0;
        } catch (IOException | CommandSyntaxException | RuntimeException error) {
            LOGGER.error("Configured merchant spawn failed: {} at {}", vendorUnitId, position, error);
            return false;
        }
    }

    static void applyNpcWorldFont(Entity entity) {
        Component customName = entity.getCustomName();
        if (customName != null) {
            entity.setCustomName(
                customName.copy().withStyle(style -> style.withFont(NPC_WORLD_FONT))
            );
        }
    }

    private static boolean usesNpcWorldFont(ResourceLocation entityType) {
        return entityType.getNamespace().equals("easy_npc")
            || entityType.equals(COBBLE_MERCHANT);
    }

    private static boolean isDepartmentStoreFacility(String facilityId) {
        return facilityId != null && facilityId.startsWith("facility_department_store");
    }

    private static boolean isPokemartFacility(String facilityId) {
        return facilityId != null && facilityId.startsWith("facility_pokemart");
    }

    private static boolean hasConfiguredVendor(ServerLevel level, String vendorUnitId) {
        try {
            return configuredVendorDefinition(level, vendorUnitId) != null;
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Configured merchant lookup failed: {}", vendorUnitId, error);
            return false;
        }
    }

    private static JsonObject configuredVendorDefinition(
        ServerLevel level, String vendorUnitId
    ) throws IOException {
        ResourceLocation catalogLocation = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "economy/catalog.json"
        );
        Resource resource = level.getServer().getResourceManager()
            .getResource(catalogLocation).orElse(null);
        if (resource == null) return null;
        try (Reader reader = resource.openAsReader()) {
            JsonObject catalog = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement element : catalog.getAsJsonArray("vendor_units")) {
                JsonObject candidate = element.getAsJsonObject();
                if (requiredString(candidate, "id").equals(vendorUnitId)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String configuredExportLanguage(ServerLevel level) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "economy/export-language.txt"
        );
        Resource resource = level.getServer().getResourceManager()
            .getResource(location).orElse(null);
        if (resource == null) return "ko_kr";
        try (Reader reader = resource.openAsReader()) {
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[32];
            for (int read; (read = reader.read(buffer)) >= 0;) {
                value.append(buffer, 0, read);
            }
            String language = value.toString().trim();
            return language.matches("[a-z]{2}_[a-z]{2}") ? language : "ko_kr";
        } catch (IOException error) {
            LOGGER.warn("Export language resource could not be read; using ko_kr", error);
            return "ko_kr";
        }
    }

    private static FacilityWorkerPlacement facilityWorker(
        String resource, int x, int y, int z
    ) {
        return new FacilityWorkerPlacement(
            "bca:" + resource, "bca:stores/store_workers/" + resource,
            new BlockPoint(x, y, z)
        );
    }

    private static void drawConfiguredRoad(
        ServerLevel level, Point start, Point end, RoadProfile profile,
        boolean townSurface, boolean useLoadedTerrain
    ) {
        drawConfiguredRoad(
            level, start, end, profile, townSurface, useLoadedTerrain, true
        );
    }

    private static void drawConfiguredRoad(
        ServerLevel level, Point start, Point end, RoadProfile profile,
        boolean townSurface, boolean useLoadedTerrain,
        boolean clearConnectedTrees
    ) {
        drawConfiguredRoad(
            level, start, end, profile, townSurface, useLoadedTerrain,
            clearConnectedTrees, false
        );
    }

    private static void drawConfiguredRoad(
        ServerLevel level, Point start, Point end, RoadProfile profile,
        boolean townSurface, boolean useLoadedTerrain,
        boolean clearConnectedTrees, boolean clipBeyondEnd
    ) {
        Set<Long> roadColumns = new HashSet<>();
        collectConfiguredRoadColumns(
            roadColumns, start, end, profile.width(), clipBeyondEnd
        );
        Map<Long, Integer> elevations = useLoadedTerrain
            ? loadedRoadElevations(level, roadColumns)
            : naturalRoadElevations(level, roadColumns);
        if (clearConnectedTrees) {
            clearTreesIntersectingRoad(level, roadColumns, elevations);
        }
        for (long key : roadColumns) {
            paintConfiguredRoadColumn(
                level, blockColumnX(key), blockColumnZ(key), profile.material(),
                elevations.get(key),
                configuredRoadStairDirection(key, roadColumns, elevations), true,
                townSurface
            );
        }
    }

    private static Map<Long, Integer> naturalRoadElevations(
        ServerLevel level, Set<Long> columns
    ) {
        Map<Long, Integer> heights = new HashMap<>();
        for (long key : columns) {
            heights.put(key, plannedTerrainGroundY(
                level, blockColumnX(key), blockColumnZ(key)
            ));
        }
        return heights;
    }

    private static Map<Long, Integer> loadedRoadElevations(
        ServerLevel level, Set<Long> columns
    ) {
        Map<Long, Integer> heights = new HashMap<>();
        for (long key : columns) {
            int x = blockColumnX(key);
            int z = blockColumnZ(key);
            heights.put(key, loadedRoadSurfaceY(level, x, z));
        }
        int corrected = 0;
        int maximumCorrection = 0;
        for (long key : columns) {
            int x = blockColumnX(key);
            int z = blockColumnZ(key);
            int loadedY = heights.get(key);
            if (!loadedRoadHeightNeedsVerification(level, x, loadedY, z, heights)) {
                continue;
            }
            int plannedY = plannedTerrainGroundY(level, x, z);
            if (loadedY <= plannedY + 2) {
                continue;
            }
            heights.put(key, plannedY);
            corrected++;
            maximumCorrection = Math.max(maximumCorrection, loadedY - plannedY);
        }
        if (corrected > 0) {
            LOGGER.info(
                "Corrected elevated road columns caused by surface obstructions: columns={}, maximumCorrection={}",
                corrected, maximumCorrection
            );
        }
        return heights;
    }

    private static int loadedRoadSurfaceY(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z
        ) - 1;
        BlockState surface = level.getBlockState(new BlockPos(x, surfaceY, z));
        return isConfiguredRoadStair(surface) || isConfiguredRoadSlab(surface)
            ? surfaceY - 1 : surfaceY;
    }

    /**
     * Resolves the road height again after nearby buildings may already exist. A roof or eave
     * above the authored door can become the heightmap surface, so large upward differences
     * must fall back to the terrain plan used when the facility was originally placed.
     */
    private static int runtimeRoadSurfaceY(ServerLevel level, int x, int z) {
        return FacilityRuntimeGround.correctedRoadSurfaceY(
            loadedRoadSurfaceY(level, x, z), plannedTerrainGroundY(level, x, z)
        );
    }

    private static boolean loadedRoadHeightNeedsVerification(
        ServerLevel level, int x, int surfaceY, int z,
        Map<Long, Integer> roadHeights
    ) {
        BlockState surface = level.getBlockState(new BlockPos(x, surfaceY, z));
        if (isNaturalTownTree(surface)
            || surface.is(Blocks.BEE_NEST) || surface.is(Blocks.BEEHIVE)) {
            return true;
        }
        if (!isNaturalRoadObstruction(surface)) {
            return true;
        }
        List<Integer> nearby = new ArrayList<>(8);
        for (int offsetX = -3; offsetX <= 3; offsetX += 3) {
            for (int offsetZ = -3; offsetZ <= 3; offsetZ += 3) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                Integer nearbyY = roadHeights.get(blockColumnKey(
                    x + offsetX, z + offsetZ
                ));
                if (nearbyY != null) {
                    nearby.add(nearbyY);
                }
            }
        }
        if (nearby.size() < 3) {
            return false;
        }
        nearby.sort(Integer::compareTo);
        return surfaceY > nearby.get(nearby.size() / 2) + 3;
    }

    private static Direction configuredRoadStairDirection(
        long key, Set<Long> columns, Map<Long, Integer> elevations
    ) {
        int current = elevations.get(key);
        int x = blockColumnX(key);
        int z = blockColumnZ(key);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            long adjacent = blockColumnKey(
                x + direction.getStepX(), z + direction.getStepZ()
            );
            if (columns.contains(adjacent) && elevations.get(adjacent) == current + 1) {
                return direction;
            }
        }
        return null;
    }

    private static boolean paintConfiguredRoadColumn(
        ServerLevel level, int x, int z, String material
    ) {
        int groundY = plannedTerrainGroundY(level, x, z);
        return paintConfiguredRoadColumn(
            level, x, z, material, groundY, null, false, false
        );
    }

    private static boolean paintConfiguredRoadColumn(
        ServerLevel level, int x, int z, String material,
        int baseY, Direction stairDirection,
        boolean carveNaturalTerrain, boolean townSurface
    ) {
        HexWorldPlan world = activeHexWorld;
        TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample != null && isAquatic(sample)) return false;
        if (world != null && isCaveMountainProtectedColumn(world, x, z)
            && !isCaveEntrancePassage(world, x, z)) {
            return false;
        }
        int groundY = baseY + (stairDirection == null ? 0 : 1);
        clearVegetationColumn(level, x, groundY, z, 32);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            BlockState current = level.getBlockState(new BlockPos(x, y, z));
            if (!current.isAir() && !current.canBeReplaced()
                && (!carveNaturalTerrain || !isNaturalRoadObstruction(current))) {
                return false;
            }
        }
        BlockState fullRoad = townSurface
            ? configuredTownRoadBlock(material, x, z)
            : configuredRoadBlock(material);
        BlockState road = stairDirection == null
            ? fullRoad : configuredRoadStair(material, fullRoad, stairDirection);
        if (stairDirection != null) {
            level.setBlock(new BlockPos(x, baseY, z), fullRoad, 2);
        }
        supportRoadColumn(level, x, groundY, z);
        level.setBlock(new BlockPos(x, groundY, z), road, 2);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return true;
    }

    private static boolean isNaturalRoadObstruction(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.DIRT)
            || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT)
            || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM)
            || state.is(Blocks.STONE)
            || state.is(Blocks.DEEPSLATE)
            || state.is(Blocks.ANDESITE)
            || state.is(Blocks.DIORITE)
            || state.is(Blocks.GRANITE)
            || state.is(Blocks.TUFF)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.SAND)
            || state.is(Blocks.RED_SAND)
            || state.is(Blocks.CLAY)
            || state.is(Blocks.SNOW_BLOCK)
            || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.MOSSY_COBBLESTONE)
            || state.is(Blocks.STONE_BRICKS)
            || state.is(Blocks.BRICKS)
            || state.is(Blocks.DIRT_PATH)
            || state.is(Blocks.PACKED_MUD)
            || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.POLISHED_DIORITE)
            || isConfiguredRoadSlab(state)
            || isConfiguredRoadStair(state);
    }

    private static BlockState configuredRoadBlock(String material) {
        return switch (material) {
            case "stone_bricks" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "bricks" -> Blocks.BRICKS.defaultBlockState();
            case "grass_path" -> Blocks.DIRT_PATH.defaultBlockState();
            case "gravel" -> Blocks.GRAVEL.defaultBlockState();
            case "packed_mud" -> Blocks.PACKED_MUD.defaultBlockState();
            case "sandstone" -> Blocks.SANDSTONE.defaultBlockState();
            case "snow" -> Blocks.POLISHED_DIORITE.defaultBlockState();
            default -> Blocks.COBBLESTONE.defaultBlockState();
        };
    }

    private static BlockState configuredTownRoadBlock(String material, int x, int z) {
        if (!material.equals("cobblestone")) {
            return configuredRoadBlock(material);
        }
        int pattern = Math.floorMod(
            x * 73_428_767 ^ z * 91_273_681 ^ (x + z) * 31, 100
        );
        if (pattern < 12) return Blocks.STONE_BRICKS.defaultBlockState();
        if (pattern < 22) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (pattern < 32) return Blocks.STONE.defaultBlockState();
        return Blocks.COBBLESTONE.defaultBlockState();
    }

    private static BlockState configuredRoadStair(
        String material, BlockState fullRoad, Direction direction
    ) {
        BlockState stair = fullRoad.is(Blocks.STONE_BRICKS)
            ? Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            : fullRoad.is(Blocks.MOSSY_COBBLESTONE)
                ? Blocks.MOSSY_COBBLESTONE_STAIRS.defaultBlockState()
                : fullRoad.is(Blocks.STONE)
                    ? Blocks.STONE_STAIRS.defaultBlockState()
                    : switch (material) {
                        case "bricks" -> Blocks.BRICK_STAIRS.defaultBlockState();
                        case "packed_mud" -> Blocks.MUD_BRICK_STAIRS.defaultBlockState();
                        case "sandstone" -> Blocks.SANDSTONE_STAIRS.defaultBlockState();
                        case "snow" -> Blocks.QUARTZ_STAIRS.defaultBlockState();
                        default -> Blocks.COBBLESTONE_STAIRS.defaultBlockState();
                    };
        return stair.setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
    }

    private static int plannedTerrainGroundY(ServerLevel level, int x, int z) {
        HexWorldPlan world = activeHexWorld;
        if (world != null && NativeWorldGeneration.usesNativeGenerator(
            level.getChunkSource().getGenerator()
        )) {
            return nativeTerrainColumn(world, x, z).groundY();
        }
        TerrainSample sample = world == null ? null : terrainAt(world, x + 0.5D, z + 0.5D);
        return sample == null
            ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
            : terrainGroundY(world, sample, x, z);
    }

    private static int clearTownChunkTrees(
        ServerLevel level, SettlementPlan settlement
    ) {
        Set<Long> chunks = townPreparationChunkKeys(settlement);
        HexWorldPlan world = activeHexWorld;
        boolean constrainToTownTerrain = usesAuthoredTownFootprint(world, settlement);
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int removed = 0;
        int skippedOutsideTownColumns = 0;
        for (long key : chunks) {
            int startX = ChunkPos.getX(key) << 4;
            int startZ = ChunkPos.getZ(key) << 4;
            for (int x = startX; x < startX + 16; x++) {
                for (int z = startZ; z < startZ + 16; z++) {
                    if (constrainToTownTerrain
                        && !isOwnedTownTerrain(world, settlement.id(), x, z)) {
                        skippedOutsideTownColumns++;
                        continue;
                    }
                    int topY = Math.min(
                        level.getMaxBuildHeight() - 1,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
                    );
                    int bottomY = Math.max(level.getMinBuildHeight(), topY - 48);
                    for (int y = topY; y >= bottomY; y--) {
                        position.set(x, y, z);
                        BlockState state = level.getBlockState(position);
                        if (state.isAir()) {
                            continue;
                        }
                        if (isNaturalTownTree(state)) {
                            level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                            removed++;
                            continue;
                        }
                        // Snow layers, grass and flowers can sit above or between a
                        // tree canopy. Preserve them, but keep scanning down until
                        // the first solid non-tree block so they do not hide a trunk.
                        if (state.getFluidState().isEmpty() && state.canBeReplaced()) {
                            continue;
                        }
                        break;
                    }
                }
            }
        }
        LOGGER.info(
            "Town chunk trees cleared: settlement={}, chunks={}, removedBlocks={}, "
                + "skippedOutsideTownColumns={}",
            settlement.id(), chunks.size(), removed, skippedOutsideTownColumns
        );
        return removed;
    }

    private static boolean usesAuthoredTownFootprint(
        HexWorldPlan world, SettlementPlan settlement
    ) {
        if (world == null) {
            return false;
        }
        HexSettlement authored = world.settlements().get(settlement.id());
        if (authored == null) {
            return false;
        }
        Point center = townFootprintWorldCenter(world.grid(), authored);
        return Math.abs(center.x() - settlement.center().x()) <= 1
            && Math.abs(center.z() - settlement.center().z()) <= 1;
    }

    private static boolean isOwnedTownTerrain(
        HexWorldPlan world, String settlementId, int x, int z
    ) {
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        return sample != null && sample.kind().equals("town")
            && sample.owner().equals(settlementId);
    }

    private static boolean isNaturalTownTree(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.SAPLINGS)
            || state.is(Blocks.BAMBOO) || state.is(Blocks.MUSHROOM_STEM)
            || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
            || state.is(Blocks.RED_MUSHROOM_BLOCK)
            || state.is(Blocks.BEE_NEST) || state.is(Blocks.BEEHIVE)
            || state.is(Blocks.MANGROVE_ROOTS)
            || state.is(Blocks.MUDDY_MANGROVE_ROOTS)
            || state.is(Blocks.VINE) || state.is(Blocks.COCOA);
    }

    private static boolean isNaturalVegetation(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS)
            || state.is(Blocks.BAMBOO) || state.is(Blocks.CACTUS)
            || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.BROWN_MUSHROOM)
            || state.is(Blocks.RED_MUSHROOM)
            || (state.getFluidState().isEmpty() && state.canBeReplaced());
    }

    private static void clearVegetationAroundPlot(
        ServerLevel level, int originX, int originZ,
        int width, int depth, int clearance
    ) {
        Set<Long> columns = new HashSet<>();
        collectVegetationColumnsAroundPlot(
            columns, originX, originZ, width, depth, clearance
        );
        clearVegetationColumns(level, columns);
    }

    private static void collectVegetationColumnsAroundPlot(
        Set<Long> columns, int originX, int originZ,
        int width, int depth, int clearance
    ) {
        for (int x = originX - clearance; x < originX + width + clearance; x++) {
            for (int z = originZ - clearance; z < originZ + depth + clearance; z++) {
                columns.add(blockColumnKey(x, z));
            }
        }
    }

    private static void clearVegetationColumns(ServerLevel level, Set<Long> columns) {
        for (long key : columns) {
            int x = blockColumnX(key);
            int z = blockColumnZ(key);
            clearVegetationColumn(level, x, plannedTerrainGroundY(level, x, z), z, 32);
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
            if (isNaturalVegetation(state)) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static void clearTreesIntersectingRoad(
        ServerLevel level, Set<Long> roadColumns, Map<Long, Integer> elevations
    ) {
        int removed = 0;
        for (long key : roadColumns) {
            int x = blockColumnX(key);
            int z = blockColumnZ(key);
            Integer configuredElevation = elevations.get(key);
            int groundY = configuredElevation != null
                ? configuredElevation
                : plannedTerrainGroundY(level, x, z);
            int top = Math.min(level.getMaxBuildHeight() - 1, groundY + 32);
            for (int y = groundY + 1; y <= top; y++) {
                BlockPos seed = new BlockPos(x, y, z);
                BlockState state = level.getBlockState(seed);
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                    removed += clearConnectedTree(level, seed, groundY);
                }
            }
        }
        if (removed > 0) {
            LOGGER.debug("Road vegetation cleanup removed {} connected tree blocks", removed);
        }
    }

    private static int clearConnectedTree(
        ServerLevel level, BlockPos seed, int roadGroundY
    ) {
        int minY = Math.max(level.getMinBuildHeight(), roadGroundY - 2);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, roadGroundY + 40);
        int horizontalLimit = 12;
        int blockLimit = 8192;
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<BlockPos> connected = new ArrayList<>();
        pending.add(seed);
        while (!pending.isEmpty() && connected.size() < blockLimit) {
            BlockPos position = pending.removeFirst();
            if (!visited.add(position.asLong())
                || position.getY() < minY || position.getY() > maxY
                || Math.abs(position.getX() - seed.getX()) > horizontalLimit
                || Math.abs(position.getZ() - seed.getZ()) > horizontalLimit) {
                continue;
            }
            BlockState state = level.getBlockState(position);
            if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
                continue;
            }
            connected.add(position.immutable());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) {
                            pending.add(position.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        for (BlockPos position : connected) {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
        }
        return connected.size();
    }

    private static void cleanupTownGenerationDebris(
        ServerLevel level, SettlementPlan settlement
    ) {
        Set<Long> chunks = townPreparationChunkKeys(settlement);
        if (chunks.isEmpty()) {
            return;
        }
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (long key : chunks) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            minChunkX = Math.min(minChunkX, chunkX);
            minChunkZ = Math.min(minChunkZ, chunkZ);
            maxChunkX = Math.max(maxChunkX, chunkX);
            maxChunkZ = Math.max(maxChunkZ, chunkZ);
        }
        AABB bounds = new AABB(
            minChunkX << 4, level.getMinBuildHeight(), minChunkZ << 4,
            (maxChunkX + 1) << 4, level.getMaxBuildHeight(), (maxChunkZ + 1) << 4
        );
        int removed = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (isNaturalGenerationDebris(item)) {
                item.discard();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info(
                "Town generation debris cleaned: settlement={}, itemEntities={}",
                settlement.id(), removed
            );
        }
    }

    private static void runScheduledTownDebrisCleanup(ServerLevel level, long gameTime) {
        if (scheduledTownDebrisCleanup.isEmpty()) {
            return;
        }
        BootstrapSavedData data = level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
            DATA_FILE
        );
        List<Long> dueChunks = scheduledTownDebrisCleanup.entrySet().stream()
            .filter(entry -> entry.getValue() <= gameTime)
            .map(Map.Entry::getKey)
            .toList();
        for (long chunkKey : dueChunks) {
            scheduledTownDebrisCleanup.remove(chunkKey);
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                continue;
            }
            AABB bounds = new AABB(
                chunkX << 4, level.getMinBuildHeight(), chunkZ << 4,
                (chunkX + 1) << 4, level.getMaxBuildHeight(), (chunkZ + 1) << 4
            );
            int removed = 0;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
                if (isNaturalGenerationDebris(item)) {
                    item.discard();
                    removed++;
                }
            }
            data.markTownDebrisCleanupComplete(chunkKey);
            if (removed > 0) {
                LOGGER.info(
                    "Deferred town debris cleaned: chunk=({}, {}), itemEntities={}",
                    chunkX, chunkZ, removed
                );
            }
        }
    }

    private static boolean isNaturalGenerationDebris(ItemEntity entity) {
        if (!(entity.getItem().getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.is(BlockTags.LOGS)
            || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.SAPLINGS)
            || state.is(BlockTags.FLOWERS)
            || state.is(Blocks.BAMBOO)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.SUGAR_CANE)
            || state.is(Blocks.BROWN_MUSHROOM)
            || state.is(Blocks.RED_MUSHROOM);
    }

    static void scheduleGenerationDebrisCleanup(
        ServerLevel level, String structure, BlockPos origin,
        StructureTemplate template, Rotation rotation
    ) {
        var size = template.getSize();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int localX : new int[] {0, Math.max(0, size.getX() - 1)}) {
            for (int localZ : new int[] {0, Math.max(0, size.getZ() - 1)}) {
                BlockPos corner = origin.offset(StructureTemplate.transform(
                    new BlockPos(localX, 0, localZ), Mirror.NONE, rotation, BlockPos.ZERO
                ));
                minX = Math.min(minX, corner.getX());
                minZ = Math.min(minZ, corner.getZ());
                maxX = Math.max(maxX, corner.getX());
                maxZ = Math.max(maxZ, corner.getZ());
            }
        }
        AABB bounds = new AABB(
            minX - 1, origin.getY() - 1, minZ - 1,
            maxX + 2, origin.getY() + Math.max(1, size.getY()) + 1, maxZ + 2
        );
        cleanupNaturalGenerationDebris(level, bounds, structure);
        long dueAt = level.getGameTime() + 2L;
        for (int chunkX = (minX - 1) >> 4; chunkX <= (maxX + 1) >> 4; chunkX++) {
            for (int chunkZ = (minZ - 1) >> 4; chunkZ <= (maxZ + 1) >> 4; chunkZ++) {
                GenerationDebrisChunk key = new GenerationDebrisChunk(
                    level.dimension(), ChunkPos.asLong(chunkX, chunkZ)
                );
                scheduledGenerationDebrisCleanup.merge(key, dueAt, Math::max);
            }
        }
    }

    private static void runScheduledGenerationDebrisCleanup(MinecraftServer server) {
        if (scheduledGenerationDebrisCleanup.isEmpty()) {
            return;
        }
        List<GenerationDebrisChunk> dueChunks = scheduledGenerationDebrisCleanup.entrySet()
            .stream()
            .filter(entry -> {
                ServerLevel level = server.getLevel(entry.getKey().dimension());
                return level != null && entry.getValue() <= level.getGameTime();
            })
            .map(Map.Entry::getKey)
            .toList();
        for (GenerationDebrisChunk pending : dueChunks) {
            ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                scheduledGenerationDebrisCleanup.remove(pending);
                continue;
            }
            int chunkX = ChunkPos.getX(pending.chunkKey());
            int chunkZ = ChunkPos.getZ(pending.chunkKey());
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                scheduledGenerationDebrisCleanup.put(pending, level.getGameTime() + 20L);
                continue;
            }
            scheduledGenerationDebrisCleanup.remove(pending);
            AABB bounds = new AABB(
                chunkX << 4, level.getMinBuildHeight(), chunkZ << 4,
                (chunkX + 1) << 4, level.getMaxBuildHeight(), (chunkZ + 1) << 4
            );
            cleanupNaturalGenerationDebris(
                level, bounds, "chunk " + chunkX + "," + chunkZ
            );
        }
    }

    private static void cleanupNaturalGenerationDebris(
        ServerLevel level, AABB bounds, String source
    ) {
        int removed = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (isNaturalGenerationDebris(item)) {
                item.discard();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info(
                "Structure generation debris cleaned: source={}, dimension={}, itemEntities={}",
                source, level.dimension().location(), removed
            );
        }
    }

    private record GenerationDebrisChunk(
        ResourceKey<Level> dimension, long chunkKey
    ) {}

    private static void prepareSpecialDistrict(
        ServerLevel level,
        FacilityPlacement facility,
        BlockPoint origin,
        String rotationName
    ) {
        int width = Math.max(8, facility.footprintWidth());
        int depth = Math.max(8, facility.footprintDepth());
        boolean quarterTurn = rotationName.equals("clockwise_90")
            || rotationName.equals("counterclockwise_90");
        if (quarterTurn) {
            int originalWidth = width;
            width = depth;
            depth = originalWidth;
        }
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId != null) {
            var template = level.getStructureManager().get(structureId);
            if (template.isPresent()) {
                var size = template.get().getSize(structureRotation(rotationName));
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
        List<ChunkPos> forcedChunks = forceBlockAreaChunks(
            level, minX, minZ, maxX, maxZ, 1
        );
        try {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean insideFootprint = x >= origin.x() && x < origin.x() + width
                        && z >= origin.z() && z < origin.z() + depth;
                    int terrainY = plannedTerrainGroundY(level, x, z);
                    if (!insideFootprint) {
                        clearVegetationColumn(level, x, terrainY, z, 32);
                        continue;
                    }
                    int obstructionTopY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z
                    ) - 1;
                    HexWorldPlan world = activeHexWorld;
                    TerrainSample sample = world == null
                        ? null : terrainAt(world, x + 0.5D, z + 0.5D);
                    String biome = sample == null ? "minecraft:plains" : sample.biome();
                    if (terrainY < targetY) {
                        for (int y = Math.max(level.getMinBuildHeight(), terrainY + 1); y < targetY; y++) {
                            level.setBlock(new BlockPos(x, y, z), fillerBlock(biome), 2);
                        }
                        level.setBlock(new BlockPos(x, targetY, z), surfaceBlock(biome), 2);
                    } else if (terrainY > targetY) {
                        level.setBlock(new BlockPos(x, targetY, z), surfaceBlock(biome), 2);
                    }
                    for (int y = targetY + 1; y <= Math.max(clearTop, obstructionTopY); y++) {
                        if (y >= level.getMaxBuildHeight()) break;
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        } finally {
            releaseForcedChunks(level, forcedChunks);
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
        TownLayout layout = generateTownLayout(settlement);
        TownPlot generated = layout.facilities().get(facility.id());
        if (generated != null) {
            int x = settlement.center().x() + (int) Math.round(generated.x());
            int z = settlement.center().z() + (int) Math.round(generated.z());
            TownRoad entranceRoad = townBuildingEntranceRoad(layout, generated);
            int roadX = settlement.center().x() + entranceRoad.x2();
            int roadZ = settlement.center().z() + entranceRoad.z2();
            int groundY = runtimeRoadSurfaceY(level, roadX, roadZ);
            LOGGER.info(
                "Generated town facility lot selected: settlement={}, facility={}, "
                    + "origin=({}, {}, {}), roadConnection=({}, {})",
                settlement.id(), facility.id(), x, groundY, z, roadX, roadZ
            );
            return facilityTemplateOrigin(
                level, facility, x, groundY, z, generated.rotation()
            );
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
        LOGGER.error(
            "Compiled gym lot is missing; rebuild the data mod: settlement={}, facility={}",
            settlement.id(), facility.id()
        );
        return null;
    }

    private static String facilityRuntimeRotation(
        SettlementPlan settlement, FacilityPlacement facility
    ) {
        TownPlot generated = generateTownLayout(settlement).facilities().get(facility.id());
        return FacilityPlacementRotation.resolve(
            facility.id(), generated == null ? null : generated.rotation(),
            RGS_GYM_ROTATION
        );
    }

    private static BlockPoint facilityTemplateOrigin(
        FacilityPlacement facility, int x, int groundY, int z
    ) {
        return new BlockPoint(x, groundY - facilityGroundOffset(facility), z);
    }

    private static BlockPoint facilityTemplateOrigin(
        ServerLevel level, FacilityPlacement facility,
        int x, int roadY, int z, String rotationName
    ) {
        BlockPos roadAnchor = BuildingRuntimeSystem.exteriorRoadAnchorOffset(
            level, facility.structure(), rotationName
        );
        int localRoadY = roadAnchor == null
            ? facilityGroundOffset(facility) : roadAnchor.getY();
        return new BlockPoint(x, roadY - localRoadY, z);
    }

    private static int facilityGroundLevelY(
        FacilityPlacement facility, BlockPoint origin
    ) {
        return origin.y() + facilityGroundOffset(facility)
            - BuildingRuntimeSystem.placementYOffset(facility.structure());
    }

    private static int facilityGroundLevelY(
        ServerLevel level, FacilityPlacement facility,
        BlockPoint origin, String rotationName
    ) {
        BlockPos roadAnchor = BuildingRuntimeSystem.exteriorRoadAnchorOffset(
            level, facility.structure(), rotationName
        );
        int localRoadY = roadAnchor == null
            ? facilityGroundOffset(facility) : roadAnchor.getY();
        return origin.y() + localRoadY
            - BuildingRuntimeSystem.placementYOffset(facility.structure());
    }

    private static BlockPoint applyBuildingPlacementYOffset(
        String structure, BlockPoint origin
    ) {
        int offset = BuildingRuntimeSystem.placementYOffset(structure);
        return offset == 0 ? origin : new BlockPoint(
            origin.x(), origin.y() + offset, origin.z()
        );
    }

    private static int facilityGroundOffset(FacilityPlacement facility) {
        // The BCA Pokecenter template stores its surface blocks at local Y=3.
        // Its berry children connect below that surface and grow at local Y=4.
        return facility.id().equals("facility_pokemon_center") ? 3 : 0;
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
                    width, depth, rotateTemplateOffset(
                        RGS_GYM_ENTRANCE_OFFSET, width, depth, RGS_GYM_ROTATION
                    )
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
        int minX = origin.x() - radius;
        int maxX = origin.x() + radius;
        for (int distance = 0; distance <= radius * 2; distance++) {
            for (int x = minX; x <= maxX; x++) {
                int zDistance = distance - Math.abs(x - origin.x());
                if (zDistance < 0 || zDistance > radius) {
                    continue;
                }
                int lowerZ = origin.z() - zDistance;
                if (isVillageRoadCandidate(
                    level, x, lowerZ,
                    excludedMinX, excludedMinZ, excludedMaxX, excludedMaxZ
                )) {
                    return new Point(x, lowerZ);
                }
                if (zDistance == 0) {
                    continue;
                }
                int upperZ = origin.z() + zDistance;
                if (isVillageRoadCandidate(
                    level, x, upperZ,
                    excludedMinX, excludedMinZ, excludedMaxX, excludedMaxZ
                )) {
                    return new Point(x, upperZ);
                }
            }
        }
        return null;
    }

    private static boolean isVillageRoadCandidate(
        ServerLevel level,
        int x,
        int z,
        int excludedMinX,
        int excludedMinZ,
        int excludedMaxX,
        int excludedMaxZ
    ) {
        return !(x >= excludedMinX && x <= excludedMaxX
            && z >= excludedMinZ && z <= excludedMaxZ)
            && isVillageRoadAt(level, x, z);
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

    private static void connectGymEntranceToVillageRoad(
        ServerLevel level,
        SettlementPlan settlement,
        FacilityPlacement facility,
        BlockPoint origin,
        String rotationName
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(facility.structure());
        if (structureId == null) {
            LOGGER.warn("Cannot connect gym entrance for invalid structure ID: {}", facility.structure());
            return;
        }
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            LOGGER.warn("Cannot read gym template size for entrance road: {}", structureId);
            return;
        }
        var size = template.get().getSize();
        BlockPoint entranceOffset = rotateTemplateOffset(
            RGS_GYM_ENTRANCE_OFFSET, size.getX(), size.getZ(), rotationName
        );
        GymEntranceGeometry entranceGeometry = gymEntranceGeometry(
            origin, size.getX(), size.getZ(), entranceOffset
        );
        Point doorway = entranceGeometry.doorway();
        Point approach = new Point(
            doorway.x() + entranceGeometry.outwardX() * 3,
            doorway.z() + entranceGeometry.outwardZ() * 3
        );
        int minX = origin.x() - 1;
        int minZ = origin.z() - 1;
        int maxX = origin.x() + size.getX();
        int maxZ = origin.z() + size.getZ();
        Point villageRoad = findNearestVillageRoad(
            level, approach, GYM_ROAD_SEARCH_RADIUS,
            minX, minZ, maxX, maxZ
        );
        if (villageRoad == null) {
            LOGGER.warn(
                "Gym entrance has no village road within search radius: settlement={}, entrance={}",
                settlement.id(), doorway
            );
            return;
        }
        drawSafeGymApproachRoad(level, doorway, approach);
        drawSafeGymApproachRoad(level, approach, villageRoad);
        LOGGER.info(
            "Gym entrance connected directly to village road: settlement={}, structure={}, "
                + "rotation={}, entrance={}, villageRoad={}",
            settlement.id(), structureId, rotationName, doorway, villageRoad
        );
    }

    private static BlockPoint rotateTemplateOffset(
        BlockPoint point, int width, int depth, String rotationName
    ) {
        return switch (rotationName) {
            case "clockwise_90" -> new BlockPoint(depth - 1 - point.z(), point.y(), point.x());
            case "clockwise_180" -> new BlockPoint(
                width - 1 - point.x(), point.y(), depth - 1 - point.z()
            );
            case "counterclockwise_90" -> new BlockPoint(
                point.z(), point.y(), width - 1 - point.x()
            );
            default -> point;
        };
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

    private static boolean placeOptionalTemplate(
        ServerLevel level, String structure, BlockPoint position
    ) {
        BlockPos blockPos = position.toBlockPos();
        List<ChunkPos> forcedChunks = forceTemplateChunks(level, structure, blockPos);
        try {
            return placeTemplateLoaded(level, structure, position, false);
        } finally {
            releaseForcedChunks(level, forcedChunks);
        }
    }

    private static boolean placeFacilityTemplate(
        ServerLevel level, FacilityPlacement facility, BlockPoint position
    ) {
        return placeFacilityTemplate(level, facility, position, "none");
    }

    private static boolean placeFacilityTemplate(
        ServerLevel level, FacilityPlacement facility, BlockPoint position,
        String rotationName
    ) {
        String structure = facility.structure();
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return false;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return false;
        BlockPoint placementOrigin = facilityPlacementOrigin(
            level, facility, position, rotationName
        );
        BlockPos blockPos = placementOrigin.toBlockPos();
        List<ChunkPos> forcedChunks = forceTemplateChunks(level, structure, blockPos);
        try {
            Rotation rotation = structureRotation(rotationName);
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
                .addProcessor(GroundFloorAirPreservationProcessor.INSTANCE)
                .addProcessor(new FacilityTerrainPreservationProcessor(
                    facilityGroundLevelY(level, facility, position, rotationName)
                ));
            ExplicitAirPlacementProcessor.configure(template.get(), settings);
            boolean placed = template.get().placeInWorld(
                level, blockPos, blockPos, settings,
                RandomSource.create(level.getSeed() ^ blockPos.asLong()), 2
            );
            if (placed) {
                StructurePlacementFixes.afterPlacement(
                    level, blockPos, template.get(), settings
                );
                scheduleGenerationDebrisCleanup(
                    level, structure, blockPos, template.get(), rotation
                );
            }
            return placed;
        } finally {
            releaseForcedChunks(level, forcedChunks);
        }
    }

    private static BlockPoint facilityPlacementOrigin(
        ServerLevel level, FacilityPlacement facility, BlockPoint position,
        String rotationName
    ) {
        if (rotationName.equals("none")) {
            return position;
        }
        var template = level.getStructureManager().get(
            ResourceLocation.parse(facility.structure())
        ).orElseThrow(() -> new IllegalStateException(
            "Facility template is missing: " + facility.structure()
        ));
        var size = template.getSize();
        return rotatedTemplateOrigin(
            position.x(), position.y(), position.z(),
            size.getX(), size.getZ(), rotationName
        );
    }

    private static boolean placeTemplateLoaded(
        ServerLevel level, String structure, BlockPoint position
    ) {
        return placeTemplateLoaded(level, structure, position, true);
    }

    private static boolean placeTemplateLoaded(
        ServerLevel level, String structure, BlockPoint position, boolean logFailure
    ) {
        BlockPos blockPos = position.toBlockPos();
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) return false;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            if (logFailure) LOGGER.error("Template is missing: {}", structure);
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
            .addProcessor(GroundFloorAirPreservationProcessor.INSTANCE);
        ExplicitAirPlacementProcessor.configure(template.orElseThrow(), settings);
        boolean placed = template.orElseThrow().placeInWorld(
            level, blockPos, blockPos, settings,
            RandomSource.create(level.getSeed() ^ blockPos.asLong()), 2
        );
        if (!placed && logFailure) {
            LOGGER.error("Template placement failed for {} at {}", structure, position);
        }
        if (placed) {
            StructurePlacementFixes.afterPlacement(
                level, blockPos, template.orElseThrow(), settings
            );
            scheduleGenerationDebrisCleanup(
                level, structure, blockPos, template.orElseThrow(), Rotation.NONE
            );
        }
        return placed;
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
        Rotation rotation = structureRotation(rotationName);
        BlockPos blockPos = position.toBlockPos();
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
            .addProcessor(GroundFloorAirPreservationProcessor.INSTANCE);
        ExplicitAirPlacementProcessor.configure(template.get(), settings);
        boolean placed = template.get().placeInWorld(
            level, blockPos, blockPos, settings,
            RandomSource.create(level.getSeed() ^ blockPos.asLong()), 2
        );
        if (placed) {
            StructurePlacementFixes.afterPlacement(
                level, blockPos, template.get(), settings
            );
            scheduleGenerationDebrisCleanup(
                level, structure, blockPos, template.get(), rotation
            );
        }
        return placed;
    }

    private static Rotation structureRotation(String rotationName) {
        return switch (rotationName) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "clockwise_180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
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
        for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
            for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                forcedChunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        loadGenerationChunks(level, forcedChunks);
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
                forcedChunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        loadGenerationChunks(level, forcedChunks);
        return forcedChunks;
    }

    private static List<ChunkPos> forceBlockAreaChunks(
        ServerLevel level,
        int minBlockX,
        int minBlockZ,
        int maxBlockX,
        int maxBlockZ,
        int chunkPadding
    ) {
        int minChunkX = (minBlockX >> 4) - chunkPadding;
        int maxChunkX = (maxBlockX >> 4) + chunkPadding;
        int minChunkZ = (minBlockZ >> 4) - chunkPadding;
        int maxChunkZ = (maxBlockZ >> 4) + chunkPadding;
        List<ChunkPos> forcedChunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                forcedChunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        loadGenerationChunks(level, forcedChunks);
        return forcedChunks;
    }

    private static void loadGenerationChunks(
        ServerLevel level, List<ChunkPos> chunks
    ) {
        // These chunks are resident only for the lifetime of the structure edit.
        // setChunkForced persists them in world data and is not a work ticket.
        for (ChunkPos chunk : chunks) {
            level.getChunkSource().addRegionTicket(
                STRUCTURE_GENERATION_TICKET, chunk, 0, chunk
            );
        }
        for (ChunkPos chunk : chunks) {
            if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
                level.getChunk(chunk.x, chunk.z);
            }
        }
    }

    private static void releaseForcedChunks(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            level.getChunkSource().removeRegionTicket(
                STRUCTURE_GENERATION_TICKET, chunk, 0, chunk
            );
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (integrationShutdownTicks > 0 && --integrationShutdownTicks == 0) {
            event.getServer().halt(false);
            return;
        }
        runPendingWorldInitialization(event);
        runActiveWorldInitialization();
        runScheduledGenerationDebrisCleanup(event.getServer());
        tickCompletedTownGenerationDisplay();
        BattleMovementBoundary.tick(event.getServer());
        try {
            PokemonCenterDefeatReturn.onServerTick(event);
        } catch (LinkageError | RuntimeException error) {
            // A development JAR can be replaced while an integrated server is still
            // running. Keep an optional defeat-return failure from stopping the server.
            LOGGER.error("Pokemon Center defeat return tick failed; using emergency recovery", error);
            try {
                PokemonCenterDefeatReturn.recoverAfterTickFailure(event.getServer());
            } catch (LinkageError | RuntimeException recoveryError) {
                LOGGER.error("Pokemon Center emergency recovery also failed", recoveryError);
            }
        }
        Set<UUID> deepWaterBlocked = new HashSet<>();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ServerLevel playerLevel = player.serverLevel();
            if (!enforceDeepWaterAccess(player, playerLevel, playerLevel.getGameTime())) {
                deepWaterBlocked.add(player.getUUID());
            }
        }
        ServerLevel level = event.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            return;
        }
        long gameTime = level.getGameTime();
        runScheduledTownDebrisCleanup(level, gameTime);
        ServerLevel dungeons = event.getServer().getLevel(DUNGEONS);
        scheduleNearbyTownInitialization(level, gameTime);
        scheduleBackgroundTownInitialization(level);
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (deepWaterBlocked.contains(player.getUUID())) {
                PursuitEncounterSystem.tick(player, null, gameTime);
                LocalWeatherSystem.clear(player);
                LocationAnnouncement.clear(player);
                continue;
            }
            if (dungeons != null && player.serverLevel() == dungeons) {
                DungeonSystem.tick(player, gameTime);
                if (player.serverLevel() != dungeons) {
                    continue;
                }
                PursuitEncounterSystem.Config dungeonEncounters =
                    DungeonSystem.randomEncounterConfig(player);
                PursuitEncounterSystem.tick(
                    player,
                    dungeonEncounters != null ? dungeonEncounters
                        : pursuitEncounterAt(dungeons, player.getX(), player.getZ()),
                    gameTime
                );
                if (gameTime % 10L == 0L) {
                    InteriorMusicSystem.sync(player);
                    MusicPlayback.tickDimension(
                        player, "cave", caveIdAt(player.getX(), player.getZ())
                    );
                }
                LocalWeatherSystem.clear(player);
                LocationAnnouncement.clear(player);
                handleCavePortal(player, level, dungeons, gameTime);
                continue;
            }
            ForestRegion forestRegion = activeForestRegions.stream()
                .filter(region -> region.contains(
                    player.serverLevel(), player.getX(), player.getZ()
                ))
                .findFirst()
                .orElseGet(() -> activeForestRegions.stream()
                    .filter(region -> region.dimension().equals(player.serverLevel().dimension()))
                    .findFirst().orElse(null));
            if (forestRegion != null) {
                PursuitEncounterSystem.tick(
                    player, pursuitEncounterAt(player.serverLevel(), player.getX(), player.getZ()), gameTime
                );
                LocalWeatherSystem.clear(player);
                LocationAnnouncement.clear(player);
                HexWorldPlan forestWorld = activeHexWorld;
                if (forestWorld != null) {
                    WorldGateSystem.tick(
                        player, level, forestWorld, gameTime
                    );
                }
                if (gameTime % 10L == 0L) {
                    InteriorMusicSystem.sync(player);
                    MusicPlayback.tickDimension(
                        player, "forest", forestRegion.forestId()
                    );
                }
                continue;
            }
            if (player.serverLevel() != level) {
                PursuitEncounterSystem.tick(player, null, gameTime);
                LocalWeatherSystem.clear(player);
                LocationAnnouncement.clear(player);
                if (gameTime % 10L == 0L) {
                    InteriorMusicSystem.sync(player);
                    MusicPlayback.tickRetainedContext(player);
                }
                continue;
            }
            PokemonCenterDefeatReturn.ensureFallback(
                player, level, level.getSharedSpawnPos()
            );
            if (!enforceVerticalWorldBoundary(player, level, gameTime)) {
                continue;
            }
            PursuitEncounterSystem.tick(
                player, pursuitEncounterAt(level, player.getX(), player.getZ()), gameTime
            );
            if (gameTime % 10L == 0L) {
                updatePokemonCenterCheckpoint(player, level);
            }
            HexWorldPlan world = activeHexWorld;
            if (world != null) {
                DungeonSystem.tick(player, gameTime);
                WorldGateSystem.tick(player, level, world, gameTime);
                if (gameTime % 10L == 0L) {
                    String facilityMusic = facilityMusicContextAt(player);
                    if (facilityMusic == null) MusicPlayback.leaveInterior(player);
                    else MusicPlayback.enterFacility(player, facilityMusic);
                    LocationArea area = locationAreaAt(
                        world, player.getX(), player.getZ()
                    );
                    updateLocationTitle(player, area);
                    TerrainSample sample = terrainAt(
                        world, player.getX(), player.getZ()
                    );
                    HexCoord coordinate = world.grid().worldToHex(player.getX(), player.getZ());
                    MusicPlayback.tick(
                        player,
                        coordinate.q(),
                        coordinate.r(),
                        area.kind(),
                        area.owner()
                    );
                    LocalWeatherSystem.tick(player, world, sample);
                }
            }
            applyRockClimb(player, level);
            if (!enforceFieldMoveAccess(player, level, gameTime)) {
                continue;
            }
            if (!enforceWhirlpoolAccess(player, level, gameTime)) {
                continue;
            }
            if (dungeons != null && handleCavePortal(player, level, dungeons, gameTime)) {
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

    private static LocationArea locationAreaAt(HexWorldPlan world, double x, double z) {
        HexCoord coordinate = world.grid().worldToHex(x, z);
        CellPlan cell = world.cells().get(coordinate);
        // A town keeps priority at route endpoints. Everywhere else, belonging
        // to a route path makes the complete hex tile part of that route.
        if (cell != null && "town".equals(cell.kind())) {
            return new LocationArea("town", cell.owner());
        }
        for (ConnectionPath route : world.paths()) {
            if (route.cells().contains(coordinate)) {
                return new LocationArea("route", route.id());
            }
        }
        return new LocationArea("", "");
    }

    private static void updateLocationTitle(ServerPlayer player, LocationArea area) {
        String candidate = area.kind().isEmpty()
            ? ""
            : area.kind() + ":" + area.owner();
        if (candidate.isEmpty()) {
            LocationAnnouncement.update(
                player, "", Component.empty(), Component.empty(), Component.empty(), false
            );
            return;
        }

        if (area.kind().equals("town")) {
            SettlementPlan settlement = activeSettlements.get(area.owner());
            if (settlement != null) {
                GymInteriorSystem.GymArrivalInfo gym =
                    GymInteriorSystem.arrivalInfo(settlement.id(), player);
                LocationAnnouncement.update(
                    player,
                    candidate,
                    Component.literal(settlement.displayName()).withStyle(ChatFormatting.GOLD),
                    Component.translatable(
                        "message.cobbleventure_player_menu.location_title.town"
                    ).withStyle(ChatFormatting.GRAY),
                    gym == null ? Component.empty() : Component.literal(
                        gym.displayName() + " · " + gymTypeDisplayName(gym.theme())
                            + " 타입 · " + (gym.cleared() ? "클리어" : "미클리어")
                    ),
                    true
                );
            }
            return;
        }
        LocationAnnouncement.update(
            player,
            candidate,
            routeDisplayName(area.owner()).copy().withStyle(ChatFormatting.YELLOW),
            Component.translatable(
                "message.cobbleventure_player_menu.location_title.route"
            ).withStyle(ChatFormatting.GRAY),
            Component.empty(),
            false
        );
    }

    private record LocationArea(String kind, String owner) {}

    private static String gymTypeDisplayName(String type) {
        return switch (type) {
            case "normal" -> "노말";
            case "fire" -> "불꽃";
            case "water" -> "물";
            case "electric" -> "전기";
            case "grass" -> "풀";
            case "ice" -> "얼음";
            case "fighting" -> "격투";
            case "poison" -> "독";
            case "ground" -> "땅";
            case "flying" -> "비행";
            case "psychic" -> "에스퍼";
            case "bug" -> "벌레";
            case "rock" -> "바위";
            case "ghost" -> "고스트";
            case "dragon" -> "드래곤";
            case "dark" -> "악";
            case "steel" -> "강철";
            case "fairy" -> "페어리";
            default -> type;
        };
    }

    private static Component routeDisplayName(String routeId) {
        HexWorldPlan world = activeHexWorld;
        if (world != null) {
            for (ConnectionPath route : world.paths()) {
                if (route.id().equals(routeId) && route.displayName() != null
                    && !route.displayName().isBlank()) {
                    return Component.literal(route.displayName());
                }
            }
        }
        if (routeId.startsWith("route_custom_")) {
            String number = routeId.substring("route_custom_".length()).replaceFirst("^0+", "");
            return Component.translatable(
                "message.cobbleventure_player_menu.location_title.route_number",
                number.isEmpty() ? "0" : number
            );
        }
        if (routeId.equals("route_mt_moon_west")) {
            return Component.translatable(
                "message.cobbleventure_player_menu.location_title.mt_moon_west"
            );
        }
        if (routeId.equals("route_mt_moon_east")) {
            return Component.translatable(
                "message.cobbleventure_player_menu.location_title.mt_moon_east"
            );
        }
        String readable = routeId.replaceFirst("^route_", "").replace('_', ' ');
        return Component.literal(readable);
    }

    private static boolean handleCavePortal(
        ServerPlayer player, ServerLevel generationOne, ServerLevel dungeons, long gameTime
    ) {
        HexWorldPlan world = activeHexWorld;
        if (world == null
            || player.getPersistentData().getLong(CAVE_PORTAL_COOLDOWN) > gameTime) {
            return false;
        }
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            if (!entrance.pokemonCenterEnabled() && !isUndergroundRoad(entrance)) {
                continue;
            }
            if (entrance.destination() == null) {
                continue;
            }
            CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
            if (player.serverLevel() == generationOne) {
                TransitionRegion surfaceEntry = ACTIVE_SURFACE_ENTRY_REGIONS.get(entrance.id());
                if (surfaceEntry == null || !surfaceEntry.touches(player)) {
                    continue;
                }
                if (isUndergroundRoad(entrance)) {
                    BlockPoint destination = entrance.destination();
                    player.getPersistentData().putLong(CAVE_PORTAL_COOLDOWN, gameTime + 40L);
                    player.teleportTo(
                        dungeons,
                        destination.x() + 0.5D,
                        destination.y(),
                        destination.z() + 0.5D,
                        player.getYRot(), player.getXRot()
                    );
                    return true;
                }
                BlockPoint destination = entrance.destination();
                player.getPersistentData().putLong(CAVE_PORTAL_COOLDOWN, gameTime + 40L);
                player.teleportTo(
                    dungeons,
                    destination.x() + 0.5D,
                    destination.y(),
                    destination.z() + 0.5D,
                    player.getYRot(), player.getXRot()
                );
                return true;
            }
            if (isUndergroundRoad(entrance)) {
                TransitionRegion dungeonExit = ACTIVE_UNDERGROUND_DUNGEON_EXITS.get(
                    entrance.id()
                );
                if (dungeonExit == null || !dungeonExit.touches(player)) continue;
            }
            BlockPoint portalAnchor = entrance.portalAnchor();
            if (portalAnchor == null) {
                continue;
            }
            double dx = player.getX() - (portalAnchor.x() + 0.5D);
            double dy = player.getY() - portalAnchor.y();
            double dz = player.getZ() - (portalAnchor.z() + 0.5D);
            if (!isUndergroundRoad(entrance)
                && (dx * dx + dz * dz > 4.0D || dy < -2.5D || dy > 2.5D)) {
                continue;
            }
            int returnX = mouth.x() - (int) Math.round(mouth.forwardX() * 5.0D);
            int returnZ = mouth.z() - (int) Math.round(mouth.forwardZ() * 5.0D);
            int returnY = caveEntranceFloorY(world, entrance) + 1;
            player.getPersistentData().putLong(CAVE_PORTAL_COOLDOWN, gameTime + 40L);
            player.teleportTo(
                generationOne,
                returnX + 0.5D,
                returnY,
                returnZ + 0.5D,
                player.getYRot(), player.getXRot()
            );
            return true;
        }
        return false;
    }

    private static void updatePokemonCenterCheckpoint(
        ServerPlayer player,
        ServerLevel level
    ) {
        for (SettlementPlan settlement : activeSettlements.values()) {
            TownLayout layout = settlement.compiledLayout();
            if (layout == null) {
                continue;
            }
            TownPlot center = layout.facilities().get("facility_pokemon_center");
            if (center == null) {
                continue;
            }
            int minX = settlement.center().x() + (int) Math.floor(center.x());
            int minZ = settlement.center().z() + (int) Math.floor(center.z());
            if (player.getX() < minX || player.getX() >= minX + center.width()
                || player.getZ() < minZ || player.getZ() >= minZ + center.depth()) {
                continue;
            }
            FacilityPlacement facility = settlement.facilities().stream()
                .filter(candidate -> candidate.id().equals("facility_pokemon_center"))
                .findFirst()
                .orElse(null);
            if (facility == null) {
                continue;
            }
            TownRoad entrance = townBuildingEntranceRoad(layout, center);
            int entranceX = settlement.center().x() + entrance.x2();
            int entranceZ = settlement.center().z() + entrance.z2();
            String eventSpaceId = buildingEventSpaceId(settlement.id(), facility.id());
            BuildingRuntimeSystem.PlacedBuilding placed =
                BuildingRuntimeSystem.resolvePlacedBuilding(
                    level.getServer(), level.dimension(), facility.structure(), eventSpaceId
                );
            BlockPoint origin;
            if (placed != null) {
                origin = placed.origin();
            } else {
                int originX = settlement.center().x() + (int) Math.round(center.x());
                int originZ = settlement.center().z() + (int) Math.round(center.z());
                origin = facilityTemplateOrigin(
                    level, facility, originX,
                    loadedRoadSurfaceY(level, entranceX, entranceZ),
                    originZ, center.rotation()
                );
            }
            BlockPos exit = surfacePosition(
                level, entranceX, entranceZ
            );
            PokemonCenterDefeatReturn.recordCenterVisit(
                player,
                level,
                new BlockPos(origin.x() + 14, origin.y() + 5, origin.z() + 10),
                exit
            );
            return;
        }
        HexWorldPlan world = activeHexWorld;
        if (world == null) {
            return;
        }
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            if (!entrance.pokemonCenterEnabled()) {
                continue;
            }
            Point entranceCenter = world.grid().worldCenter(entrance.anchor());
            HexCoord offset = entrance.pokemonCenterOffset();
            Point offsetCenter = world.grid().worldCenter(new HexCoord(
                entrance.anchor().q() + offset.q(), entrance.anchor().r() + offset.r()
            ));
            double deltaX = offsetCenter.x() - entranceCenter.x();
            double deltaZ = offsetCenter.z() - entranceCenter.z();
            double length = Math.max(1.0D, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
            int centerX = entranceCenter.x() + (int) Math.round(deltaX / length * 28.0D);
            int centerZ = entranceCenter.z() + (int) Math.round(deltaZ / length * 28.0D);
            double dx = player.getX() - centerX;
            double dz = player.getZ() - centerZ;
            if (dx * dx + dz * dz <= 144.0D) {
                String structure = entrance.pokemonCenterStructure();
                ResourceLocation structureId = ResourceLocation.tryParse(structure);
                if (structureId == null || level.getStructureManager().get(structureId).isEmpty()) {
                    continue;
                }
                var size = level.getStructureManager().get(structureId).orElseThrow().getSize();
                int groundY = plannedTerrainGroundY(level, centerX, centerZ);
                BlockPoint origin = new BlockPoint(
                    centerX - size.getX() / 2,
                    groundY - 3,
                    centerZ - size.getZ() / 2
                );
                PokemonCenterDefeatReturn.recordCenterVisit(
                    player, level,
                    new BlockPos(origin.x() + 14, origin.y() + 5, origin.z() + 10),
                    surfacePosition(
                        level,
                        origin.x() - 4,
                        origin.z() + Math.min(10, size.getZ() - 1)
                    )
                );
                return;
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
        SettlementPlan next = activeSettlements.values().stream()
            .filter(SettlementPlan::enabled)
            .filter(settlement -> !data.isSettlementGenerated(settlement.id()))
            .min(Comparator.comparingInt(SettlementPlan::loadOrder).thenComparing(SettlementPlan::id))
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
            next.id(), townChunkLoadBudget(next, false)
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
        if (job.townStartedAt == 0L) {
            job.townStartedAt = System.nanoTime();
            long handoffMs = job.previousTownCompletedAt == 0L
                ? 0L : (job.townStartedAt - job.previousTownCompletedAt) / 1_000_000L;
            LOGGER.info(
                "Town generation started: settlement={}, index={}/{}, handoffMs={}",
                settlement.id(), job.index + 1, job.settlements.size(), handoffMs
            );
            if (Boolean.getBoolean(TOWN_SEQUENCE_PERFORMANCE_TEST_PROPERTY)
                && job.index > 0) {
                LOGGER.info(
                    "Town sequence measurement boundary reached before second town generation: nextSettlement={}, handoffMs={}",
                    settlement.id(), handoffMs
                );
                activeInitialization = null;
                job.display.close();
                integrationShutdownTicks = 40;
                return;
            }
        }
        job.display.update(job, settlement);
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
                job.chunkFinishedAt = System.nanoTime();
                job.phase = 0;
            } else if (job.phase == 0) {
                long phaseStartedAt = System.nanoTime();
                if (!placeTown(job.level, settlement)) {
                    throw new IllegalStateException("Town road placement returned false");
                }
                job.roadElapsedNanos = System.nanoTime() - phaseStartedAt;
                job.phase = 1;
            } else if (job.phase == 1) {
                long phaseStartedAt = System.nanoTime();
                if (!placeFacilities(job.level, settlement)) {
                    throw new IllegalStateException("Town facility placement returned false");
                }
                job.facilityElapsedNanos = System.nanoTime() - phaseStartedAt;
                job.phase = 2;
            } else {
                long phaseStartedAt = System.nanoTime();
                if (NativeWorldGeneration.usesNativeGenerator(
                    job.level.getChunkSource().getGenerator()
                )) {
                    HexSettlement hexSettlement = job.runtime.hexWorld().settlements()
                        .get(settlement.id());
                    String biome = hexSettlement == null
                        ? "" : hexSettlement.townBiome();
                    long baseSeed = job.runtime.hexWorld().seed()
                        ^ ((long) settlement.id().hashCode() << 32);
                    int[] streetDecorations = decoratePlannedTownStreets(
                        job.level, job.runtime.hexWorld(), settlement, biome, baseSeed
                    );
                    LOGGER.info(
                        "Native town natural landscaping skipped; planned street decorations placed: settlement={}, streetLamps={}, streetTrees={}, benches={}, flowerBeds={}, fountains={}",
                        settlement.id(), streetDecorations[0], streetDecorations[1],
                        streetDecorations[2], streetDecorations[3], streetDecorations[4]
                    );
                } else {
                    decorateTownLandscape(job.level, job.runtime.hexWorld(), settlement);
                }
                cleanupTownGenerationDebris(job.level, settlement);
                placeAutomaticTownNpcs(job.level, settlement, job.data);
                job.data.markTownDebrisCleanupPending(townPreparationChunkKeys(settlement));
                long completedAt = System.nanoTime();
                long landscapeElapsedNanos = completedAt - phaseStartedAt;
                LOGGER.info(
                    "Town generation completed: settlement={}, chunks={}, chunkMs={}, roadMs={}, facilityMs={}, landscapeMs={}, totalMs={}",
                    settlement.id(), job.townChunks.size(),
                    (job.chunkFinishedAt - job.townStartedAt) / 1_000_000L,
                    job.roadElapsedNanos / 1_000_000L,
                    job.facilityElapsedNanos / 1_000_000L,
                    landscapeElapsedNanos / 1_000_000L,
                    (completedAt - job.townStartedAt) / 1_000_000L
                );
                releasePreparedTownChunks(job);
                job.phase = -1;
                job.index++;
                job.previousTownCompletedAt = completedAt;
                job.townStartedAt = 0L;
                job.chunkFinishedAt = 0L;
                job.roadElapsedNanos = 0L;
                job.facilityElapsedNanos = 0L;
                if (job.index >= job.settlements.size()) {
                    finishWorldInitialization(job);
                }
            }
        } catch (RuntimeException error) {
            releasePreparedTownChunks(job);
            activeInitialization = null;
            job.display.close();
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
        int budget = townChunkLoadBudget(settlement, job.player != null);
        int requested = 0;
        while (job.nextTownChunk < job.townChunks.size() && requested < budget) {
            ChunkPos chunk = job.townChunks.get(job.nextTownChunk++);
            job.level.getChunkSource().addRegionTicket(
                TOWN_GENERATION_TICKET, chunk, 0, chunk
            );
            if (job.level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
                job.level.getChunk(chunk.x, chunk.z);
            }
            requested++;
        }
    }

    private static int townChunkLoadBudget(
        SettlementPlan settlement, boolean playerTriggered
    ) {
        return playerTriggered || settlement.id().equals(STARTER_SETTLEMENT)
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
        for (int index = 0; index < job.nextTownChunk; index++) {
            ChunkPos chunk = job.townChunks.get(index);
            job.level.getChunkSource().removeRegionTicket(
                TOWN_GENERATION_TICKET, chunk, 0, chunk
            );
        }
        job.townChunks.clear();
        job.nextTownChunk = 0;
        job.lastReportedReadyChunks = -1;
        job.chunkPreparationStartedAt = 0L;
    }

    private static void tickCompletedTownGenerationDisplay() {
        TownGenerationDisplay display = completedTownGenerationDisplay;
        if (display == null || completedTownGenerationDisplayTicks <= 0) {
            return;
        }
        if (--completedTownGenerationDisplayTicks == 0) {
            display.close();
            completedTownGenerationDisplay = null;
        }
    }

    private static Set<Long> townPreparationChunkKeys(SettlementPlan settlement) {
        Set<Long> chunks = new HashSet<>();
        TownLayout layout = generateTownLayout(settlement);
        Point center = new Point(settlement.center().x(), settlement.center().z());
        Set<Long> roadColumns = new HashSet<>();
        for (TownRoad road : layout.roads()) {
            collectConfiguredRoadColumns(
                roadColumns,
                center.translate(road.x1(), road.z1()),
                center.translate(road.x2(), road.z2()),
                settlement.roadProfile().width()
            );
        }
        for (TownDecoration decoration : layout.decorations()) {
            chunks.add(ChunkPos.asLong(
                (center.x() + decoration.x()) >> 4,
                (center.z() + decoration.z()) >> 4
            ));
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
        activeFacilityMusicZones = facilityMusicZones(job.level, job.runtime.settlements());
        for (SettlementPlan settlement : job.settlements) {
            job.data.markSettlementGenerated(settlement.id());
        }
        boolean allSettlementsGenerated = job.runtime.settlements().values().stream()
            .filter(SettlementPlan::enabled)
            .allMatch(settlement -> job.data.isSettlementGenerated(settlement.id()));
        if (allSettlementsGenerated) {
            job.display.complete();
            completedTownGenerationDisplay = job.display;
            completedTownGenerationDisplayTicks = 100;
        } else {
            job.display.close();
        }
        if (!job.initialGeneration) {
            String settlementId = job.settlements.getFirst().id();
            if (Boolean.getBoolean(TOWN_SEQUENCE_PERFORMANCE_TEST_PROPERTY)) {
                LOGGER.info(
                    "Town sequence performance measurement completed: settlements={}",
                    job.settlements.stream().map(SettlementPlan::id).toList()
                );
                integrationShutdownTicks = 40;
                return;
            }
            job.progress.update(100, "마을 생성 완료: " + settlementId);
            if (job.player != null) {
                job.player.sendSystemMessage(Component.literal(
                    "[Cobbleventure] 접근한 마을을 생성했습니다: " + settlementId
                ));
            } else {
                LOGGER.info("Background town initialization completed: {}", settlementId);
            }
            if (allSettlementsGenerated) {
                // Route trainers must be restored after the last background town. Spawning
                // them after only the starter town can query incomplete route chunks at Y=0,
                // and later structure placement can invalidate an otherwise safe position.
                spawnRouteNpcs(job.level, job.runtime.hexWorld());
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

    private static boolean enforceVerticalWorldBoundary(
        ServerPlayer player, ServerLevel level, long gameTime
    ) {
        if (player.isSpectator()) {
            return true;
        }
        if (player.getY() < VERTICAL_BOUNDARY_Y) {
            return true;
        }

        BlockPos destination = safeTeleportPosition(
            level, player.getBlockX(), player.getBlockZ()
        );
        if (destination == null) {
            BlockPos spawn = level.getSharedSpawnPos();
            destination = safeTeleportPosition(level, spawn.getX(), spawn.getZ());
        }
        if (destination == null) destination = level.getSharedSpawnPos();
        Vec3 safe = Vec3.atBottomCenterOf(destination);
        restorePlayerInsideWorldBoundary(player, level, safe);
        if (player.getPersistentData().getLong(VERTICAL_BOUNDARY_MESSAGE_COOLDOWN)
            <= gameTime) {
            player.getPersistentData().putLong(
                VERTICAL_BOUNDARY_MESSAGE_COOLDOWN, gameTime + 60L
            );
            player.displayClientMessage(Component.literal(
                "[Cobbleventure] 이동 가능한 최대 높이에 도달했습니다."
            ), true);
        }
        return false;
    }

    private static void restorePlayerInsideWorldBoundary(
        ServerPlayer player, ServerLevel level, Vec3 safe
    ) {
        player.teleportTo(
            level, safe.x(), safe.y(), safe.z(), player.getYRot(), player.getXRot()
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
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
        if (requirement == null || isSurfRequirement(requirement)
            || isTraversalOnlyRequirement(requirement)
            || hasFieldMove(player, requirement)) {
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

    private static boolean enforceDeepWaterAccess(
        ServerPlayer player, ServerLevel level, long gameTime
    ) {
        UUID playerId = player.getUUID();
        if (player.isCreative() || player.isSpectator()
            || FieldMoveRidingAccess.isValidSurfRide(player)
            || FieldMoveRidingAccess.isProtectedOceanBattle(player)) {
            deepWaterTicks.remove(playerId);
            return true;
        }
        if (!isInDeepWater(player, level)) {
            deepWaterTicks.remove(playerId);
            if (!player.isInWater() && player.onGround()) {
                safeWaterPositions.put(
                    playerId, new SafeWaterPosition(level.dimension(), player.position())
                );
            }
            return true;
        }

        SafeWaterPosition saved = safeWaterPositions.get(playerId);
        Vec3 safe = saved != null && saved.dimension().equals(level.dimension())
            ? saved.position() : null;
        if (safe == null) {
            safe = findNearbyDryPosition(level, player.blockPosition(), 24);
            if (safe != null) {
                safeWaterPositions.put(
                    playerId, new SafeWaterPosition(level.dimension(), safe)
                );
            }
        }
        if (safe != null) {
            double deltaX = safe.x() - player.getX();
            double deltaZ = safe.z() - player.getZ();
            double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            if (horizontalDistance > 0.01D) {
                double currentStrength = 0.18D;
                player.setDeltaMovement(
                    deltaX / horizontalDistance * currentStrength,
                    Math.max(player.getDeltaMovement().y(), 0.05D),
                    deltaZ / horizontalDistance * currentStrength
                );
                player.hurtMarked = true;
            }
        } else {
            player.setDeltaMovement(
                0.0D, Math.max(player.getDeltaMovement().y(), 0.05D), 0.0D
            );
            player.hurtMarked = true;
        }
        player.resetFallDistance();

        int blockedTicks = deepWaterTicks.merge(playerId, 1, Integer::sum);
        if (player.getPersistentData().getLong(DEEP_WATER_MESSAGE_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(DEEP_WATER_MESSAGE_COOLDOWN, gameTime + 60L);
            player.displayClientMessage(Component.literal(
                "[Cobbleventure] 물살이 너무 거셉니다. 파도타기 포켓몬이 필요합니다."
            ), true);
        }
        if (blockedTicks >= 20 && safe != null) {
            player.teleportTo(
                level, safe.x(), safe.y(), safe.z(), player.getYRot(), player.getXRot()
            );
            deepWaterTicks.remove(playerId);
        }
        return false;
    }

    private static boolean isInDeepWater(ServerPlayer player, ServerLevel level) {
        if (!player.isInWater()) {
            return false;
        }
        BlockPos.MutableBlockPos position = player.blockPosition().mutable();
        if (!level.getFluidState(position).is(FluidTags.WATER)) {
            position.set(player.getX(), player.getEyeY() - 0.1D, player.getZ());
        }
        if (!level.getFluidState(position).is(FluidTags.WATER)) {
            return false;
        }
        while (position.getY() < level.getMaxBuildHeight() - 1
            && level.getFluidState(position.above()).is(FluidTags.WATER)) {
            position.move(0, 1, 0);
        }
        return level.getFluidState(position.below()).is(FluidTags.WATER);
    }

    private static Vec3 findNearbyDryPosition(
        ServerLevel level, BlockPos origin, int maxRadius
    ) {
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (Math.abs(offsetX) != radius && Math.abs(offsetZ) != radius) {
                        continue;
                    }
                    int x = origin.getX() + offsetX;
                    int z = origin.getZ() + offsetZ;
                    int groundY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z
                    ) - 1;
                    BlockPos ground = new BlockPos(x, groundY, z);
                    BlockPos feet = ground.above();
                    if (level.getFluidState(ground).is(FluidTags.WATER)
                        || !level.getFluidState(feet).isEmpty()
                        || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        || !level.getBlockState(feet.above())
                            .getCollisionShape(level, feet.above()).isEmpty()) {
                        continue;
                    }
                    return new Vec3(x + 0.5D, feet.getY(), z + 0.5D);
                }
            }
        }
        return null;
    }

    private static boolean isSurfRequirement(String requirement) {
        return "surf".equals(fieldMoveName(requirement));
    }

    private static boolean isTraversalOnlyRequirement(String requirement) {
        String move = fieldMoveName(requirement);
        return "rock_climb".equals(move) || "whirlpool".equals(move);
    }

    private static void applyRockClimb(ServerPlayer player, ServerLevel level) {
        if (!FieldMoveRidingAccess.isActive(player, "rock_climb")
            || player.isPassenger() || player.isSpectator() || player.isInWater()
            || !player.horizontalCollision || !touchesNaturalRockWall(player, level)) {
            return;
        }
        Vec3 movement = player.getDeltaMovement();
        double vertical = player.isShiftKeyDown() ? -0.12D : 0.20D;
        player.setDeltaMovement(movement.x(), vertical, movement.z());
        player.resetFallDistance();
        player.hurtMarked = true;
    }

    private static boolean touchesNaturalRockWall(ServerPlayer player, ServerLevel level) {
        BlockPos feet = player.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = feet.relative(direction);
            if (isNaturalRock(level, adjacent) || isNaturalRock(level, adjacent.above())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNaturalRock(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private static boolean enforceWhirlpoolAccess(
        ServerPlayer player, ServerLevel level, long gameTime
    ) {
        HexWorldPlan world = activeHexWorld;
        if (world == null || player.isCreative() || player.isSpectator()) {
            return true;
        }
        if (gameTime % 4L == 0L) {
            renderNearbyWhirlpools(player, level, world);
        }
        if (!FieldMoveRidingAccess.isValidSurfRide(player)) {
            safeWhirlpoolPositions.remove(player.getUUID());
            return true;
        }

        TerrainSample sample = terrainAt(world, player.getX(), player.getZ());
        boolean insideWhirlpoolSea = sample != null
            && WHIRLPOOL_REQUIREMENT.equals(sample.accessRequirement());
        if (!insideWhirlpoolSea) {
            safeWhirlpoolPositions.put(player.getUUID(), player.position());
            return true;
        }
        if (FieldMoveRidingAccess.isEnabled(player, "whirlpool")) {
            return true;
        }

        Vec3 safe = safeWhirlpoolPositions.get(player.getUUID());
        if (safe != null) {
            Entity mover = player.getVehicle() == null ? player : player.getVehicle();
            double deltaX = safe.x() - mover.getX();
            double deltaZ = safe.z() - mover.getZ();
            double distance = Math.hypot(deltaX, deltaZ);
            if (distance > 0.01D) {
                mover.setDeltaMovement(
                    deltaX / distance * 0.42D,
                    Math.max(0.08D, mover.getDeltaMovement().y()),
                    deltaZ / distance * 0.42D
                );
                mover.hurtMarked = true;
            }
        }
        player.resetFallDistance();
        if (player.getPersistentData().getLong(WHIRLPOOL_MESSAGE_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(WHIRLPOOL_MESSAGE_COOLDOWN, gameTime + 60L);
            player.displayClientMessage(Component.literal(
                "[Cobbleventure] 거센 바다회오리를 통과하려면 바다회오리가 필요합니다."
            ), true);
        }
        return false;
    }

    private static void renderNearbyWhirlpools(
        ServerPlayer player, ServerLevel level, HexWorldPlan world
    ) {
        int centerX = player.blockPosition().getX();
        int centerZ = player.blockPosition().getZ();
        for (int offsetX = -18; offsetX <= 18; offsetX += 3) {
            for (int offsetZ = -18; offsetZ <= 18; offsetZ += 3) {
                int x = centerX + offsetX;
                int z = centerZ + offsetZ;
                if (Math.floorMod(x * 31 + z * 17, 4) != 0
                    || !isWhirlpoolBoundary(world, x, z)
                    || !level.getFluidState(new BlockPos(x, WATER_SURFACE_Y, z)).is(FluidTags.WATER)) {
                    continue;
                }
                level.sendParticles(
                    ParticleTypes.BUBBLE_COLUMN_UP,
                    x + 0.5D, WATER_SURFACE_Y + 0.1D, z + 0.5D,
                    5, 0.45D, 0.25D, 0.45D, 0.04D
                );
                level.sendParticles(
                    ParticleTypes.SPLASH,
                    x + 0.5D, WATER_SURFACE_Y + 0.35D, z + 0.5D,
                    2, 0.35D, 0.1D, 0.35D, 0.02D
                );
            }
        }
    }

    private static boolean isWhirlpoolBoundary(HexWorldPlan world, int x, int z) {
        boolean center = isWhirlpoolTerrain(terrainAt(world, x + 0.5D, z + 0.5D));
        return center != isWhirlpoolTerrain(terrainAt(world, x + 3.5D, z + 0.5D))
            || center != isWhirlpoolTerrain(terrainAt(world, x - 2.5D, z + 0.5D))
            || center != isWhirlpoolTerrain(terrainAt(world, x + 0.5D, z + 3.5D))
            || center != isWhirlpoolTerrain(terrainAt(world, x + 0.5D, z - 2.5D));
    }

    private static boolean isWhirlpoolTerrain(TerrainSample sample) {
        return sample != null && WHIRLPOOL_REQUIREMENT.equals(sample.accessRequirement());
    }

    private static boolean hasFieldMove(ServerPlayer player, String requirement) {
        return FieldMoveRidingAccess.isEnabled(player, fieldMoveName(requirement));
    }

    private static String fieldMoveName(String requirement) {
        int separator = requirement.lastIndexOf('/');
        return separator >= 0 ? requirement.substring(separator + 1) : requirement;
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_center")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("teleport")
                    .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("settlement", StringArgumentType.greedyString())
                            .executes(context -> teleportToPokemonCenter(
                                EntityArgument.getPlayer(context, "target"),
                                StringArgumentType.getString(context, "settlement")
                            )))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_gate")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("teleport")
                    .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("gate", StringArgumentType.word())
                            .then(Commands.argument("side", StringArgumentType.word())
                                .executes(context -> {
                                    if (activeHexWorld == null) {
                                        context.getSource().sendFailure(Component.literal(
                                            "[Cobbleventure] 관문 데이터가 준비되지 않았습니다."
                                        ));
                                        return 0;
                                    }
                                    try {
                                        return WorldGateSystem.teleportToGate(
                                            context.getSource().getLevel(),
                                            EntityArgument.getEntities(context, "targets"),
                                            activeHexWorld,
                                            StringArgumentType.getString(context, "gate"),
                                            StringArgumentType.getString(context, "side")
                                        );
                                    } catch (IllegalArgumentException error) {
                                        context.getSource().sendFailure(Component.literal(
                                            "[Cobbleventure] " + error.getMessage()
                                        ));
                                        return 0;
                                    }
                                })))))
        );
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
                .then(Commands.literal("grant")
                    .requires(source -> source.hasPermission(2))
                    .then(fieldMoveArgument()
                        .executes(context -> setFieldMove(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move"), true
                        )))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(fieldMoveArgument()
                            .executes(context -> setFieldMove(
                                EntityArgument.getPlayers(context, "targets"),
                                StringArgumentType.getString(context, "move"), true
                            )))))
                .then(Commands.literal("revoke")
                    .requires(source -> source.hasPermission(2))
                    .then(fieldMoveArgument()
                        .executes(context -> setFieldMove(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move"), false
                        )))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(fieldMoveArgument()
                            .executes(context -> setFieldMove(
                                EntityArgument.getPlayers(context, "targets"),
                                StringArgumentType.getString(context, "move"), false
                            )))))
                .then(Commands.literal("on")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(fieldMoveArgument()
                            .executes(context -> setFieldMove(
                                EntityArgument.getPlayers(context, "targets"),
                                StringArgumentType.getString(context, "move"), true
                            )))))
                .then(Commands.literal("off")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .then(fieldMoveArgument()
                            .executes(context -> setFieldMove(
                                EntityArgument.getPlayers(context, "targets"),
                                StringArgumentType.getString(context, "move"), false
                            )))))
                .then(fieldMoveArgument()
                    .then(Commands.literal("on")
                        .executes(context -> setFieldMoveActive(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move"), true
                        )))
                    .then(Commands.literal("off")
                        .executes(context -> setFieldMoveActive(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move"), false
                        )))
                    .then(Commands.literal("toggle")
                        .executes(context -> toggleFieldMoveActive(
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "move")
                        ))))
        );
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
        CommandSourceStack, String
    > fieldMoveArgument() {
        return Commands.argument("move", StringArgumentType.word())
            .suggests((context, builder) -> {
                for (String move : SUPPORTED_FIELD_MOVES) {
                    builder.suggest(move);
                }
                return builder.buildFuture();
            });
    }

    private static int teleportToPokemonCenter(
        ServerPlayer player, String settlementId
    ) {
        SettlementPlan settlement = activeSettlements.get(settlementId);
        if (settlement == null || settlement.compiledLayout() == null) {
            return 0;
        }
        TownPlot center = settlement.compiledLayout().facilities()
            .get("facility_pokemon_center");
        if (center == null) {
            return 0;
        }
        ServerLevel level = player.getServer().getLevel(GENERATION_ONE);
        if (level == null) {
            return 0;
        }
        TownRoad entrance = townBuildingEntranceRoad(
            settlement.compiledLayout(), center
        );
        BlockPos target = safeTeleportPosition(
            level,
            settlement.center().x() + entrance.x2(),
            settlement.center().z() + entrance.z2()
        );
        if (target == null) {
            LOGGER.error(
                "Pokemon Center teleport has no safe surface: settlement={}, x={}, z={}",
                settlementId,
                settlement.center().x() + entrance.x2(),
                settlement.center().z() + entrance.z2()
            );
            return 0;
        }
        player.stopRiding();
        player.teleportTo(
            level,
            target.getX() + 0.5D,
            target.getY(),
            target.getZ() + 0.5D,
            -90.0F,
            0.0F
        );
        player.resetFallDistance();
        return 1;
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
            BootstrapSavedData data = level.getServer().overworld().getDataStorage()
                .computeIfAbsent(
                    new SavedData.Factory<>(BootstrapSavedData::create, BootstrapSavedData::load),
                    DATA_FILE
                );
            placeAutomaticTownNpcs(level, translated, data);
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
            settlement.id(), settlement.displayName(), settlement.enabled(),
            settlement.loadOrder(),
            settlement.townRadiusCells(),
            settlement.structure(), settlement.houseStyle(), settlement.disableCommercialOneOff(),
            settlement.layoutShape(), settlement.roadLayoutTemplate(), settlement.roadProfile(), settlement.generationSeed(),
            settlement.generationDepth(), settlement.buildingDensity(), settlement.basicBuildings(),
            settlement.center().translate(deltaX, deltaZ),
            settlement.structurePoint().translate(deltaX, deltaZ),
            settlement.playerSpawn().translate(deltaX, deltaZ),
            Map.copyOf(anchors), settlement.facilities(), settlement.gates(),
            settlement.shopCatalogId(), settlement.vendorUnits(),
            settlement.vendorAssignments(),
            settlement.compiledLayout(), settlement.automaticNpcPlacements()
        );
    }

    private static int setFieldMove(ServerPlayer player, String move, boolean granted) {
        if (!FieldMoveRidingAccess.isSupported(move)) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 지원하지 않는 비전머신입니다: " + move
            ));
            return 0;
        }
        FieldMoveRidingAccess.setEnabled(player, move, granted);
        player.sendSystemMessage(Component.literal(
            "[Cobbleventure] " + FieldMoveRidingAccess.displayName(move)
                + " 필드 기술을 " + (granted ? "해금했습니다." : "회수했습니다.")
        ));
        return 1;
    }

    private static int setFieldMoveActive(ServerPlayer player, String move, boolean active) {
        if (!FieldMoveRidingAccess.setActive(player, move, active)) {
            player.sendSystemMessage(Component.literal(
                "[Cobbleventure] 보유 중인 ON/OFF 비전머신이 아닙니다: " + move
            ));
            return 0;
        }
        player.displayClientMessage(Component.literal(
            "[Cobbleventure] " + FieldMoveRidingAccess.displayName(move)
                + " " + (active ? "ON" : "OFF")
        ), true);
        return 1;
    }

    private static int toggleFieldMoveActive(ServerPlayer player, String move) {
        return setFieldMoveActive(
            player, move, !FieldMoveRidingAccess.isActive(player, move)
        );
    }

    private static int setFieldMove(Iterable<ServerPlayer> players, String move, boolean granted) {
        int changed = 0;
        for (ServerPlayer player : players) {
            changed += setFieldMove(player, move, granted);
        }
        return changed;
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

    private static List<FacilityMusicZone> facilityMusicZones(
        ServerLevel level, Map<String, SettlementPlan> settlements
    ) {
        List<FacilityMusicZone> zones = new ArrayList<>();
        for (SettlementPlan settlement : settlements.values()) {
            if (!settlement.enabled()) continue;
            for (FacilityPlacement facility : settlement.facilities()) {
                String context = "pokemon_center".equals(facility.facilityType())
                    ? "pokemon_center"
                    : "pokemart".equals(facility.facilityType()) ? "pokemart" : null;
                if (context == null || !facility.mode().equals("direct_template")) continue;
                BlockPoint resolved = resolveDirectFacilityPosition(level, settlement, facility);
                if (resolved == null) continue;
                BlockPoint origin = applyBuildingPlacementYOffset(facility.structure(), resolved);
                zones.add(new FacilityMusicZone(
                    origin.x(), origin.y(), origin.z(),
                    origin.x() + Math.max(1, facility.footprintWidth()),
                    origin.y() + Math.max(4, facility.footprintHeight()),
                    origin.z() + Math.max(1, facility.footprintDepth()),
                    context
                ));
            }
        }
        return List.copyOf(zones);
    }

    private static String facilityMusicContextAt(ServerPlayer player) {
        for (FacilityMusicZone zone : activeFacilityMusicZones) {
            if (zone.contains(player.getX(), player.getY(), player.getZ())
                && hasFacilityCeiling(player.serverLevel(), player.blockPosition(), zone)) {
                return zone.context();
            }
        }
        return null;
    }

    private static boolean hasFacilityCeiling(
        ServerLevel level, BlockPos playerPosition, FacilityMusicZone zone
    ) {
        int top = Math.min(zone.maxY(), playerPosition.getY() + 16);
        for (int y = playerPosition.getY() + 2; y < top; y++) {
            int roofBlocks = 0;
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    BlockState state = level.getBlockState(new BlockPos(
                        playerPosition.getX() + offsetX, y,
                        playerPosition.getZ() + offsetZ
                    ));
                    if (!state.isAir() && !state.is(BlockTags.LEAVES)
                        && !state.is(BlockTags.LOGS)) {
                        roofBlocks++;
                    }
                }
            }
            if (roofBlocks >= 3) return true;
        }
        return false;
    }

    private static Map<String, SettlementPlan> loadSettlementPlans(ServerLevel level) {
        Map<String, SettlementPlan> plans = new LinkedHashMap<>();
        List<SettlementPlan> loadedPlans = new ArrayList<>();
        Map<String, String> facilityDefaults = loadFacilityStructureDefaults(level);
        Map<ResourceLocation, Resource> resources = level.getServer().getResourceManager().listResources(
            "settlements",
            location -> location.getNamespace().equals("cobbleventure")
                && location.getPath().endsWith(".json")
        );
        resources.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .forEach(entry -> {
                try (Reader reader = entry.getValue().openAsReader()) {
                    loadedPlans.add(parseSettlement(
                        level, JsonParser.parseReader(reader).getAsJsonObject(), facilityDefaults
                    ));
                } catch (IOException | RuntimeException error) {
                    throw new IllegalStateException("Invalid settlement resource: " + entry.getKey(), error);
                }
            });
        loadedPlans.stream()
            .sorted(Comparator.comparingInt(SettlementPlan::loadOrder).thenComparing(SettlementPlan::id))
            .forEach(plan -> {
                if (plans.putIfAbsent(plan.id(), plan) != null) {
                    throw new IllegalStateException("Duplicate settlement id: " + plan.id());
                }
            });
        if (plans.isEmpty()) {
            throw new IllegalStateException("No packaged settlement data was found");
        }
        return plans;
    }

    private static Map<String, String> loadFacilityStructureDefaults(ServerLevel level) {
        JsonObject root = readJsonResource(level, "building_settings.json");
        JsonObject defaults = root.has("facility_defaults")
            ? root.getAsJsonObject("facility_defaults") : new JsonObject();
        Map<String, String> result = new LinkedHashMap<>();
        for (String type : List.of("pokemon_center", "pokemart", "department_store")) {
            if (defaults.has(type)) {
                result.put(type, defaults.get(type).getAsString());
            }
        }
        return Map.copyOf(result);
    }

    private static String facilityStructure(
        JsonObject structureProfile, Map<String, String> defaults,
        String type, String compatibilityFallback
    ) {
        if (structureProfile.has("facility_structures")) {
            JsonObject overrides = structureProfile.getAsJsonObject("facility_structures");
            if (overrides.has(type)) {
                return overrides.get(type).getAsString();
            }
        }
        return defaults.getOrDefault(type, compatibilityFallback);
    }

    private static int[] facilityDimensions(
        ServerLevel level, String structure, int fallbackWidth,
        int fallbackDepth, int fallbackHeight
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(structure);
        if (structureId == null) {
            return new int[] {fallbackWidth, fallbackDepth, fallbackHeight};
        }
        return level.getStructureManager().get(structureId)
            .map(template -> {
                var size = template.getSize();
                return new int[] {size.getX(), size.getZ(), size.getY()};
            })
            .orElseGet(() -> new int[] {fallbackWidth, fallbackDepth, fallbackHeight});
    }

    private static SettlementPlan parseSettlement(
        ServerLevel level, JsonObject root, Map<String, String> facilityDefaults
    ) {
        String id = requiredString(root, "id");
        boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
        int loadOrder = root.has("load_order") ? root.get("load_order").getAsInt() : Integer.MAX_VALUE;
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
        String roadLayoutTemplate = structureProfile.has("road_layout_template")
            ? requiredString(structureProfile, "road_layout_template") : "cross";
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
            ? generationProfile.get("building_density").getAsString() : "packed";
        List<String> basicBuildings = new ArrayList<>();
        if (generationProfile != null && generationProfile.has("basic_buildings")) {
            for (JsonElement element : generationProfile.getAsJsonArray("basic_buildings")) {
                basicBuildings.add(element.getAsString());
            }
        }
        boolean residentialBuildingsEnabled = generationProfile == null
            || !generationProfile.has("residential_buildings_enabled")
            || generationProfile.get("residential_buildings_enabled").getAsBoolean();
        if (basicBuildings.isEmpty() && residentialBuildingsEnabled) {
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
            String pokemonCenterStructure = facilityStructure(
                structureProfile, facilityDefaults, "pokemon_center",
                "bca:default/one_off/pokecenter"
            );
            int[] dimensions = facilityDimensions(
                level, pokemonCenterStructure, 22, 23, 15
            );
            facilities.add(new FacilityPlacement(
                "facility_pokemon_center", "direct_template",
                pokemonCenterStructure, "pokemon_center", "포켓몬센터",
                null, null, null, null, null, null, 1.5D,
                dimensions[0], dimensions[1], dimensions[2], 6
            ));
        }
        String commercialCenter = structureProfile.has("commercial_center")
            ? structureProfile.get("commercial_center").getAsString()
            : (starterSettlement ? "none" : "pokemart");
        String shopCatalogId = null;
        List<String> vendorUnits = null;
        List<ShopVendorAssignment> vendorAssignments = null;
        if (structureProfile.has("shop_configuration")) {
            JsonObject shopConfiguration = structureProfile.getAsJsonObject("shop_configuration");
            shopCatalogId = optionalString(shopConfiguration, "catalog_id");
            vendorUnits = new ArrayList<>();
            if (shopConfiguration.has("vendor_units")) {
                for (JsonElement element : shopConfiguration.getAsJsonArray("vendor_units")) {
                    vendorUnits.add(element.getAsString());
                }
            }
            if (shopConfiguration.has("assignments")) {
                vendorAssignments = new ArrayList<>();
                for (JsonElement element : shopConfiguration.getAsJsonArray("assignments")) {
                    JsonObject assignment = element.getAsJsonObject();
                    vendorAssignments.add(new ShopVendorAssignment(
                        requiredString(assignment, "slot_id"),
                        requiredString(assignment, "vendor_unit")
                    ));
                }
            }
        }
        if (commercialCenter.equals("preset")) {
            commercialCenter = "pokemart";
        }
        if (commercialCenter.equals("pokemart")) {
            String pokemartStructure = facilityStructure(
                structureProfile, facilityDefaults, "pokemart",
                "bca:default/one_off/structure_pokemart"
            );
            int[] dimensions = facilityDimensions(
                level, pokemartStructure, 23, 22, 15
            );
            facilities.add(new FacilityPlacement(
                "facility_pokemart", "direct_template",
                pokemartStructure, "pokemart", "포켓몬상점",
                null, null, null, null, null, null, 1.5D,
                dimensions[0], dimensions[1], dimensions[2], 6
            ));
        } else if (commercialCenter.equals("department_store")) {
            String departmentStoreStructure = facilityStructure(
                structureProfile, facilityDefaults, "department_store",
                "cobbleventure:facilities/department_store"
            );
            int[] dimensions = facilityDimensions(
                level, departmentStoreStructure, 42, 32, 44
            );
            facilities.add(new FacilityPlacement(
                "facility_department_store", "direct_template",
                departmentStoreStructure,
                "department_store", "백화점", null, null, null,
                null, null, null, 1.5D,
                dimensions[0], dimensions[1], dimensions[2], 8
            ));
        }
        if (structureProfile.has("facility_placements")) {
            Set<String> configuredFacilityTypes = facilities.stream()
                .map(FacilityPlacement::facilityType)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            for (JsonElement element : structureProfile.getAsJsonArray("facility_placements")) {
                JsonObject facility = element.getAsJsonObject();
                String facilityId = requiredString(facility, "id");
                String facilityType = optionalString(facility, "facility_type");
                if ((hasGymConfig && facilityId.equals("gym_building"))
                    || (hasDistrictConfig && facilityId.equals("special_district_building"))) {
                    continue;
                }
                if (facilityType != null && configuredFacilityTypes.contains(facilityType)) {
                    LOGGER.warn(
                        "Duplicate civic facility placement ignored: settlement={}, facility={}, type={}",
                        id, facilityId, facilityType
                    );
                    continue;
                }
                JsonObject footprint = facility.has("footprint")
                    ? facility.getAsJsonObject("footprint") : null;
                facilities.add(new FacilityPlacement(
                    facilityId,
                    requiredString(facility, "mode"),
                    requiredString(facility, "structure"),
                    facilityType,
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
                if (facilityType != null) {
                    configuredFacilityTypes.add(facilityType);
                }
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
        List<TownNpcPlacement> automaticNpcPlacements = parseTownNpcPlacements(root);
        return new SettlementPlan(
            id, settlementDisplayName(root, id), enabled,
            loadOrder,
            root.get("town_radius_cells").getAsInt(),
            structure, houseStyle, disableCommercialOneOff, layoutShape, roadLayoutTemplate, roadProfile,
            generationSeed, generationDepth, buildingDensity, List.copyOf(basicBuildings),
            center, structurePoint, playerSpawn,
            Map.copyOf(anchorPoints), List.copyOf(facilities), List.copyOf(gates),
            shopCatalogId, vendorUnits == null ? null : List.copyOf(vendorUnits),
            vendorAssignments == null ? null : List.copyOf(vendorAssignments),
            compiledLayout, automaticNpcPlacements
        );
    }

    private static List<TownNpcPlacement> parseTownNpcPlacements(JsonObject root) {
        if (!root.has("npc_placement")) {
            return List.of();
        }
        JsonObject placement = root.getAsJsonObject("npc_placement");
        if (!placement.has("resolved_auto_npcs")) {
            return List.of();
        }
        JsonObject resolved = placement.getAsJsonObject("resolved_auto_npcs");
        if (!resolved.has("placements")) {
            return List.of();
        }
        boolean noAmbientNpcs = false;
        if (root.has("settlement_flags") && root.get("settlement_flags").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("settlement_flags")) {
                if (element.isJsonPrimitive() && "no_ambient_npcs".equals(element.getAsString())) {
                    noAmbientNpcs = true;
                    break;
                }
            }
        }
        List<TownNpcPlacement> placements = new ArrayList<>();
        for (JsonElement element : resolved.getAsJsonArray("placements")) {
            JsonObject value = element.getAsJsonObject();
            if (noAmbientNpcs && "npc".equals(requiredString(value, "classification"))) {
                continue;
            }
            placements.add(new TownNpcPlacement(
                requiredString(value, "npc"),
                requiredString(value, "classification"),
                requiredString(value, "placement_area"),
                value.has("building") ? requiredString(value, "building") : null,
                value.has("slot") ? value.get("slot").getAsInt() : 0
            ));
        }
        return List.copyOf(placements);
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
        Map<String, List<TownRoad>> buildingAccessRoads = new LinkedHashMap<>();
        if (compiled.has("access_roads")) {
            for (JsonElement element : compiled.getAsJsonArray("access_roads")) {
                JsonObject road = element.getAsJsonObject();
                TownRoad parsed = new TownRoad(
                    road.get("x1").getAsInt(), road.get("z1").getAsInt(),
                    road.get("x2").getAsInt(), road.get("z2").getAsInt()
                );
                accessRoads.add(parsed);
                if (road.has("building")) {
                    buildingAccessRoads.computeIfAbsent(
                        requiredString(road, "building"), ignored -> new ArrayList<>()
                    ).add(parsed);
                }
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
        List<TownDecoration> decorations = new ArrayList<>();
        if (compiled.has("decorations")) {
            for (JsonElement element : compiled.getAsJsonArray("decorations")) {
                JsonObject decoration = element.getAsJsonObject();
                decorations.add(new TownDecoration(
                    requiredString(decoration, "type"),
                    decoration.get("x").getAsInt(), decoration.get("z").getAsInt(),
                    decoration.has("rotation")
                        ? requiredString(decoration, "rotation") : "none"
                ));
            }
        }
        List<Point> externalExits = new ArrayList<>();
        if (compiled.has("external_exit_points")) {
            for (JsonElement element : compiled.getAsJsonArray("external_exit_points")) {
                externalExits.add(pointFrom(element.getAsJsonObject()));
            }
        }
        buildingAccessRoads.replaceAll((ignored, roadsForBuilding) ->
            List.copyOf(roadsForBuilding)
        );
        return new TownLayout(
            List.copyOf(roads), List.copyOf(accessRoads),
            Map.copyOf(buildingAccessRoads), Map.copyOf(facilities), List.copyOf(houses),
            List.copyOf(decorations), List.copyOf(externalExits)
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
                ? plot.getAsJsonObject("road_connection").get("z").getAsInt() : 0,
            plot.has("entrance_facing")
                ? requiredString(plot, "entrance_facing")
                : facilityCanonicalEntranceFacing(requiredString(plot, "id"))
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

    private static String settlementDisplayName(JsonObject root, String id) {
        if (root.has("display_name")) {
            JsonObject names = root.getAsJsonObject("display_name");
            if (names.has("ko_kr")) {
                return names.get("ko_kr").getAsString();
            }
            if (names.has("en_us")) {
                return names.get("en_us").getAsString();
            }
        }
        String slug = id.substring(id.lastIndexOf('/') + 1);
        return Arrays.stream(slug.split("_"))
            .map(word -> word.isEmpty()
                ? word
                : Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .collect(Collectors.joining(" "));
    }

    private static String requiredString(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string field: " + key);
        }
        return object.get(key).getAsString();
    }

    private static String localizedString(JsonObject object, String key, String locale) {
        if (object == null || !object.has(key)) {
            throw new IllegalArgumentException("Missing localized field: " + key);
        }
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive()) return value.getAsString();
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Invalid localized field: " + key);
        }
        JsonObject localized = value.getAsJsonObject();
        if (localized.has(locale) && localized.get(locale).isJsonPrimitive()) {
            return localized.get(locale).getAsString();
        }
        if (localized.has("ko_kr") && localized.get("ko_kr").isJsonPrimitive()) {
            return localized.get("ko_kr").getAsString();
        }
        if (localized.has("en_us") && localized.get("en_us").isJsonPrimitive()) {
            return localized.get("en_us").getAsString();
        }
        throw new IllegalArgumentException("Localized field has no text: " + key);
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
        return attachCaveDestinations(
            level, parseHexWorldPlan(root, townRadii, profiles, seed)
        );
    }

    private static HexWorldPlan attachCaveDestinations(
        ServerLevel level, HexWorldPlan world
    ) {
        Map<String, JsonObject> caves = new HashMap<>();
        Map<String, JsonObject> undergroundRoads = new HashMap<>();
        List<CaveEntrancePlan> entrances = new ArrayList<>();
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            if (isUndergroundRoad(entrance)) {
                JsonObject road = undergroundRoads.computeIfAbsent(
                    entrance.cave(), roadId -> UndergroundRoadSystem.loadDocument(level, roadId)
                );
                UndergroundRoadSystem.ConnectionTarget target = UndergroundRoadSystem.resolveConnection(
                    level, road, entrance.undergroundModule(), entrance.undergroundConnector()
                );
                ACTIVE_UNDERGROUND_DUNGEON_EXITS.put(
                    entrance.id(), new TransitionRegion(target.transitionBlocks())
                );
                entrances.add(new CaveEntrancePlan(
                    entrance.id(), entrance.cave(), entrance.entrance(),
                    entrance.surfaceTransition(), entrance.undergroundModule(),
                    entrance.undergroundConnector(), entrance.anchor(),
                    entrance.facing(), entrance.structure(), entrance.structureVariants(),
                    entrance.pokemonCenterEnabled(), entrance.pokemonCenterStructure(),
                    entrance.pokemonCenterOffset(), target.destination(), target.portalAnchor(),
                    NaturalCaveGenerator.Settings.defaults()
                ));
                continue;
            }
            JsonObject cave = caves.computeIfAbsent(entrance.cave(), caveId -> {
                String slug = caveId.substring(caveId.lastIndexOf('/') + 1);
                return readJsonResource(level, "caves/generation_1/" + slug + ".json");
            });
            BlockPoint destination = null;
            BlockPoint portalAnchor = null;
            for (JsonElement element : cave.getAsJsonArray("entrances")) {
                JsonObject configured = element.getAsJsonObject();
                if (requiredString(configured, "id").equals(entrance.entrance())) {
                    portalAnchor = blockPointFrom(configured.getAsJsonObject("destination_anchor"));
                    destination = blockPointFrom(configured.getAsJsonObject("fallback_anchor"));
                    break;
                }
            }
            if (destination == null || portalAnchor == null) {
                throw new IllegalStateException(
                    "Cave entrance destination is missing: " + entrance.id()
                );
            }
            entrances.add(new CaveEntrancePlan(
                entrance.id(), entrance.cave(), entrance.entrance(),
                entrance.surfaceTransition(), null, null,
                entrance.anchor(),
                entrance.facing(), entrance.structure(), entrance.structureVariants(),
                entrance.pokemonCenterEnabled(),
                entrance.pokemonCenterStructure(),
                entrance.pokemonCenterOffset(), destination, portalAnchor,
                caveGenerationSettings(cave)
            ));
        }
        JsonObject biomeProfiles = readJsonResource(level, "catalogs/biome-profiles.json");
        JsonObject pokemonHabitats = readJsonResource(level, "catalogs/pokemon-habitats.json");
        List<PursuitEncounterZone> caveEncounters = new ArrayList<>();
        for (Map.Entry<String, JsonObject> entry : caves.entrySet()) {
            PursuitEncounterSystem.Config config = PursuitEncounterSystem.parse(
                entry.getKey(), entry.getValue(), biomeProfiles, pokemonHabitats
            );
            if (config == null) continue;
            JsonObject bounds = entry.getValue().getAsJsonObject("dimension").getAsJsonObject("bounds");
            caveEncounters.add(new PursuitEncounterZone(
                config, bounds.get("min_x").getAsInt(), bounds.get("min_z").getAsInt(),
                bounds.get("max_x").getAsInt(), bounds.get("max_z").getAsInt()
            ));
        }
        Map<String, JsonObject> forests = new HashMap<>();
        Map<String, PursuitEncounterSystem.Config> forestEncounters = new HashMap<>();
        List<WorldGateSystem.Gate> gates = new ArrayList<>();
        for (WorldGateSystem.Gate gate : world.gates()) {
            String forestId = gate.destinationForest();
            if (forestId == null || forestId.isBlank()) {
                gates.add(gate);
                continue;
            }
            String slug = forestId.substring(forestId.lastIndexOf('/') + 1);
            JsonObject forest = forests.computeIfAbsent(forestId, ignored ->
                readJsonResource(level, "forests/generation_1/" + slug + ".json")
            );
            JsonObject origin = forest.getAsJsonObject("dimension").getAsJsonObject("origin");
            int originX = origin.get("x").getAsInt();
            int originY = origin.get("y").getAsInt();
            int originZ = origin.get("z").getAsInt();
            BlockPoint portalAnchor = null;
            BlockPoint destination = null;
            for (JsonElement element : forest.getAsJsonArray("entrances")) {
                JsonObject configured = element.getAsJsonObject();
                if (!requiredString(configured, "id").equals(gate.destinationEntrance())) {
                    continue;
                }
                JsonObject position = configured.getAsJsonObject("position");
                int portalX = originX + position.get("x").getAsInt();
                int portalZ = originZ + position.get("z").getAsInt();
                portalAnchor = new BlockPoint(portalX, originY, portalZ);
                destination = inwardDestination(
                    portalX, originY, portalZ, originX, originZ
                );
                break;
            }
            if (portalAnchor == null || destination == null) {
                throw new IllegalStateException(
                    "Forest entrance destination is missing: " + gate.id()
                );
            }
            ResourceLocation forestDimensionId = ResourceLocation.parse(
                requiredString(forest.getAsJsonObject("dimension"), "id")
            );
            ResourceKey<Level> forestDimension = ResourceKey.create(
                Registries.DIMENSION, forestDimensionId
            );
            gates.add(gate.withForestDestination(forestDimension, destination, portalAnchor));
            if (!forestEncounters.containsKey(forestId)) {
                PursuitEncounterSystem.Config config = PursuitEncounterSystem.parse(
                    forestId, forest, biomeProfiles, pokemonHabitats
                );
                if (config != null) forestEncounters.put(forestId, config);
            }
        }
        activeCaveEncounters = List.copyOf(caveEncounters);
        activeCaveDocuments = Map.copyOf(caves);
        activeUndergroundRoadDocuments = Map.copyOf(undergroundRoads);
        activeForestEncounters = Map.copyOf(forestEncounters);
        activeForestDocuments = Map.copyOf(forests);
        activeForestRegions = forests.entrySet().stream()
            .map(entry -> {
                JsonObject forest = entry.getValue();
                JsonObject dimension = forest.getAsJsonObject("dimension");
                JsonObject origin = dimension.getAsJsonObject("origin");
                JsonObject bounds = dimension.getAsJsonObject("bounds");
                return new ForestRegion(
                    entry.getKey(),
                    ResourceKey.create(
                        Registries.DIMENSION,
                        ResourceLocation.parse(requiredString(dimension, "id"))
                    ),
                    origin.get("x").getAsInt() + bounds.get("min_x").getAsInt(),
                    origin.get("z").getAsInt() + bounds.get("min_z").getAsInt(),
                    origin.get("x").getAsInt() + bounds.get("max_x").getAsInt(),
                    origin.get("z").getAsInt() + bounds.get("max_z").getAsInt()
                );
            })
            .toList();
        return new HexWorldPlan(
            world.grid(), world.seed(), world.cells(), world.paths(), world.settlements(),
            world.boundaryProfiles(), world.defaultEmptyTerrain(), world.emptyTerrainTiles(),
            world.environmentOverrides(), world.levelOverrides(),
            List.copyOf(entrances), List.copyOf(gates), world.worldStructures()
        );
    }

    private static PursuitEncounterSystem.Config pursuitEncounterAt(
        ServerLevel level, double x, double z
    ) {
        if (level.dimension().equals(DUNGEONS)) {
            return activeCaveEncounters.stream()
                .filter(zone -> zone.contains(x, z))
                .map(PursuitEncounterZone::config)
                .findFirst().orElse(null);
        }
        PursuitEncounterSystem.Config forestEncounter = activeForestRegions.stream()
            .filter(region -> region.contains(level, x, z))
            .map(region -> activeForestEncounters.get(region.forestId()))
            .filter(Objects::nonNull)
            .findFirst().orElse(null);
        if (forestEncounter != null) return forestEncounter;
        HexWorldPlan world = activeHexWorld;
        if (!level.dimension().equals(GENERATION_ONE) || world == null) return null;
        HexCoord coordinate = world.grid().worldToHex(x, z);
        if (!"dense_forest".equals(world.emptyTerrainTiles().get(coordinate))) return null;
        return world.gates().stream()
            .filter(gate -> gate.destinationForest() != null
                && activeForestEncounters.containsKey(gate.destinationForest()))
            .min(Comparator.comparingDouble(gate -> {
                Point center = world.grid().worldCenter(gate.anchor());
                double dx = center.x() - x;
                double dz = center.z() - z;
                return dx * dx + dz * dz;
            }))
            .map(gate -> activeForestEncounters.get(gate.destinationForest()))
            .orElse(null);
    }

    private static String caveIdAt(double x, double z) {
        return activeCaveDocuments.entrySet().stream()
            .filter(entry -> {
                JsonObject dimension = entry.getValue().getAsJsonObject("dimension");
                JsonObject bounds = dimension.getAsJsonObject("bounds");
                return x >= bounds.get("min_x").getAsInt()
                    && x <= bounds.get("max_x").getAsInt()
                    && z >= bounds.get("min_z").getAsInt()
                    && z <= bounds.get("max_z").getAsInt();
            })
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    /** Authored encounter weights used by display-only integrations such as CobbleNav. */
    public static Map<ResourceLocation, Integer> authoredEncounterWeights(
        ServerLevel level, double x, double z
    ) {
        PursuitEncounterSystem.Config config = pursuitEncounterAt(level, x, z);
        if (config == null) return null;
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (PursuitEncounterSystem.SpeciesChoice choice : config.species()) {
            ResourceLocation species = ResourceLocation.tryParse(choice.species());
            if (species != null) result.put(species, choice.weight());
        }
        return Map.copyOf(result);
    }

    static List<RadarLocationCatalog.Location> radarWorldLocations(ServerLevel level) {
        HexWorldPlan world = activeHexWorld;
        if (world == null) return List.of();
        boolean generationDimension = level.dimension().equals(GENERATION_ONE);
        List<RadarLocationCatalog.Location> result = new ArrayList<>(
            WorldGateSystem.radarLocations(level, world, generationDimension)
        );
        if (!generationDimension) return List.copyOf(result);

        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
            int y = plannedCaveMouthFloorY(level, mouth.x(), mouth.z());
            if (!level.getBlockState(new BlockPos(mouth.x(), y - 2, mouth.z()))
                .is(Blocks.LODESTONE)) continue;
            result.add(new RadarLocationCatalog.Location(
                "cave/" + entrance.id(),
                RadarLocationCatalog.Kind.CAVE_ENTRANCE,
                level.dimension().location(),
                mouth.x() + 0.5D,
                y,
                mouth.z() + 0.5D,
                entrance.cave(),
                entrance.cave()
            ));
        }
        return List.copyOf(result);
    }

    private record PursuitEncounterZone(
        PursuitEncounterSystem.Config config, int minX, int minZ, int maxX, int maxZ
    ) {
        private boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private record ForestRegion(
        String forestId, ResourceKey<Level> dimension,
        int minX, int minZ, int maxX, int maxZ
    ) {
        private boolean contains(ServerLevel level, double x, double z) {
            return level.dimension().equals(dimension)
                && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static void spawnCaveNpcs(
        ServerLevel level, Map<String, JsonObject> caves
    ) {
        for (Map.Entry<String, JsonObject> entry : caves.entrySet()) {
            JsonObject cave = entry.getValue();
            if (!cave.has("trainer_settings")) continue;
            JsonObject settings = cave.getAsJsonObject("trainer_settings");
            if (!settings.has("enabled") || !settings.get("enabled").getAsBoolean()) continue;
            int maximum = Math.max(0, settings.get("max_active").getAsInt());
            int placed = 0;
            if (settings.has("placements")) {
                for (JsonElement element : settings.getAsJsonArray("placements")) {
                    if (placed >= maximum) break;
                    JsonObject placement = element.getAsJsonObject();
                    JsonObject position = placement.getAsJsonObject("position");
                    String trainerId = requiredString(placement, "trainer_id");
                    String trigger = placement.has("trigger_override")
                        ? placement.get("trigger_override").getAsString()
                        : regionalTrigger(settings, trainerId);
                    if (spawnRegionalNpc(
                        level, trainerId,
                        new BlockPos(
                            position.get("x").getAsInt(), position.get("y").getAsInt(),
                            position.get("z").getAsInt()
                        ), 0.0F, trigger
                    )) placed++;
                }
            }
            List<String> candidates = regionalTrainerCandidates(level, cave, settings);
            List<BlockPos> positions = caveTrainerPositions(cave);
            for (int index = 0; placed < maximum && index < candidates.size()
                && index < positions.size(); index++) {
                if (spawnRegionalNpc(
                    level, candidates.get(index), positions.get(index),
                    index % 2 == 0 ? 90.0F : -90.0F,
                    regionalTrigger(settings, candidates.get(index))
                )) placed++;
            }
            if (placed > 0) LOGGER.info("Cave NPC placement completed: cave={}, spawned={}", entry.getKey(), placed);
        }
    }

    private static List<BlockPos> caveTrainerPositions(JsonObject cave) {
        List<BlockPos> result = new ArrayList<>();
        List<BlockPos> entrances = caveEntrancePositions(cave);
        if (cave.has("generator")) {
            JsonObject generator = cave.getAsJsonObject("generator");
            if (generator.has("manual_layout")) {
                JsonObject layout = generator.getAsJsonObject("manual_layout");
                if (layout.has("anchors")) {
                    for (JsonElement element : layout.getAsJsonArray("anchors")) {
                        JsonObject position = element.getAsJsonObject().getAsJsonObject("position");
                        for (int offset : new int[] {3, -3}) {
                            BlockPos candidate = new BlockPos(
                                position.get("x").getAsInt() + offset,
                                position.get("y").getAsInt() + 1,
                                position.get("z").getAsInt()
                            );
                            if (!isNearRegionalEntrance(candidate, entrances)) {
                                result.add(candidate);
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> caveEntrancePositions(JsonObject cave) {
        if (!cave.has("entrances")) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (JsonElement element : cave.getAsJsonArray("entrances")) {
            JsonObject anchor = element.getAsJsonObject().getAsJsonObject("destination_anchor");
            result.add(new BlockPos(
                anchor.get("x").getAsInt(), anchor.get("y").getAsInt(),
                anchor.get("z").getAsInt()
            ));
        }
        return List.copyOf(result);
    }

    private static boolean isNearRegionalEntrance(
        BlockPos candidate, List<BlockPos> entrances
    ) {
        int clearanceSquared = REGIONAL_NPC_ENTRANCE_CLEARANCE
            * REGIONAL_NPC_ENTRANCE_CLEARANCE;
        return entrances.stream().anyMatch(entrance -> {
            int dx = candidate.getX() - entrance.getX();
            int dz = candidate.getZ() - entrance.getZ();
            return dx * dx + dz * dz < clearanceSquared;
        });
    }

    private static void spawnForestNpcs(
        ServerLevel level, Map<String, JsonObject> forests
    ) {
        for (Map.Entry<String, JsonObject> entry : forests.entrySet()) {
            JsonObject forest = entry.getValue();
            if (!forest.has("trainer_settings")) continue;
            JsonObject settings = forest.getAsJsonObject("trainer_settings");
            if (!settings.has("enabled") || !settings.get("enabled").getAsBoolean()) continue;
            List<String> candidates = regionalTrainerCandidates(level, forest, settings);
            int maximum = Math.min(
                Math.max(0, settings.get("max_active").getAsInt()), candidates.size()
            );
            if (maximum == 0) continue;
            JsonObject origin = forest.getAsJsonObject("dimension").getAsJsonObject("origin");
            int originX = origin.get("x").getAsInt();
            int originY = origin.get("y").getAsInt();
            int originZ = origin.get("z").getAsInt();
            Map<String, JsonObject> uniquePoints = new LinkedHashMap<>();
            for (JsonElement pathElement : forest.getAsJsonArray("paths")) {
                for (JsonElement pointElement : pathElement.getAsJsonObject().getAsJsonArray("points")) {
                    JsonObject point = pointElement.getAsJsonObject();
                    if (isNearForestEntrance(forest, point)) continue;
                    uniquePoints.putIfAbsent(
                        point.get("x").getAsInt() + "," + point.get("z").getAsInt(), point
                    );
                }
            }
            List<JsonObject> points = List.copyOf(uniquePoints.values());
            int spawned = 0;
            for (int index = 0; spawned < maximum && index < points.size(); index++) {
                int pointIndex = Math.min(
                    points.size() - 1,
                    Math.round((index + 1) * (points.size() - 1.0F) / (maximum + 1))
                );
                JsonObject point = points.get(pointIndex);
                int x = originX + point.get("x").getAsInt() + (index % 2 == 0 ? 2 : -2);
                int z = originZ + point.get("z").getAsInt();
                if (spawnRegionalNpc(
                    level, candidates.get(spawned), new BlockPos(x, originY, z),
                    index % 2 == 0 ? 90.0F : -90.0F,
                    regionalTrigger(settings, candidates.get(spawned))
                )) spawned++;
            }
            if (spawned > 0) LOGGER.info("Forest NPC placement completed: forest={}, spawned={}", entry.getKey(), spawned);
        }
    }

    private static boolean isNearForestEntrance(JsonObject forest, JsonObject point) {
        if (!forest.has("entrances")) return false;
        int x = point.get("x").getAsInt();
        int z = point.get("z").getAsInt();
        int clearanceSquared = REGIONAL_NPC_ENTRANCE_CLEARANCE
            * REGIONAL_NPC_ENTRANCE_CLEARANCE;
        for (JsonElement element : forest.getAsJsonArray("entrances")) {
            JsonObject entrance = element.getAsJsonObject().getAsJsonObject("position");
            int dx = x - entrance.get("x").getAsInt();
            int dz = z - entrance.get("z").getAsInt();
            if (dx * dx + dz * dz < clearanceSquared) return true;
        }
        return false;
    }

    private static String regionalTrigger(JsonObject settings, String trainerId) {
        if (settings.has("trainer_trigger_overrides")) {
            JsonObject overrides = settings.getAsJsonObject("trainer_trigger_overrides");
            if (overrides.has(trainerId)) return overrides.get(trainerId).getAsString();
        }
        return settings.has("trigger_override")
            ? settings.get("trigger_override").getAsString() : "proximity";
    }

    private static List<String> regionalTrainerCandidates(
        ServerLevel level, JsonObject region, JsonObject settings
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (settings.has("direct_trainers")) {
            for (JsonElement element : settings.getAsJsonArray("direct_trainers")) {
                result.add(element.getAsString());
            }
        }
        if (settings.has("use_biome_defaults")
            && !settings.get("use_biome_defaults").getAsBoolean()) {
            return List.copyOf(result);
        }
        JsonObject catalog = readJsonResource(level, "catalogs/npc-placement-profiles.json");
        List<JsonObject> profiles = new ArrayList<>();
        for (JsonElement element : catalog.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            if (!"trainer".equals(requiredString(profile, "classification"))) continue;
            if (profile.has("automatic_route_placement")
                && profile.get("automatic_route_placement").getAsBoolean()) {
                profiles.add(profile);
            }
        }
        int levelTarget = 5;
        String biome = "";
        if (region.has("random_encounters")) {
            JsonObject encounters = region.getAsJsonObject("random_encounters");
            levelTarget = Math.round((
                encounters.get("minimum_level").getAsInt()
                    + encounters.get("maximum_level").getAsInt()
            ) / 2.0F);
            biome = encounters.has("pokemon_biome")
                ? encounters.get("pokemon_biome").getAsString() : "";
        }
        final int expectedLevel = levelTarget;
        final String expectedBiome = biome;
        profiles.sort(Comparator
            .comparingInt((JsonObject profile) -> regionalTrainerScore(
                profile, expectedLevel, expectedBiome
            ))
            .thenComparing(profile -> requiredString(profile, "npc")));
        for (JsonObject profile : profiles) result.add(requiredString(profile, "npc"));
        return List.copyOf(result);
    }

    private static int regionalTrainerScore(
        JsonObject profile, int expectedLevel, String expectedBiome
    ) {
        int biomeScore = 20;
        if (profile.has("preferred_biomes")) {
            JsonArray preferred = profile.getAsJsonArray("preferred_biomes");
            biomeScore = preferred.size() == 0 ? 20 : 100;
            for (JsonElement element : preferred) {
                if (element.getAsString().equals(expectedBiome)) {
                    biomeScore = 0;
                    break;
                }
            }
        }
        int levelScore = profile.has("expected_level")
            ? Math.abs(profile.get("expected_level").getAsInt() - expectedLevel) : 15;
        return biomeScore + levelScore;
    }

    private static NaturalCaveGenerator.Settings caveGenerationSettings(JsonObject cave) {
        boolean requiresFlash = cave.has("requires_flash")
            && cave.get("requires_flash").getAsBoolean();
        if (!cave.has("generator")) {
            return NaturalCaveGenerator.Settings.defaults(requiresFlash);
        }
        JsonObject generator = cave.getAsJsonObject("generator");
        JsonObject roomRadius = generator.getAsJsonObject("room_radius");
        JsonObject tunnelRadius = generator.getAsJsonObject("tunnel_radius");
        NaturalCaveGenerator.Settings defaults = NaturalCaveGenerator.Settings.defaults(requiresFlash);
        String style = cave.has("style") ? cave.get("style").getAsString() : "rock";
        String internalBiome = switch (style) {
            case "lush" -> "minecraft:lush_caves";
            case "ice" -> "minecraft:deep_frozen_ocean";
            case "lava" -> "minecraft:basalt_deltas";
            default -> "minecraft:dripstone_caves";
        };
        String decoration = switch (style) {
            case "dripstone", "crystal", "lush", "ice", "lava" -> style;
            default -> "rock";
        };
        NaturalCaveGenerator.ManualLayout manualLayout = NaturalCaveGenerator.ManualLayout.disabled();
        if (generator.has("manual_layout")) {
            JsonObject manual = generator.getAsJsonObject("manual_layout");
            List<NaturalCaveGenerator.ManualAnchor> anchors = new ArrayList<>();
            for (JsonElement element : manual.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                JsonObject position = anchor.getAsJsonObject("position");
                anchors.add(new NaturalCaveGenerator.ManualAnchor(
                    requiredString(anchor, "id"), requiredString(anchor, "kind"),
                    position.get("x").getAsInt(), position.get("y").getAsInt(), position.get("z").getAsInt(),
                    anchor.get("radius_x").getAsDouble(), anchor.get("radius_z").getAsDouble(),
                    anchor.get("height").getAsDouble(),
                    anchor.has("room_type") ? anchor.get("room_type").getAsString() : ""
                ));
            }
            List<NaturalCaveGenerator.ManualConnection> connections = new ArrayList<>();
            for (JsonElement element : manual.getAsJsonArray("connections")) {
                JsonObject connection = element.getAsJsonObject();
                connections.add(new NaturalCaveGenerator.ManualConnection(
                    requiredString(connection, "id"), requiredString(connection, "from"),
                    requiredString(connection, "to"), requiredString(connection, "kind"),
                    connection.get("width").getAsInt(),
                    connection.has("path_type") ? connection.get("path_type").getAsString() : ""
                ));
            }
            manualLayout = new NaturalCaveGenerator.ManualLayout(
                manual.get("enabled").getAsBoolean(), List.copyOf(anchors), List.copyOf(connections)
            );
        }
        List<NaturalCaveGenerator.RoomType> roomTypes = List.of(
            new NaturalCaveGenerator.RoomType(style, 100, decoration, 1.0D, 1.0D)
        );
        List<NaturalCaveGenerator.PathType> pathTypes = defaults.pathTypes();
        if (generator.has("path_types")) {
            pathTypes = new ArrayList<>();
            for (JsonElement element : generator.getAsJsonArray("path_types")) {
                JsonObject type = element.getAsJsonObject();
                pathTypes.add(new NaturalCaveGenerator.PathType(
                    requiredString(type, "id"), type.get("weight").getAsInt(),
                    type.get("width").getAsInt(), requiredString(type, "floor")
                ));
            }
            pathTypes = List.copyOf(pathTypes);
        }
        NaturalCaveGenerator.DecorationSettings decorations = defaults.decorations();
        if (generator.has("decorations")) {
            JsonObject configured = generator.getAsJsonObject("decorations");
            decorations = new NaturalCaveGenerator.DecorationSettings(
                configured.get("cluster_density").getAsDouble(),
                configured.get("patch_radius").getAsJsonObject().get("min").getAsInt(),
                configured.get("patch_radius").getAsJsonObject().get("max").getAsInt(),
                configured.get("dripstone_length").getAsJsonObject().get("min").getAsInt(),
                configured.get("dripstone_length").getAsJsonObject().get("max").getAsInt(),
                configured.get("route_clearance").getAsInt()
            );
        }
        return new NaturalCaveGenerator.Settings(
            generator.get("seed_salt").getAsLong(),
            style,
            generator.get("main_rooms").getAsInt(),
            generator.get("branch_count").getAsInt(),
            generator.get("loop_chance").getAsDouble(),
            generator.get("vertical_range").getAsInt(),
            roomRadius.get("min").getAsDouble(),
            roomRadius.get("max").getAsDouble(),
            tunnelRadius.get("min").getAsDouble(),
            tunnelRadius.get("max").getAsDouble(),
            generator.get("surface_roughness").getAsDouble(),
            generator.get("water_level").getAsInt(),
            generator.has("water_depth") ? generator.get("water_depth").getAsInt() : 8,
            generator.has("grand_room_scale") ? generator.get("grand_room_scale").getAsDouble() : 1.65D,
            generator.has("elevated_crossing") && generator.get("elevated_crossing").getAsBoolean(),
            generator.has("bridge_clearance") ? generator.get("bridge_clearance").getAsInt() : 13,
            requiresFlash,
            manualLayout,
            List.of(internalBiome),
            roomTypes,
            pathTypes,
            decorations
        );
    }

    static HexWorldPlan parseHexWorldPlan(
        JsonObject root,
        Map<String, Integer> townRadii,
        Map<String, BoundaryProfile> profiles,
        long seed
    ) {
        HexGrid grid = WorldPlanParser.grid(root);
        List<HexSettlement> hexSettlements = WorldPlanParser.settlements(
            root, townRadii, profiles
        );
        List<HexConnection> connections = WorldPlanParser.connections(root, profiles);
        List<PlacedTile> placedTiles = WorldPlanParser.tiles(root, profiles);
        List<CaveEntrancePlan> caveEntrances = WorldPlanParser.caveEntrances(root);
        WorldPlanParser.EmptyTerrain emptyTerrain = WorldPlanParser.emptyTerrain(root);
        String defaultEmptyTerrain = emptyTerrain.defaultType();
        Map<HexCoord, String> emptyTerrainTiles = emptyTerrain.tiles();
        Map<HexCoord, EnvironmentOverride> environmentOverrides =
            WorldPlanParser.environmentOverrides(root);
        Map<HexCoord, Integer> levelOverrides = WorldPlanParser.levelOverrides(root);
        List<WorldGateSystem.Gate> gates = new ArrayList<>();
        List<WorldStructureSystem.WorldStructure> worldStructures = new ArrayList<>();
        if (root.has("objects")) {
            gates.addAll(WorldGateSystem.parse(root.getAsJsonArray("objects")));
            worldStructures.addAll(WorldStructureSystem.parse(root.getAsJsonArray("objects")));
        }
        if (root.has("forest_entrances")) {
            gates.addAll(WorldGateSystem.parseForestEntrances(root.getAsJsonArray("forest_entrances")));
        }
        HexWorldPlan plan = planHexWorld(
            grid, seed, List.copyOf(hexSettlements), List.copyOf(connections),
            List.copyOf(placedTiles), defaultEmptyTerrain,
            Map.copyOf(emptyTerrainTiles), Map.copyOf(environmentOverrides),
            Map.copyOf(levelOverrides), profiles,
            List.copyOf(caveEntrances), gates, List.copyOf(worldStructures)
        );
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

    private static Map<String, BoundaryProfile> loadBoundaryProfiles(ServerLevel level) {
        JsonObject root = readJsonResource(level, "catalogs/boundary-profiles.json");
        return WorldPlanParser.boundaryProfiles(root);
    }

    private static HexWorldPlan planHexWorld(
        HexGrid grid,
        long seed,
        List<HexSettlement> settlements,
        List<HexConnection> connections,
        List<PlacedTile> placedTiles,
        String defaultEmptyTerrain,
        Map<HexCoord, String> emptyTerrainTiles,
        Map<HexCoord, EnvironmentOverride> environmentOverrides,
        Map<HexCoord, Integer> levelOverrides,
        Map<String, BoundaryProfile> profiles,
        List<CaveEntrancePlan> caveEntrances,
        List<WorldGateSystem.Gate> gates,
        List<WorldStructureSystem.WorldStructure> worldStructures
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
                connection.id(), connection.displayName(), connection.from(), connection.to(),
                connection.routeBiome(), connection.boundaryProfile(),
                connection.corridorWidthBlocks(), connection.edgeNoise(), connection.terrainProfile(),
                connection.surfaceStyle(), connection.accessRequirement(), List.copyOf(path),
                centerline, routeBounds(centerline), connection.pokemonSpawns(),
                connection.npcPlacements(), connection.trainerPopulation()
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
            defaultEmptyTerrain, emptyTerrainTiles, environmentOverrides,
            levelOverrides, caveEntrances, gates, worldStructures
        );
    }

    private static void verifyRouteCenterlines(
        HexGrid grid,
        Map<String, HexSettlement> settlements,
        List<ConnectionPath> paths
    ) {
        for (ConnectionPath path : paths) {
            if (path.centerline().size() < 2) {
                throw new IllegalStateException(
                    "Route centerline is too short: " + path.id()
                );
            }
            verifyRouteEndpoint(
                grid, settlements.get(path.from()), path.centerline().getFirst(),
                path.id(), "from"
            );
            verifyRouteEndpoint(
                grid, settlements.get(path.to()), path.centerline().getLast(),
                path.id(), "to"
            );
            if (path.surfaceStyle().equals("log_bridge")) {
                Set<HexCoord> routeArea = new HashSet<>(path.cells());
                HexSettlement from = settlements.get(path.from());
                HexSettlement to = settlements.get(path.to());
                if (from != null) routeArea.addAll(townFootprint(from));
                if (to != null) routeArea.addAll(townFootprint(to));
                Point first = path.centerline().getFirst();
                if (!routeArea.contains(grid.worldToHex(first.x(), first.z()))) {
                    throw new IllegalStateException(
                        "Log bridge left its authored hex tiles: route=" + path.id()
                            + ", point=" + first
                    );
                }
                for (int index = 1; index < path.centerline().size(); index++) {
                    Point previous = path.centerline().get(index - 1);
                    Point current = path.centerline().get(index);
                    if (previous.x() != current.x()
                        && previous.z() != current.z()) {
                        throw new IllegalStateException(
                            "Log bridge segment is not axis-aligned: route=" + path.id()
                                + ", from=" + previous + ", to=" + current
                        );
                    }
                    if (!routeArea.contains(grid.worldToHex(current.x(), current.z()))) {
                        throw new IllegalStateException(
                            "Log bridge left its authored hex tiles: route=" + path.id()
                                + ", point=" + current
                        );
                    }
                }
            }
        }
        verifySharedLogBridgeJunctions(paths);
    }

    private static void verifySharedLogBridgeJunctions(List<ConnectionPath> paths) {
        for (int leftIndex = 0; leftIndex < paths.size(); leftIndex++) {
            ConnectionPath left = paths.get(leftIndex);
            if (!left.surfaceStyle().equals("log_bridge")) continue;
            Set<HexCoord> leftCells = Set.copyOf(left.cells());
            Set<Point> leftPoints = Set.copyOf(left.centerline());
            for (int rightIndex = leftIndex + 1; rightIndex < paths.size(); rightIndex++) {
                ConnectionPath right = paths.get(rightIndex);
                if (!right.surfaceStyle().equals("log_bridge")
                    || right.cells().stream().noneMatch(leftCells::contains)) {
                    continue;
                }
                if (right.centerline().stream().noneMatch(leftPoints::contains)) {
                    throw new IllegalStateException(
                        "Log bridge routes sharing a world cell do not meet: left="
                            + left.id() + ", right=" + right.id()
                    );
                }
            }
        }
    }

    private static void verifyRouteEndpoint(
        HexGrid grid,
        HexSettlement settlement,
        Point endpoint,
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
        double expectedDistance = townRouteEdgeRadius(
            grid, settlement,
            endpoint.x() - center.x(), endpoint.z() - center.z()
        );
        if (Math.abs(distance - expectedDistance) > 2.0D) {
            throw new IllegalStateException(
                "Route endpoint is not anchored at the town edge: "
                    + routeId + " (" + side + ", distance=" + distance
                    + ", expected=" + expectedDistance + ")"
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
        return TOWN_FOOTPRINT_CENTERS.computeIfAbsent(
            new TownFootprintCenterKey(grid, settlement.settlement()),
            ignored -> computeTownFootprintWorldCenter(grid, settlement)
        );
    }

    private static Point computeTownFootprintWorldCenter(
        HexGrid grid, HexSettlement settlement
    ) {
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
        for (HexSettlement hex : world.settlements().values()) {
            SettlementPlan settlement = settlements.get(hex.settlement());
            if (settlement == null) {
                throw new IllegalStateException("Hex world references missing settlement: " + hex.settlement());
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
                settlement.displayName(),
                settlement.enabled(),
                settlement.loadOrder(),
                settlement.townRadiusCells(),
                settlement.structure(),
                settlement.houseStyle(),
                settlement.disableCommercialOneOff(),
                settlement.layoutShape(),
                settlement.roadLayoutTemplate(),
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
                settlement.shopCatalogId(),
                settlement.vendorUnits(),
                settlement.vendorAssignments(),
                settlement.compiledLayout(),
                settlement.automaticNpcPlacements()
            ));
        }
        Map<String, SettlementPlan> ordered = new LinkedHashMap<>();
        translated.values().stream()
            .sorted(Comparator.comparingInt(SettlementPlan::loadOrder).thenComparing(SettlementPlan::id))
            .forEach(settlement -> ordered.put(settlement.id(), settlement));
        return Collections.unmodifiableMap(ordered);
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
        progress.update(79, "월드 상단 베리어 천장 생성 중");
        drawHiddenWorldCeiling(level, world, bounds);
        profiler.finishPhase("hidden-world-ceiling");
        progress.update(80, "접근 불가 지역 마감 중");
        profiler.finishPhase("sealed-outer-decoration");
        progress.update(83, "직접 배치 길 생성 중");
        drawHexRoads(level, world);
        profiler.finishPhase("route-rendering");
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
            warpedHexAt(world, x, z),
            world.defaultEmptyTerrain()
        );
    }

    private static HexCoord warpedHexAt(
        HexWorldPlan world, double x, double z
    ) {
        WarpedPoint warped = warpedCellPoint(world, x, z);
        return world.grid().worldToHex(warped.x(), warped.z());
    }

    static String emptyTerrainBiome(String type) {
        return switch (type) {
            case "ocean" -> "minecraft:ocean";
            case "deep_ocean" -> "minecraft:deep_ocean";
            case "desert" -> "minecraft:desert";
            case "stone_mountain" -> "minecraft:stony_peaks";
            case "red_rock_mountain" -> "minecraft:badlands";
            case "snow_mountain" -> "minecraft:snowy_slopes";
            case "high_forest" -> "minecraft:dark_forest";
            case "dense_forest" -> "minecraft:old_growth_spruce_taiga";
            default -> SEALED_DARK_FOREST.location().toString();
        };
    }

    static boolean isEmptyOceanType(String type) {
        return type.equals("ocean") || type.equals("deep_ocean");
    }

    static String emptyTerrainBiome(HexWorldPlan world, double x, double z) {
        String type = emptyTerrainAt(world, x, z);
        if ((type.equals("high_forest") || type.equals("dense_forest"))
            && isSealedForestEdge(world, x, z)) {
            return SEALED_FOREST_EDGE.location().toString();
        }
        return emptyTerrainBiome(type);
    }

    private static boolean isSealedForestEdge(
        HexWorldPlan world, double x, double z
    ) {
        HexCoord current = world.grid().worldToHex(x, z);
        if (!world.cells().containsKey(current)
            && current.neighbors().stream().noneMatch(world.cells()::containsKey)) {
            return false;
        }
        double edgeNoise = layeredNoise(
            world.seed(), "world:sealed-forest-edge", x, z, 34.0D
        );
        int maximumDistance = 26 + (int) Math.round(edgeNoise * 5.0D);
        double[][] directions = {
            {1.0D, 0.0D}, {0.7071D, 0.7071D}, {0.0D, 1.0D}, {-0.7071D, 0.7071D},
            {-1.0D, 0.0D}, {-0.7071D, -0.7071D}, {0.0D, -1.0D}, {0.7071D, -0.7071D}
        };
        for (int distance = 2; distance <= maximumDistance; distance += 2) {
            for (double[] direction : directions) {
                TerrainSample nearby = terrainAt(
                    world, x + direction[0] * distance, z + direction[1] * distance
                );
                if (nearby != null && !isAquatic(nearby)) {
                    return true;
                }
            }
        }
        return false;
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
        double totalDisplacement = 0.0D;
        double maximumDisplacement = 0.0D;
        int samples = 0;
        for (HexCoord cell : world.cells().keySet()) {
            Point center = world.grid().worldCenter(cell);
            for (HexCoord neighbor : cell.neighbors()) {
                if (world.cells().containsKey(neighbor)
                    && (cell.q() > neighbor.q()
                        || cell.q() == neighbor.q() && cell.r() > neighbor.r())) {
                    continue;
                }
                Point neighborCenter = world.grid().worldCenter(neighbor);
                double x = (center.x() + neighborCenter.x()) * 0.5D;
                double z = (center.z() + neighborCenter.z()) * 0.5D;
                if (settlementWarpFactor(world, x, z) < 0.95D) {
                    continue;
                }
                WarpedPoint warped = warpedCellPoint(world, x, z);
                double displacement = Math.hypot(
                    warped.x() - x, warped.z() - z
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

    static Integer averageWildSpawnLevel(ServerLevel level, double x, double z) {
        if (!level.dimension().equals(GENERATION_ONE)) {
            return null;
        }
        HexWorldPlan world = activeHexWorld;
        if (world == null || world.levelOverrides().isEmpty()) {
            return null;
        }
        return world.levelOverrides().get(world.grid().worldToHex(x, z));
    }

    static AdventureWorldContext.WildSpawnRule wildSpawnRule(
        ServerLevel level, double x, double z
    ) {
        return wildSpawnRule(
            level, x, z, AdventureWorldContext.WildEncounterMethod.LAND
        );
    }

    static AdventureWorldContext.WildSpawnRule wildSpawnRule(
        ServerLevel level, double x, double z,
        AdventureWorldContext.WildEncounterMethod method
    ) {
        if (!level.dimension().equals(GENERATION_ONE)) {
            return null;
        }
        HexWorldPlan world = activeHexWorld;
        if (world == null) {
            return null;
        }
        ConnectionPath route = authoredEncounterRouteAt(world, x, z);
        return wildSpawnRule(route, method);
    }

    /**
     * Resolves authored encounters by the route hex shown on the regional map.
     * Terrain generation still uses {@link #strongestRouteAt}; its narrow
     * centerline corridor must not limit the encounter area advertised for the
     * entire route tile.
     */
    static ConnectionPath authoredEncounterRouteAt(
        HexWorldPlan world, double x, double z
    ) {
        HexCoord cell = world.grid().worldToHex(x, z);
        CellPlan cellPlan = world.cells().get(cell);
        Set<HexCoord> settlementCells = cellPlan != null && cellPlan.kind().equals("town")
            ? Set.of(cell) : Set.of();
        return RouteEncounterSelector.forCell(cell, world.paths(), settlementCells);
    }

    private static AdventureWorldContext.WildSpawnRule wildSpawnRule(
        ConnectionPath route, AdventureWorldContext.WildEncounterMethod method
    ) {
        if (route == null) return null;
        RoutePokemonPool settings = route.pokemonSpawns().pool(
            method.serializedName()
        );
        if (settings == null) {
            return null;
        }
        Set<ResourceLocation> excluded = settings.excludedSpecies().stream()
            .map(ResourceLocation::parse)
            .collect(Collectors.toUnmodifiableSet());
        List<AdventureWorldContext.WildSpawnAddition> additions = settings.additions().stream()
            .map(addition -> new AdventureWorldContext.WildSpawnAddition(
                ResourceLocation.parse(addition.species()), addition.spawnAsEvolved(),
                addition.weight()
            ))
            .toList();
        Map<ResourceLocation, AdventureWorldContext.WildSpawnLevelRange> levelOverrides =
            settings.levelOverrides().values().stream().collect(Collectors.toUnmodifiableMap(
                override -> ResourceLocation.parse(override.species()),
                override -> new AdventureWorldContext.WildSpawnLevelRange(
                    override.minLevel(), override.maxLevel()
                )
            ));
        return new AdventureWorldContext.WildSpawnRule(
            settings.inheritBiome(), excluded, additions, levelOverrides,
            settings.enabled(), settings.triggerChance()
        );
    }

    static boolean isLogBridgeDeckSpawn(
        ServerLevel level, HexWorldPlan world, double x, double y, double z
    ) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        LogBridgeDeckPlan deck = logBridgeDeckAt(world, blockX, blockZ);
        if (deck == null) return false;
        if (!HabitatSpawnRules.isLogBridgeDeckHeight(deck.y(), y)) return false;
        BlockState actual = level.getBlockState(new BlockPos(blockX, deck.y(), blockZ));
        return actual.is(Blocks.CAMPFIRE)
            && actual.hasProperty(BlockStateProperties.LIT)
            && !actual.getValue(BlockStateProperties.LIT);
    }

    static boolean isLogBridgeDeckSpawn(
        ServerLevel level, double x, double y, double z
    ) {
        HexWorldPlan world = activeHexWorld;
        if (world == null) return false;
        ConnectionPath route = strongestRouteAt(world, x, z);
        return route != null && route.surfaceStyle().equals("log_bridge")
            && isLogBridgeDeckSpawn(level, world, x, y, z);
    }

    static String authoredWeatherAt(ServerPlayer player) {
        if (!player.serverLevel().dimension().equals(GENERATION_ONE)) {
            return null;
        }
        HexWorldPlan world = activeHexWorld;
        if (world == null) {
            return null;
        }
        TerrainSample sample = terrainAt(world, player.getX(), player.getZ());
        return LocalWeatherSystem.authoredWeatherAt(player, world, sample);
    }

    static TerrainSample terrainAt(HexWorldPlan world, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (x == blockX + 0.5D && z == blockZ + 0.5D) {
            return terrainAtBlockCenter(world, blockX, blockZ);
        }
        return computeTerrainAt(world, x, z);
    }

    /**
     * Resolves the Minecraft biome at a position independently from route metadata.
     * Routes are a surface overlay, so their configured biome must not replace the
     * biome of the tile (or inaccessible terrain) underneath them.
     */
    static String biomeAt(
        HexWorldPlan world, double x, double z, TerrainSample sample
    ) {
        if (sample == null) {
            return emptyTerrainBiome(world, x, z);
        }
        if (!sample.kind().equals("route")) {
            return sample.biome();
        }
        TerrainSample underlying = cellInfluence(world, x, z);
        return underlying == null
            ? emptyTerrainBiome(world, x, z)
            : underlying.biome();
    }

    private static TerrainSample terrainAtBlockCenter(
        HexWorldPlan world, int blockX, int blockZ
    ) {
        return TerrainSampler.getOrCompute(world, blockX, blockZ, () -> {
            double x = blockX + 0.5D;
            double z = blockZ + 0.5D;
            WarpedPoint warped = warpedCellPoint(world, x, z);
            return new TerrainSampler.Lookup(
                computeTerrainAt(world, x, z, warped), warped
            );
        }).sample();
    }

    private static TerrainSample computeTerrainAt(HexWorldPlan world, double x, double z) {
        return computeTerrainAt(world, x, z, warpedCellPoint(world, x, z));
    }

    private static TerrainSample computeTerrainAt(
        HexWorldPlan world, double x, double z, WarpedPoint warped
    ) {
        TerrainSample cell = cellInfluence(world, warped);
        if (cell != null && cell.kind().equals("town")) {
            return cell;
        }
        TerrainSample route = strongestRouteInfluence(world, x, z);
        if (route != null) {
            return route;
        }
        return cell != null && cell.kind().equals("surrounding") ? cell : null;
    }

    private static TerrainSample cellInfluence(HexWorldPlan world, double x, double z) {
        return cellInfluence(world, warpedCellPoint(world, x, z));
    }

    private static TerrainSample cellInfluence(HexWorldPlan world, WarpedPoint warped) {
        CellPlan plan = world.cells().get(world.grid().worldToHex(warped.x(), warped.z()));
        if (plan == null) {
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
        // Anchoring every noise octave at the cell center keeps authored roads and
        // facilities stable, but also reduces displacement at the actual cell edge.
        // Restore the original boundary strength without moving those fixed centers.
        double warpGain = (0.82D + Math.min(0.72D, configuredEdgeNoise * 3.2D)) * 1.20D;
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
        ConnectionPath selected = strongestRouteAt(world, x, z);
        if (selected == null || selected.surfaceStyle().equals("water")) {
            return null;
        }
        return new TerrainSample(
            selected.biome(), selected.boundaryProfile(), "route", selected.id(),
            selected.terrainProfile(), selected.accessRequirement(), selected.surfaceStyle()
        );
    }

    private static ConnectionPath strongestRouteAt(
        HexWorldPlan world, double x, double z
    ) {
        ConnectionPath selected = null;
        double selectedStrength = Double.NEGATIVE_INFINITY;
        for (ConnectionPath route : world.paths()) {
            double edgeVariation = Math.min(0.42D, Math.abs(route.edgeNoise()) * 1.5D);
            double maximumRadius = route.corridorWidthBlocks() / 2.0D
                * (1.0D + edgeVariation);
            if (!route.bounds().contains(x, z, maximumRadius)) {
                continue;
            }
            double distance = distanceToRoute(
                route.centerline(), x, z, maximumRadius
            );
            if (distance > maximumRadius) {
                continue;
            }
            double noise = layeredNoise(world.seed(), route.id() + ":edge", x, z, 80.0D);
            double radius = route.corridorWidthBlocks() / 2.0D * (
                1.0D + Math.min(0.42D, route.edgeNoise() * 1.5D) * noise
            );
            double strength = 1.0D - distance / radius;
            if (strength >= 0.0D && strength > selectedStrength) {
                selectedStrength = strength;
                selected = route;
            }
        }
        return selected;
    }

    private static double distanceToRoute(
        List<Point> centerline, double x, double z, double maximumRadius
    ) {
        double closest = Double.POSITIVE_INFINITY;
        if (centerline.size() == 1) {
            Point point = centerline.getFirst();
            return Math.hypot(x - point.x(), z - point.z());
        }
        for (int index = 1; index < centerline.size(); index++) {
            Point start = centerline.get(index - 1);
            Point end = centerline.get(index);
            if (x < Math.min(start.x(), end.x()) - maximumRadius
                || x > Math.max(start.x(), end.x()) + maximumRadius
                || z < Math.min(start.z(), end.z()) - maximumRadius
                || z > Math.max(start.z(), end.z()) + maximumRadius) {
                continue;
            }
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

    static LogBridgeDeckPlan logBridgeDeckAt(
        HexWorldPlan world, int x, int z
    ) {
        ConnectionPath route = strongestRouteAt(world, x + 0.5D, z + 0.5D);
        if (route == null || !route.surfaceStyle().equals("log_bridge")) {
            return null;
        }
        double closest = Double.POSITIVE_INFINITY;
        double tangentX = 1.0D;
        double tangentZ = 0.0D;
        double progress = 0.0D;
        double traversed = 0.0D;
        for (int index = 1; index < route.centerline().size(); index++) {
            Point start = route.centerline().get(index - 1);
            Point end = route.centerline().get(index);
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            double lengthSquared = dx * dx + dz * dz;
            double segmentLength = Math.sqrt(lengthSquared);
            double factor = lengthSquared == 0.0D ? 0.0D
                : ((x + 0.5D - start.x()) * dx
                    + (z + 0.5D - start.z()) * dz) / lengthSquared;
            factor = Math.max(0.0D, Math.min(1.0D, factor));
            double projectedX = start.x() + factor * dx;
            double projectedZ = start.z() + factor * dz;
            double distance = Math.hypot(
                x + 0.5D - projectedX, z + 0.5D - projectedZ
            );
            if (distance < closest) {
                closest = distance;
                tangentX = dx;
                tangentZ = dz;
                progress = traversed + factor * segmentLength;
            }
            traversed += segmentLength;
        }
        if (closest > 2.5D) {
            return null;
        }
        Direction facing = Math.abs(tangentX) >= Math.abs(tangentZ)
            ? (tangentX >= 0.0D ? Direction.EAST : Direction.WEST)
            : (tangentZ >= 0.0D ? Direction.SOUTH : Direction.NORTH);
        BlockState deck = Blocks.CAMPFIRE.defaultBlockState()
            .setValue(BlockStateProperties.LIT, false)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        boolean overOcean = logBridgeOverOceanAt(world, x, z);
        boolean support = overOcean && closest >= 1.5D
            && Math.floorMod((int) Math.round(progress), 6) == 0;
        int deckY = overOcean
            ? WATER_SURFACE_Y + 1
            : roadUnderlyingGroundY(world, x, z) + 1;
        return new LogBridgeDeckPlan(deckY, deck, support, overOcean);
    }

    private static boolean logBridgeOverOceanAt(
        HexWorldPlan world, int x, int z
    ) {
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        return sample != null && sample.kind().equals("route")
            && sample.surfaceStyle().equals("log_bridge")
            && biomeAt(world, x + 0.5D, z + 0.5D, sample).contains("ocean");
    }

    private static RouteBounds routeBounds(List<Point> centerline) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Point point : centerline) {
            minX = Math.min(minX, point.x());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxZ = Math.max(maxZ, point.z());
        }
        if (centerline.isEmpty()) {
            return new RouteBounds(0, 0, -1, -1);
        }
        return new RouteBounds(minX, minZ, maxX, maxZ);
    }

    private static double layeredNoise(
        long seed, String salt, double x, double z, double scale
    ) {
        SeededNoise seededNoise = TERRAIN_NOISES.get(salt);
        if (seededNoise == null || seededNoise.seed() != seed) {
            seededNoise = new SeededNoise(seed, NormalNoise.create(
                RandomSource.create(mixedNoiseSeed(seed, salt)),
                -1,
                1.0D, 1.0D, 0.5D, 0.25D
            ));
            TERRAIN_NOISES.put(salt, seededNoise);
        }
        NormalNoise noise = seededNoise.noise();
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
                    : biomeAt(world, x + 1.5D, z + 1.5D, sample);
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
        boolean logBridge = sample.surfaceStyle().equals("log_bridge");
        boolean bridgeOverOcean = logBridge && logBridgeOverOceanAt(world, x, z);
        boolean aquatic = logBridge ? bridgeOverOcean : isAquatic(sample);
        boolean coastalWater = !logBridge
            && isCoastalWater(world, sample, x, z, groundY);
        boolean sandyShore = !logBridge
            && (coastalWater || isSandyShore(world, sample, x, z, groundY));
        String biome = biomeAt(world, x + 0.5D, z + 0.5D, sample);
        BlockState surface = logBridge
            ? bridgeOverOcean ? Blocks.GRAVEL.defaultBlockState()
                : roadSurfaceBlock(world, sample, x, z)
            : sandyShore
            ? Blocks.SAND.defaultBlockState()
            : surfaceBlock(biome);
        BlockState filler = logBridge && bridgeOverOcean
            ? Blocks.STONE.defaultBlockState()
            : sandyShore
            ? Blocks.SAND.defaultBlockState()
            : fillerBlock(biome);
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
        if (isEmptyOceanType(type)) {
            paintEmptyOceanColumn(level, world, type, x, z, cleanExisting, stats);
            return;
        }
        int topY = emptyTerrainGroundY(world, type, x, z);
        paintBlockedOuterColumn(level, world, type, x, z, topY, cleanExisting, stats);
    }

    private static int emptyTerrainGroundY(
        HexWorldPlan world, String type, int x, int z
    ) {
        int rawHeight = rawEmptyTerrainGroundY(world, type, x, z);
        return blendEmptyTerrainBoundary(world, type, x, z, rawHeight);
    }

    private static int rawEmptyTerrainGroundY(
        HexWorldPlan world, String type, int x, int z
    ) {
        double broad = layeredNoise(world.seed(), "world:sealed-outer:broad", x, z, 72.0D);
        double detail = layeredNoise(world.seed(), "world:sealed-outer:detail", x, z, 24.0D);
        if (type.equals("high_forest") || type.equals("dense_forest")) {
            double gentleBroad = layeredNoise(
                world.seed(), "world:sealed-outer:high-forest:broad", x, z, 180.0D
            );
            double gentleDetail = layeredNoise(
                world.seed(), "world:sealed-outer:high-forest:detail", x, z, 64.0D
            );
            int topY = BCA_REFERENCE_SURFACE_Y
                + (int) Math.round(gentleBroad * 3.0D + gentleDetail);
            return Math.max(64, Math.min(76, topY));
        }
        boolean raisedRockMountain = type.equals("stone_mountain")
            || type.equals("red_rock_mountain");
        boolean snowMountain = type.equals("snow_mountain");
        boolean previousMountainHeight = type.equals("desert");
        int baseY = raisedRockMountain ? 100
            : snowMountain ? 90 : previousMountainHeight ? 80 : 94;
        int topY = baseY + (int) Math.round(broad * 7.0D + detail * 3.0D);
        if (raisedRockMountain) {
            return Math.max(88, Math.min(112, topY));
        }
        if (snowMountain) {
            return Math.max(78, Math.min(102, topY));
        }
        if (previousMountainHeight) {
            return Math.max(68, Math.min(92, topY));
        }
        return Math.max(88, Math.min(112, topY));
    }

    private static int blendEmptyTerrainBoundary(
        HexWorldPlan world, String type, int x, int z, int ownHeight
    ) {
        WarpedPoint warped = warpedCellPoint(world, x + 0.5D, z + 0.5D);
        HexCoord current = world.grid().worldToHex(warped.x(), warped.z());
        Point center = world.grid().worldCenter(current);
        double bestDistance = Double.POSITIVE_INFINITY;
        String neighborType = null;
        HexCoord selectedNeighbor = null;
        for (HexCoord neighbor : current.neighbors()) {
            if (world.cells().containsKey(neighbor)) {
                continue;
            }
            String candidate = world.emptyTerrainTiles().getOrDefault(
                neighbor, world.defaultEmptyTerrain()
            );
            if (candidate.equals(type)) {
                continue;
            }
            Point neighborCenter = world.grid().worldCenter(neighbor);
            double axisX = neighborCenter.x() - center.x();
            double axisZ = neighborCenter.z() - center.z();
            double length = Math.max(1.0D, Math.hypot(axisX, axisZ));
            double projection = ((warped.x() - center.x()) * axisX
                + (warped.z() - center.z()) * axisZ) / length;
            double distance = Math.max(0.0D, length * 0.5D - projection);
            if (distance < bestDistance) {
                bestDistance = distance;
                neighborType = candidate;
                selectedNeighbor = neighbor;
            }
        }
        if (neighborType == null || selectedNeighbor == null) {
            return ownHeight;
        }
        int neighborHeight = isEmptyOceanType(neighborType)
            ? WATER_SURFACE_Y
            : rawEmptyTerrainGroundY(world, neighborType, x, z);
        int boundaryHeight = (int) Math.round(
            (ownHeight + neighborHeight) * 0.5D
        );
        if (boundaryHeight == ownHeight) {
            return ownHeight;
        }
        int distanceFromBoundary = Math.max(
            0, (int) Math.floor(bestDistance)
        );
        return steppedHeightToward(
            world, boundaryHeight, ownHeight, distanceFromBoundary,
            current.q() + selectedNeighbor.q(),
            current.r() + selectedNeighbor.r(),
            0x454D505459535445L
        );
    }

    private static int steppedHeightToward(
        HexWorldPlan world, int startY, int targetY, int horizontalDistance,
        int seedX, int seedZ, long salt
    ) {
        int height = startY;
        int direction = Integer.signum(targetY - startY);
        for (int step = 0;
             step < horizontalDistance && height != targetY; step++) {
            int rise = 2 + Math.floorMod((int) coordinateSeed(
                world.seed(), seedX + step * 37, seedZ - step * 53, salt
            ), 2);
            int remaining = Math.abs(targetY - height);
            height += direction * Math.min(rise, remaining);
        }
        return height;
    }

    private static void paintEmptyOceanColumn(
        ServerLevel level, HexWorldPlan world, String type, int x, int z,
        boolean cleanExisting, TerrainWriteStats stats
    ) {
        int floorY = emptyOceanFloorY(world, type, x, z);
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

    private static int emptyOceanFloorY(
        HexWorldPlan world, String type, int x, int z
    ) {
        int ownFloor = rawEmptyOceanFloorY(world, type, x, z);
        WarpedPoint warped = warpedCellPoint(world, x + 0.5D, z + 0.5D);
        HexCoord current = world.grid().worldToHex(warped.x(), warped.z());
        Point center = world.grid().worldCenter(current);
        double bestDistance = Double.POSITIVE_INFINITY;
        String neighborType = null;
        HexCoord selectedNeighbor = null;
        for (HexCoord neighbor : current.neighbors()) {
            String candidate = world.emptyTerrainTiles().getOrDefault(
                neighbor, world.defaultEmptyTerrain()
            );
            if (!isEmptyOceanType(candidate) || candidate.equals(type)) {
                continue;
            }
            Point neighborCenter = world.grid().worldCenter(neighbor);
            double axisX = neighborCenter.x() - center.x();
            double axisZ = neighborCenter.z() - center.z();
            double length = Math.max(1.0D, Math.hypot(axisX, axisZ));
            double projection = ((warped.x() - center.x()) * axisX
                + (warped.z() - center.z()) * axisZ) / length;
            double distance = Math.max(0.0D, length * 0.5D - projection);
            if (distance < bestDistance) {
                bestDistance = distance;
                neighborType = candidate;
                selectedNeighbor = neighbor;
            }
        }
        if (neighborType == null || selectedNeighbor == null) {
            return ownFloor;
        }
        int neighborFloor = rawEmptyOceanFloorY(world, neighborType, x, z);
        int boundaryFloor = (ownFloor + neighborFloor) / 2;
        return steppedHeightToward(
            world, boundaryFloor, ownFloor,
            Math.max(0, (int) Math.floor(bestDistance)),
            current.q() + selectedNeighbor.q(),
            current.r() + selectedNeighbor.r(), 0x444545504F434541L
        );
    }

    private static int rawEmptyOceanFloorY(
        HexWorldPlan world, String type, int x, int z
    ) {
        double floorNoise = layeredNoise(
            world.seed(), "world:empty-ocean:floor", x, z,
            type.equals("deep_ocean") ? 54.0D : 42.0D
        );
        int baseY = type.equals("deep_ocean") ? 22 : 42;
        int variation = type.equals("deep_ocean") ? 7 : 5;
        return Math.max(
            DEEP_FOUNDATION_MAX_Y + 2,
            baseY + (int) Math.round(floorNoise * variation)
        );
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
        if (sample.kind().equals("route") && (sample.surfaceStyle().equals("road")
            || (sample.surfaceStyle().equals("log_bridge")
                && !logBridgeOverOceanAt(
                    world, (int) Math.floor(x), (int) Math.floor(z)
                )))) {
            return roadColumnPlan(world, sample.owner(), x, z).groundY();
        }
        int groundY = baseTerrainGroundY(world, sample, x, z);
        if (isAquatic(sample)
            || "cobbleventure:field_move/rock_climb".equals(sample.accessRequirement())) {
            return groundY;
        }
        return stabilizeNarrowTerrainFeature(world, groundY, x, z);
    }

    private static int baseTerrainGroundY(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainHeightKey key = new TerrainHeightKey(
            System.identityHashCode(world), world.seed(), sample,
            Double.doubleToLongBits(x), Double.doubleToLongBits(z)
        );
        return BASE_TERRAIN_HEIGHTS.getOrCompute(
            key, () -> computeBaseTerrainGroundY(world, sample, x, z)
        );
    }

    private static int computeBaseTerrainGroundY(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        int rawHeight = rawTerrainHeight(world, sample, x, z);
        if (isAquatic(sample) && (!sample.surfaceStyle().equals("log_bridge")
            || logBridgeOverOceanAt(
                world, (int) Math.floor(x), (int) Math.floor(z)
            ))) {
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

    private static int stabilizeNarrowTerrainFeature(
        HexWorldPlan world, int groundY, double x, double z
    ) {
        int[][] offsets = {
            {-6, -6}, {0, -6}, {6, -6},
            {-6, 0},            {6, 0},
            {-6, 6},  {0, 6},  {6, 6},
            {-3, -3}, {0, -3}, {3, -3},
            {-3, 0},            {3, 0},
            {-3, 3},  {0, 3},  {3, 3}
        };
        List<Integer> nearbyHeights = new ArrayList<>(offsets.length + 1);
        nearbyHeights.add(groundY);
        for (int[] offset : offsets) {
            TerrainSample nearby = terrainAt(
                world, x + offset[0], z + offset[1]
            );
            if (nearby == null || isAquatic(nearby)
                || nearby.surfaceStyle().equals("road")
                || "cobbleventure:field_move/rock_climb".equals(
                    nearby.accessRequirement()
                )) {
                continue;
            }
            nearbyHeights.add(baseTerrainGroundY(
                world, nearby, x + offset[0], z + offset[1]
            ));
        }
        if (nearbyHeights.size() < 10) {
            return groundY;
        }
        nearbyHeights.sort(Integer::compareTo);
        int median = nearbyHeights.get(nearbyHeights.size() / 2);
        if (Math.abs(groundY - median) < 5) {
            return groundY;
        }
        int lowerQuartile = nearbyHeights.get(nearbyHeights.size() / 4);
        int upperQuartile = nearbyHeights.get(nearbyHeights.size() * 3 / 4);
        if (upperQuartile - lowerQuartile > 4) {
            return groundY;
        }
        return median + Integer.signum(groundY - median);
    }

    private static RoadColumnPlan roadColumnPlan(
        HexWorldPlan world, String routeId, double x, double z
    ) {
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        if (sample == null || !sample.kind().equals("route")
            || !sample.owner().equals(routeId)) {
            return new RoadColumnPlan(LEGACY_SURFACE_Y, null);
        }
        int baseY = roadUnderlyingGroundY(world, x, z);
        Direction stairDirection = regionalRoadStairDirection(
            world, routeId, x, z, baseY
        );
        return new RoadColumnPlan(
            baseY + (stairDirection == null ? 0 : 1), stairDirection
        );
    }

    /** Keeps a regional road on the terrain it overlays instead of using the
     * route profile as a separate, usually flat, terrain layer. */
    private static int roadUnderlyingGroundY(
        HexWorldPlan world, double x, double z
    ) {
        double sampleX = Math.floor(x) + 0.5D;
        double sampleZ = Math.floor(z) + 0.5D;
        TerrainSample underlying = cellInfluence(world, sampleX, sampleZ);
        if (underlying != null) {
            return terrainGroundY(world, underlying, x, z);
        }
        String type = emptyTerrainAt(world, sampleX, sampleZ);
        if (isEmptyOceanType(type)) {
            return WATER_SURFACE_Y;
        }
        return emptyTerrainGroundY(
            world, type, (int) Math.floor(x), (int) Math.floor(z)
        );
    }

    private static Direction regionalRoadStairDirection(
        HexWorldPlan world, String routeId, double x, double z, int currentHeight
    ) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            double adjacentX = x + direction.getStepX();
            double adjacentZ = z + direction.getStepZ();
            TerrainSample adjacent = terrainAt(
                world, adjacentX + 0.5D, adjacentZ + 0.5D
            );
            if (adjacent == null || !adjacent.kind().equals("route")
                || !adjacent.owner().equals(routeId)) {
                continue;
            }
            if (roadUnderlyingGroundY(world, adjacentX, adjacentZ)
                == currentHeight + 1) {
                return direction;
            }
        }
        return null;
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
        int localHeight = localRawTerrainHeight(world, sample, x, z);
        return blendMatchingConnectionHeights(world, sample, x, z, localHeight);
    }

    private static int localRawTerrainHeight(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainProfile terrain = sample.terrainProfile();
        double density = terrainDensity(world, sample, x, z);
        boolean gentle = isGentleTerrain(sample);
        double maximumRelief = terrain.heightVariation() * (gentle ? 1.75D : 3.0D);
        double displacementScale = gentle ? 4.5D : 8.0D;
        double displacement = Math.max(
            -maximumRelief,
            Math.min(maximumRelief, density * terrain.heightVariation() * displacementScale)
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

    private static int blendMatchingConnectionHeights(
        HexWorldPlan world, TerrainSample sample, double x, double z, int localHeight
    ) {
        WarpedPoint warped = cachedWarpedPoint(world, x, z);
        HexCoord current = world.grid().worldToHex(warped.x(), warped.z());
        CellPlan currentPlan = world.cells().get(current);
        if (currentPlan == null || !sameTerrainIdentity(sample, currentPlan)) {
            return localHeight;
        }
        Point currentCenter = world.grid().worldCenter(current);
        double currentDistance = Math.hypot(
            warped.x() - currentCenter.x(), warped.z() - currentCenter.z()
        );
        double weightedHeight = localHeight;
        double totalWeight = 1.0D;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};
        for (int[] direction : directions) {
            HexCoord coordinate = new HexCoord(
                current.q() + direction[0], current.r() + direction[1]
            );
            CellPlan neighbor = world.cells().get(coordinate);
            if (neighbor == null || !canBlendTerrainConnections(currentPlan, neighbor)) {
                continue;
            }
            Point neighborCenter = world.grid().worldCenter(coordinate);
            double neighborDistance = Math.hypot(
                warped.x() - neighborCenter.x(), warped.z() - neighborCenter.z()
            );
            double boundaryDistance = Math.max(
                0.0D, (neighborDistance - currentDistance) * 0.5D
            );
            double blendWidth = isGentleTerrain(currentPlan)
                && isGentleTerrain(neighbor) ? 22.0D : 10.0D;
            double progress = 1.0D - boundaryDistance / blendWidth;
            if (progress <= 0.0D) {
                continue;
            }
            double weight = fade(Math.min(1.0D, progress));
            TerrainSample neighborSample = new TerrainSample(
                neighbor.biome(), neighbor.boundaryProfile(), neighbor.kind(),
                neighbor.owner(), neighbor.terrainProfile(),
                neighbor.accessRequirement(), neighbor.surfaceStyle()
            );
            weightedHeight += localRawTerrainHeight(
                world, neighborSample, x, z
            ) * weight;
            totalWeight += weight;
        }
        return (int) Math.round(weightedHeight / totalWeight);
    }

    private static WarpedPoint cachedWarpedPoint(
        HexWorldPlan world, double x, double z
    ) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (x == blockX + 0.5D && z == blockZ + 0.5D) {
            TerrainSampler.Lookup cached = TerrainSampler.get(world, blockX, blockZ);
            if (cached != null) {
                return cached.warped();
            }
        }
        return warpedCellPoint(world, x, z);
    }

    private static boolean sameTerrainIdentity(TerrainSample sample, CellPlan plan) {
        return sample.kind().equals(plan.kind()) && sample.owner().equals(plan.owner());
    }

    private static boolean canBlendTerrainConnections(CellPlan first, CellPlan second) {
        if (first.terrainProfile().connectionHeight()
            != second.terrainProfile().connectionHeight()) {
            return false;
        }
        boolean firstAquatic = first.surfaceStyle().equals("water")
            || first.biome().contains("ocean") || first.biome().contains("river");
        boolean secondAquatic = second.surfaceStyle().equals("water")
            || second.biome().contains("ocean") || second.biome().contains("river");
        if (firstAquatic != secondAquatic) {
            return false;
        }
        return Objects.equals(first.accessRequirement(), second.accessRequirement());
    }

    private static boolean isGentleTerrain(TerrainSample sample) {
        return !isAquatic(sample)
            && sample.terrainProfile().connectionHeight() == 0
            && !"cobbleventure:field_move/rock_climb".equals(
                sample.accessRequirement()
            );
    }

    private static boolean isGentleTerrain(CellPlan plan) {
        boolean aquatic = plan.surfaceStyle().equals("water")
            || plan.biome().contains("ocean") || plan.biome().contains("river");
        return !aquatic
            && plan.terrainProfile().connectionHeight() == 0
            && !"cobbleventure:field_move/rock_climb".equals(
                plan.accessRequirement()
            );
    }

    private static double terrainDensity(
        HexWorldPlan world, TerrainSample sample, double x, double z
    ) {
        TerrainProfile terrain = sample.terrainProfile();
        double scaleMultiplier = isGentleTerrain(sample) ? 1.65D : 1.0D;
        double noiseScale = terrain.noiseScaleBlocks() * scaleMultiplier;
        double continentalness = centeredTerrainNoise(
            world, "world:height:continentalness", x, z,
            Math.max(220.0D * scaleMultiplier, noiseScale * 3.2D)
        );
        double erosion = centeredTerrainNoise(
            world, "world:height:erosion", x, z,
            Math.max(96.0D * scaleMultiplier, noiseScale * 1.35D)
        );
        double ridgeSource = centeredTerrainNoise(
            world, "world:height:ridges", x, z,
            Math.max(52.0D * scaleMultiplier, noiseScale * 0.72D)
        );
        double ridges = Math.copySign(ridgeSource * ridgeSource, ridgeSource);
        double detail = centeredTerrainNoise(
            world, "world:height:detail", x, z,
            Math.max(22.0D * scaleMultiplier, noiseScale * 0.28D)
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
        if (sample.surfaceStyle().equals("log_bridge")) {
            return Math.min(deepFloor, WATER_SURFACE_Y - minimumWaterDepth(sample));
        }
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
        if (sample.surfaceStyle().equals("road")) {
            return false;
        }
        return sample.surfaceStyle().equals("water")
            || sample.surfaceStyle().equals("log_bridge")
            || sample.biome().contains("ocean")
            || sample.biome().contains("river");
    }

    private static int minimumWaterDepth(TerrainSample sample) {
        return sample.surfaceStyle().equals("log_bridge")
            || sample.biome().contains("ocean") ? 20 : 6;
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
        TerrainColumnKey key = new TerrainColumnKey(
            System.identityHashCode(world), world.seed(), x, z
        );
        return NATIVE_TERRAIN_COLUMNS.getOrCompute(
            key, () -> computeNativeTerrainColumn(world, x, z)
        );
    }

    private static NativeTerrainColumn computeNativeTerrainColumn(
        HexWorldPlan world, int x, int z
    ) {
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        int boundaryRockHeight = oceanBoundaryRockHeight(world, x, z);
        boolean logBridge = sample != null && sample.kind().equals("route")
            && sample.surfaceStyle().equals("log_bridge");
        if (boundaryRockHeight > 0 && !logBridge
            && (sample == null || isAquatic(sample))) {
            int topY = WATER_SURFACE_Y + boundaryRockHeight;
            String biome = sample == null ? "minecraft:deep_ocean" : sample.biome();
            return new NativeTerrainColumn(
                topY, topY,
                oceanBoundaryRock(world, x, topY, z),
                oceanCliffRock(world, x, topY - 1, z),
                biome, sample == null, true, sample
            );
        }
        if (sample == null) {
            String type = emptyTerrainAt(world, x + 0.5D, z + 0.5D);
            String biome = emptyTerrainBiome(type);
            PlayableEdge nearestPlayable = nearestPlayableTerrain(
                world, x, z, OUTER_TERRAIN_TRANSITION_WIDTH
            );
            boolean oceanBoundary = nearestPlayable != null
                && nearestPlayable.aquatic()
                && nearestPlayable.distance() <= OUTER_TERRAIN_TRANSITION_WIDTH;
            if (isEmptyOceanType(type) || oceanBoundary) {
                String oceanType = isEmptyOceanType(type) ? type : "ocean";
                biome = emptyTerrainBiome(oceanType);
                int floorY = emptyOceanFloorY(world, oceanType, x, z);
                return new NativeTerrainColumn(
                    floorY, WATER_SURFACE_Y,
                    oceanFloorBlock(world, x, floorY, z),
                    Blocks.STONE.defaultBlockState(), biome, true, false, null
                );
            }
            int topY = emptyTerrainGroundY(world, type, x, z);
            if (nearestPlayable != null && !nearestPlayable.aquatic()) {
                if (type.equals("high_forest") || type.equals("dense_forest")) {
                    Integer continuedHeight = continuedPlayableTerrainHeight(
                        world, x, z
                    );
                    if (continuedHeight != null) {
                        double continuationProgress = fade(Math.max(
                            0.0D, Math.min(1.0D,
                                (nearestPlayable.distance() - 1.0D) / 8.0D)
                        ));
                        double inheritedHeight = nearestPlayable.groundY()
                            + (continuedHeight - nearestPlayable.groundY())
                                * continuationProgress;
                        double forestProgress = fade(Math.max(
                            0.0D, Math.min(1.0D,
                                (nearestPlayable.distance() - 1.0D)
                                    / (OUTER_TERRAIN_TRANSITION_WIDTH - 1.0D))
                        ));
                        topY = (int) Math.round(
                            inheritedHeight + (topY - inheritedHeight) * forestProgress
                        );
                    }
                } else {
                    HexCoord emptyCell = world.grid().worldToHex(
                        x + 0.5D, z + 0.5D
                    );
                    topY = steppedHeightToward(
                        world, nearestPlayable.groundY(), topY,
                        Math.max(0, nearestPlayable.distance() - 1),
                        emptyCell.q(), emptyCell.r(), 0x504C415941424C45L
                    );
                }
            }
            BlockState surface = switch (type) {
                case "stone_mountain" -> oceanCliffRock(world, x, topY, z);
                case "red_rock_mountain" -> redRockMountainBlock(
                    world, x, topY, z
                );
                default -> surfaceBlock(biome);
            };
            BlockState filler = switch (type) {
                case "stone_mountain" -> oceanCliffRock(world, x, topY - 1, z);
                case "red_rock_mountain" -> redRockMountainBlock(
                    world, x, topY - 1, z
                );
                default -> fillerBlock(biome);
            };
            return new NativeTerrainColumn(
                topY, topY, surface, filler, biome, true, false, null
            );
        }

        int groundY = terrainGroundY(world, sample, x, z);
        logBridge = sample.surfaceStyle().equals("log_bridge");
        boolean bridgeOverOcean = logBridge && logBridgeOverOceanAt(world, x, z);
        boolean aquatic = logBridge ? bridgeOverOcean : isAquatic(sample);
        boolean coastalWater = !logBridge
            && isCoastalWater(world, sample, x, z, groundY);
        boolean sandyShore = !logBridge
            && isSandyShore(world, sample, x, z, groundY);
        String biome = biomeAt(world, x + 0.5D, z + 0.5D, sample);
        BlockState surface = logBridge
            ? bridgeOverOcean ? Blocks.GRAVEL.defaultBlockState()
                : roadSurfaceBlock(world, sample, x, z)
            : sample.surfaceStyle().equals("road") && !aquatic
            ? roadSurfaceBlock(world, sample, x, z)
            : sandyShore ? Blocks.SAND.defaultBlockState()
            : surfaceBlock(biome);
        BlockState filler = logBridge && bridgeOverOcean
            ? Blocks.STONE.defaultBlockState()
            : sandyShore ? Blocks.SAND.defaultBlockState() : fillerBlock(biome);
        return new NativeTerrainColumn(
            groundY,
            aquatic || coastalWater ? WATER_SURFACE_Y : groundY,
            surface,
            filler,
            biome,
            false,
            false,
            sample
        );
    }

    private static PlayableEdge nearestPlayableTerrain(
        HexWorldPlan world, int x, int z, int radius
    ) {
        HexCoord current = world.grid().worldToHex(x + 0.5D, z + 0.5D);
        boolean playableCellNearby = world.cells().containsKey(current);
        if (!playableCellNearby) {
            for (HexCoord neighbor : current.neighbors()) {
                if (world.cells().containsKey(neighbor)) {
                    playableCellNearby = true;
                    break;
                }
            }
        }
        if (!playableCellNearby) {
            return null;
        }
        PlayableEdge adjacent = adjacentPlayableTerrain(world, x, z);
        if (adjacent != null) {
            return adjacent;
        }
        double[][] directions = OUTER_TERRAIN_SAMPLE_DIRECTIONS;
        long[] previousSamples = new long[directions.length];
        Arrays.fill(previousSamples, Long.MIN_VALUE);
        int nearestDistance = Integer.MAX_VALUE;
        int landHeightTotal = 0;
        int landSamples = 0;
        int aquaticHeightTotal = 0;
        int aquaticSamples = 0;
        long[] nearestSampleKeys = new long[
            directions.length * OUTER_TERRAIN_DISTANCE_SAMPLE_SPACING
        ];
        int nearestSampleCount = 0;
        for (int distance = 1; distance <= radius; distance++) {
            for (int directionIndex = 0; directionIndex < directions.length; directionIndex++) {
                double[] direction = directions[directionIndex];
                int sampleBlockX = (int) Math.round(
                    (x + direction[0] * distance)
                        / OUTER_TERRAIN_DISTANCE_SAMPLE_SPACING
                ) * OUTER_TERRAIN_DISTANCE_SAMPLE_SPACING;
                int sampleBlockZ = (int) Math.round(
                    (z + direction[1] * distance)
                        / OUTER_TERRAIN_DISTANCE_SAMPLE_SPACING
                ) * OUTER_TERRAIN_DISTANCE_SAMPLE_SPACING;
                long sampleKey = ChunkPos.asLong(sampleBlockX, sampleBlockZ);
                if (previousSamples[directionIndex] == sampleKey) {
                    continue;
                }
                previousSamples[directionIndex] = sampleKey;
                TerrainSample nearby = terrainAtBlockCenter(
                    world, sampleBlockX, sampleBlockZ
                );
                if (nearby == null) {
                    continue;
                }
                int actualDistance = Math.max(
                    Math.abs(sampleBlockX - x), Math.abs(sampleBlockZ - z)
                );
                if (actualDistance > nearestDistance) {
                    continue;
                }
                if (actualDistance < nearestDistance) {
                    nearestDistance = actualDistance;
                    landHeightTotal = 0;
                    landSamples = 0;
                    aquaticHeightTotal = 0;
                    aquaticSamples = 0;
                    nearestSampleCount = 0;
                }
                boolean duplicate = false;
                for (int index = 0; index < nearestSampleCount; index++) {
                    if (nearestSampleKeys[index] == sampleKey) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    continue;
                }
                if (nearestSampleCount >= nearestSampleKeys.length) {
                    continue;
                }
                nearestSampleKeys[nearestSampleCount++] = sampleKey;
                int groundY = terrainGroundY(
                    world, nearby, sampleBlockX + 0.5D, sampleBlockZ + 0.5D
                );
                if (isAquatic(nearby)) {
                    aquaticHeightTotal += groundY;
                    aquaticSamples++;
                } else {
                    landHeightTotal += groundY;
                    landSamples++;
                }
            }
            if (nearestDistance != Integer.MAX_VALUE
                && distance >= nearestDistance) {
                return averagedPlayableEdge(
                    nearestDistance, landHeightTotal, landSamples,
                    aquaticHeightTotal, aquaticSamples
                );
            }
        }
        return averagedPlayableEdge(
            nearestDistance, landHeightTotal, landSamples,
            aquaticHeightTotal, aquaticSamples
        );
    }

    private static Integer continuedPlayableTerrainHeight(
        HexWorldPlan world, int x, int z
    ) {
        HexCoord emptyCell = world.grid().worldToHex(x + 0.5D, z + 0.5D);
        double weightedHeight = 0.0D;
        double totalWeight = 0.0D;
        List<HexCoord> candidates = new ArrayList<>(emptyCell.neighbors());
        candidates.add(emptyCell);
        for (HexCoord neighbor : candidates) {
            CellPlan plan = world.cells().get(neighbor);
            if (plan == null) {
                continue;
            }
            TerrainSample sample = new TerrainSample(
                plan.biome(), plan.boundaryProfile(), plan.kind(), plan.owner(),
                plan.terrainProfile(), plan.accessRequirement(), plan.surfaceStyle()
            );
            if (isAquatic(sample)) {
                continue;
            }
            Point center = world.grid().worldCenter(neighbor);
            double weight = 1.0D / Math.max(
                1.0D, Math.hypot(x + 0.5D - center.x(), z + 0.5D - center.z())
            );
            weightedHeight += localRawTerrainHeight(
                world, sample, x + 0.5D, z + 0.5D
            ) * weight;
            totalWeight += weight;
        }
        return totalWeight == 0.0D
            ? null : (int) Math.round(weightedHeight / totalWeight);
    }

    private static PlayableEdge adjacentPlayableTerrain(
        HexWorldPlan world, int x, int z
    ) {
        int landHeightTotal = 0;
        int landSamples = 0;
        int aquaticHeightTotal = 0;
        int aquaticSamples = 0;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                int sampleX = x + offsetX;
                int sampleZ = z + offsetZ;
                TerrainSample nearby = terrainAtBlockCenter(
                    world, sampleX, sampleZ
                );
                if (nearby == null) {
                    continue;
                }
                int groundY = terrainGroundY(
                    world, nearby, sampleX + 0.5D, sampleZ + 0.5D
                );
                if (isAquatic(nearby)) {
                    aquaticHeightTotal += groundY;
                    aquaticSamples++;
                } else {
                    landHeightTotal += groundY;
                    landSamples++;
                }
            }
        }
        return averagedPlayableEdge(
            1, landHeightTotal, landSamples,
            aquaticHeightTotal, aquaticSamples
        );
    }

    private static PlayableEdge averagedPlayableEdge(
        int distance,
        int landHeightTotal, int landSamples,
        int aquaticHeightTotal, int aquaticSamples
    ) {
        if (landSamples > 0) {
            return new PlayableEdge(
                distance,
                (int) Math.round(landHeightTotal / (double) landSamples),
                false
            );
        }
        if (aquaticSamples > 0) {
            return new PlayableEdge(
                distance,
                (int) Math.round(aquaticHeightTotal / (double) aquaticSamples),
                true
            );
        }
        return null;
    }

    private static int oceanBoundaryRockHeight(
        HexWorldPlan world, int x, int z
    ) {
        int gridX = Math.floorDiv(x, OCEAN_ROCK_MOUND_SPACING);
        int gridZ = Math.floorDiv(z, OCEAN_ROCK_MOUND_SPACING);
        int selectedHeight = 0;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                int cellX = gridX + offsetX;
                int cellZ = gridZ + offsetZ;
                long hash = coordinateSeed(
                    world.seed(), cellX, cellZ, 0x4F4345414E524F43L
                );
                int centerRange = OCEAN_ROCK_MOUND_SPACING
                    - OCEAN_ROCK_MOUND_CENTER_MARGIN * 2;
                int centerX = cellX * OCEAN_ROCK_MOUND_SPACING
                    + OCEAN_ROCK_MOUND_CENTER_MARGIN
                    + Math.floorMod((int) hash, centerRange);
                int centerZ = cellZ * OCEAN_ROCK_MOUND_SPACING
                    + OCEAN_ROCK_MOUND_CENTER_MARGIN
                    + Math.floorMod((int) (hash >>> 32), centerRange);
                int radius = 3 + Math.floorMod((int) (hash >>> 48), 2);
                if (!oceanMoundTouchesBoundary(
                    world, cellX, cellZ, centerX, centerZ
                )) {
                    continue;
                }
                double distance = Math.hypot(x - centerX, z - centerZ);
                if (distance > radius) {
                    continue;
                }
                double profile = 1.0D - distance / (radius + 0.35D);
                double roughness = layeredNoise(
                    world.seed(), "world:ocean-boundary-rock:roughness", x, z, 4.5D
                ) * 0.3D;
                int height = 1 + (int) Math.round(
                    Math.pow(profile, 0.72D) * 3.0D + roughness
                );
                selectedHeight = Math.max(selectedHeight, Math.max(
                    1, Math.min(OCEAN_CLIFF_MAX_Y - WATER_SURFACE_Y, height)
                ));
            }
        }
        return selectedHeight;
    }

    private static boolean oceanMoundTouchesBoundary(
        HexWorldPlan world,
        int cellX,
        int cellZ,
        int centerX,
        int centerZ
    ) {
        OceanMoundKey key = new OceanMoundKey(world.seed(), cellX, cellZ);
        return OCEAN_MOUND_BOUNDARY.computeIfAbsent(key, ignored -> {
            boolean playableOcean = false;
            boolean outerTerrain = false;
            for (int offsetX = -OCEAN_ROCK_CENTER_EDGE_RANGE;
                 offsetX <= OCEAN_ROCK_CENTER_EDGE_RANGE; offsetX++) {
                for (int offsetZ = -OCEAN_ROCK_CENTER_EDGE_RANGE;
                     offsetZ <= OCEAN_ROCK_CENTER_EDGE_RANGE; offsetZ++) {
                    if (offsetX * offsetX + offsetZ * offsetZ
                        > OCEAN_ROCK_CENTER_EDGE_RANGE * OCEAN_ROCK_CENTER_EDGE_RANGE) {
                        continue;
                    }
                    TerrainSample nearby = terrainAt(
                        world, centerX + offsetX + 0.5D,
                        centerZ + offsetZ + 0.5D
                    );
                    if (nearby == null) {
                        outerTerrain = true;
                    } else if (isAquatic(nearby)) {
                        playableOcean = true;
                    }
                    if (playableOcean && outerTerrain) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static long coordinateSeed(
        long seed, int x, int z, long salt
    ) {
        long value = seed ^ salt;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
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
                    world, x, z, directions, collisionColumns,
                    requiredCollisionColumns, staleCollisionColumns
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
                    if (isEmptyOceanType(outerType)) {
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
                level, world, column.x(), column.z()
            );
        }
        int barrierBlocks = 0;
        int preservedBlocks = 0;
        for (Point column : collisionColumns) {
            CollisionPlacement placement = drawHiddenBoundaryCollision(
                level, world, column.x(), column.z()
            );
            barrierBlocks += placement.placed();
            preservedBlocks += placement.preserved();
        }
        LOGGER.info(
            "Hidden terrain transition completed: outerTerrainColumns={}, oceanCliffColumns={}, emptyOceanRockColumns={}, filledTerrainGaps={}, outerTerrainY={}..{}, maximumOuterSlope={}, maximumPlayableSeam={}, collisionColumns={}, requiredEdgeColumns={}, removedStaleBarrierBlocks={}, barrierReplaceableBlocks={}, preservedExistingBlocks={}",
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
            && (collisionColumns.isEmpty() || barrierBlocks == 0 || preservedBlocks == 0)) {
            throw new IllegalStateException(
                "Hidden collision boundary did not preserve terrain while placing its air-and-water band"
            );
        }
    }

    private static void drawHiddenWorldCeiling(
        ServerLevel level, HexWorldPlan world, HexBounds bounds
    ) {
        int ceilingY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        int placed = 0;
        int skipped = 0;
        int minimumChunkX = bounds.minX() >> 4;
        int maximumChunkX = bounds.maxX() >> 4;
        int minimumChunkZ = bounds.minZ() >> 4;
        int maximumChunkZ = bounds.maxZ() >> 4;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            int minimumX = Math.max(bounds.minX(), chunkX << 4);
            int maximumX = Math.min(bounds.maxX(), (chunkX << 4) + 15);
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                int minimumZ = Math.max(bounds.minZ(), chunkZ << 4);
                int maximumZ = Math.min(bounds.maxZ(), (chunkZ << 4) + 15);
                var chunk = level.getChunk(chunkX, chunkZ);
                for (int x = minimumX; x <= maximumX; x++) {
                    for (int z = minimumZ; z <= maximumZ; z++) {
                        boolean playable = terrainAt(
                            world, x + 0.5D, z + 0.5D
                        ) != null;
                        if (!playable
                            && !isHiddenBoundaryCollisionColumn(world, x, z)) {
                            continue;
                        }
                        position.set(x, ceilingY, z);
                        if (chunk.getBlockState(position).is(Blocks.BARRIER)) {
                            skipped++;
                            continue;
                        }
                        chunk.setBlockState(position, barrier, false);
                        placed++;
                    }
                }
            }
        }
        LOGGER.info(
            "Hidden world ceiling completed: y={}, placed={}, existing={}",
            ceilingY, placed, skipped
        );
    }

    private static boolean isFullHexWorldTest() {
        return Boolean.getBoolean(HEX_WORLD_TEST_PROPERTY)
            && Integer.getInteger(TEST_RENDER_RADIUS_PROPERTY, 0) <= 0;
    }

    private static boolean isOceanBoundaryRockTerrain(
        HexWorldPlan world, int x, int z
    ) {
        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
        return sample == null || isAquatic(sample);
    }

    private static void collectEmptyOceanRockColumns(
        HexWorldPlan world, HexBounds bounds, int edgeX, int edgeZ,
        int outwardX, int outwardZ, Set<Point> columns
    ) {
        int tangentX = -outwardZ;
        int tangentZ = outwardX;
        for (int distance = -OCEAN_ROCK_BOUNDARY_BAND;
             distance <= OCEAN_ROCK_BOUNDARY_BAND; distance++) {
            for (int tangent = -4; tangent <= 4; tangent++) {
                int x = edgeX + outwardX * distance + tangentX * tangent;
                int z = edgeZ + outwardZ * distance + tangentZ * tangent;
                Point point = new Point(x, z);
                if (!bounds.contains(point)
                    || !isOceanBoundaryRockTerrain(world, x, z)) {
                    continue;
                }
                if (oceanBoundaryRockHeight(world, x, z) > 0) {
                    columns.add(point);
                }
            }
        }
    }

    private static void paintEmptyOceanRocks(
        ServerLevel level, HexWorldPlan world, Set<Point> columns
    ) {
        int accentBlocks = 0;
        for (Point point : columns) {
            int height = oceanBoundaryRockHeight(world, point.x(), point.z());
            if (height == 0) continue;
            accentBlocks += paintOceanCliffColumn(
                level, world, point.x(), point.z(), WATER_SURFACE_Y + height
            );
        }
        LOGGER.info(
            "Blocked ocean rock mounds completed: columns={}, accentBlocks={}",
            columns.size(), accentBlocks
        );
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
        for (int distance = -OCEAN_ROCK_BOUNDARY_BAND;
             distance <= OCEAN_ROCK_BOUNDARY_BAND; distance++) {
            for (int tangent = -4; tangent <= 4; tangent++) {
                Point point = new Point(
                    edgeX + outwardX * distance + tangentX * tangent,
                    edgeZ + outwardZ * distance + tangentZ * tangent
                );
                if (bounds.contains(point)
                    && isOceanBoundaryRockTerrain(world, point.x(), point.z())
                    && oceanBoundaryRockHeight(world, point.x(), point.z()) > 0) {
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
            int height = oceanBoundaryRockHeight(world, point.x(), point.z());
            if (height == 0) continue;
            ledgeBlocks += paintOceanCliffColumn(
                level, world, point.x(), point.z(), WATER_SURFACE_Y + height
            );
        }
        int seagrass = decorateOceanBoundarySeagrass(level, world, columns);
        LOGGER.info(
            "Clustered ocean rocks completed: columns={}, stairBlocks={}, seagrass={}",
            columns.size(), ledgeBlocks, seagrass
        );
    }

    private static int decorateOceanBoundarySeagrass(
        ServerLevel level, HexWorldPlan world, Set<Point> rockColumns
    ) {
        Set<Point> candidates = new HashSet<>();
        for (Point rock : rockColumns) {
            for (int offsetX = -3; offsetX <= 3; offsetX++) {
                for (int offsetZ = -3; offsetZ <= 3; offsetZ++) {
                    Point candidate = rock.translate(offsetX, offsetZ);
                    if (!rockColumns.contains(candidate)) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        int placed = 0;
        for (Point point : candidates) {
            if (terrainAt(world, point.x() + 0.5D, point.z() + 0.5D) != null
                || !isEmptyOceanType(emptyTerrainAt(
                    world, point.x() + 0.5D, point.z() + 0.5D
                ))
                || Math.floorMod((int) (world.seed() ^ point.x() * 73428767L
                    ^ point.z() * 912931L), 100) >= 24) {
                continue;
            }
            int floorY = level.getHeight(
                Heightmap.Types.OCEAN_FLOOR_WG, point.x(), point.z()
            ) - 1;
            BlockPos plant = new BlockPos(point.x(), floorY + 1, point.z());
            if (level.getBlockState(plant).is(Blocks.WATER)) {
                level.setBlock(plant, Blocks.SEAGRASS.defaultBlockState(), 2);
                placed++;
            }
        }
        return placed;
    }

    private static int paintOceanCliffColumn(
        ServerLevel level, HexWorldPlan world, int x, int z, int topY
    ) {
        boolean playable = terrainAt(world, x + 0.5D, z + 0.5D) != null;
        for (int y = DEEP_FOUNDATION_MAX_Y + 1; y < WATER_SURFACE_Y; y++) {
            level.setBlock(
                new BlockPos(x, y, z),
                playable
                    ? oceanCliffRock(world, x, y, z)
                    : Blocks.BARRIER.defaultBlockState(),
                2
            );
        }
        int accentBlocks = 0;
        for (int y = WATER_SURFACE_Y; y <= topY; y++) {
            BlockState rock = y == topY
                ? oceanBoundaryRock(world, x, y, z)
                : oceanCliffRock(world, x, y, z);
            level.setBlock(new BlockPos(x, y, z), rock, 2);
            if (rock.is(Blocks.ANDESITE_STAIRS) || rock.is(Blocks.COBBLESTONE_STAIRS)) {
                accentBlocks++;
            }
        }
        for (int y = topY + 1; y <= 128; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return accentBlocks;
    }

    static BlockState oceanBoundaryRock(
        HexWorldPlan world, int x, int y, int z
    ) {
        double shape = layeredNoise(
            world.seed(), "world:ocean-boundary-rock:shape",
            x + y * 0.37D, z - y * 0.29D, 6.0D
        );
        double material = layeredNoise(
            world.seed(), "world:ocean-boundary-rock:material",
            x * 0.61D - y * 0.17D, z * 0.61D + y * 0.21D, 9.0D
        );
        if (y > WATER_SURFACE_Y && shape > 0.54D) {
            return Blocks.ANDESITE_STAIRS.defaultBlockState();
        }
        if (y > WATER_SURFACE_Y && shape < -0.58D) {
            return Blocks.COBBLESTONE_STAIRS.defaultBlockState();
        }
        if (material > 0.28D) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        if (material < -0.5D) {
            return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        }
        return shape < -0.18D
            ? Blocks.COBBLESTONE.defaultBlockState()
            : Blocks.STONE.defaultBlockState();
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

    static BlockState redRockMountainBlock(
        HexWorldPlan world, int x, int y, int z
    ) {
        double strata = layeredNoise(
            world.seed(), "world:red-rock-mountain:strata",
            x * 0.48D + y * 0.17D, z * 0.48D - y * 0.14D, 15.0D
        );
        double detail = layeredNoise(
            world.seed(), "world:red-rock-mountain:detail",
            x - y * 0.23D, z + y * 0.19D, 7.0D
        );
        if (detail > 0.58D) {
            return Blocks.DRIPSTONE_BLOCK.defaultBlockState();
        }
        if (strata > 0.5D) {
            return Blocks.POLISHED_GRANITE.defaultBlockState();
        }
        if (strata < -0.55D && detail < -0.12D) {
            return Blocks.BROWN_TERRACOTTA.defaultBlockState();
        }
        return Blocks.GRANITE.defaultBlockState();
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
        Set<Point> requiredCollisionColumns,
        Set<Point> staleCollisionColumns
    ) {
        for (int[] direction : cardinalDirections) {
            int x = edgeX + direction[0];
            int z = edgeZ + direction[1];
            if (isHiddenBoundaryCollisionColumn(world, x, z)) {
                requiredCollisionColumns.add(new Point(x, z));
            }
        }
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int x = edgeX + offsetX;
                int z = edgeZ + offsetZ;
                if (terrainAt(world, x + 0.5D, z + 0.5D) != null) {
                    continue;
                }
                Point column = new Point(x, z);
                staleCollisionColumns.add(column);
                if (isHiddenBoundaryCollisionColumn(world, x, z)) {
                    collisionColumns.add(new Point(x, z));
                }
            }
        }
    }

    static boolean isHiddenBoundaryCollisionColumn(
        HexWorldPlan world, int x, int z
    ) {
        if (terrainAt(world, x + 0.5D, z + 0.5D) != null) {
            return false;
        }
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            if (terrainAt(
                world,
                x + 0.5D + direction[0],
                z + 0.5D + direction[1]
            ) != null) {
                return true;
            }
        }
        return false;
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
            int transitionStartY = edgeHeights.get(point).average();
            String outerType = emptyTerrainAt(
                world, point.x() + 0.5D, point.z() + 0.5D
            );
            int outerTopY = emptyTerrainGroundY(
                world, outerType, point.x(), point.z()
            );
            HexCoord emptyCell = world.grid().worldToHex(
                point.x() + 0.5D, point.z() + 0.5D
            );
            int topY = steppedHeightToward(
                world, transitionStartY, outerTopY,
                Math.max(0, entry.getValue() - 1),
                emptyCell.q(), emptyCell.r(), 0x4F55544552535445L
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
        int minimumY = (type.equals("high_forest") || type.equals("dense_forest")) ? 64
            : isLoweredEmptyMountain(type) ? 68 : SEALED_OUTER_MIN_Y;
        topY = Math.max(minimumY, Math.min(112, topY));
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = 69; y <= topY - 3; y++) {
            BlockState filler = switch (type) {
                case "desert" -> y >= topY - 7
                    ? Blocks.SANDSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                case "stone_mountain", "snow_mountain" -> oceanCliffRock(world, x, y, z);
                case "red_rock_mountain" -> redRockMountainBlock(world, x, y, z);
                default -> Blocks.STONE.defaultBlockState();
            };
            setTerrainBlock(level, position.setY(y), filler, stats);
        }
        BlockState subsurface = switch (type) {
            case "desert" -> Blocks.SANDSTONE.defaultBlockState();
            case "stone_mountain" -> oceanCliffRock(world, x, topY - 1, z);
            case "red_rock_mountain" -> redRockMountainBlock(
                world, x, topY - 1, z
            );
            default -> Blocks.DIRT.defaultBlockState();
        };
        BlockState surface = switch (type) {
            case "desert" -> Blocks.SAND.defaultBlockState();
            case "stone_mountain" -> oceanCliffRock(world, x, topY, z);
            case "red_rock_mountain" -> redRockMountainBlock(
                world, x, topY, z
            );
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

    private static boolean isLoweredEmptyMountain(String type) {
        return type.equals("stone_mountain")
            || type.equals("snow_mountain")
            || type.equals("red_rock_mountain");
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

    private static CollisionPlacement drawHiddenBoundaryCollision(
        ServerLevel level, HexWorldPlan world, int x, int z
    ) {
        int placed = 0;
        int preserved = 0;
        int groundY = nativeTerrainColumn(world, x, z).groundY();
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            BlockPos position = new BlockPos(x, y, z);
            if (isCaveBarrierOpening(world, x, y, z, groundY)) {
                if (level.getBlockState(position).is(Blocks.BARRIER)) {
                    level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                }
                continue;
            }
            if (placeBarrierInAirOrWater(level, position)) {
                placed++;
            } else {
                preserved++;
            }
        }
        return new CollisionPlacement(placed, preserved);
    }

    static boolean isCaveBarrierOpening(
        HexWorldPlan world, int x, int y, int z, int groundY
    ) {
        if (WorldGateSystem.isForestBarrierOpening(world, x, y, z)) {
            return true;
        }
        for (CaveEntrancePlan entrance : world.caveEntrances()) {
            CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
            double offsetX = x + 0.5D - mouth.x();
            double offsetZ = z + 0.5D - mouth.z();
            double depth = offsetX * mouth.forwardX() + offsetZ * mouth.forwardZ();
            double lateral = Math.abs(
                offsetX * -mouth.forwardZ() + offsetZ * mouth.forwardX()
            );
            if (depth < -12.5D || depth > 13.5D || lateral > 5.5D) {
                continue;
            }
            int caveFloorY = caveEntranceFloorY(world, entrance);
            int openingHeight = lateral <= 2.5D ? 11
                : lateral <= 3.5D ? 10
                : lateral <= 4.5D ? 9 : 8;
            if (y > caveFloorY && y <= caveFloorY + openingHeight) {
                return true;
            }
        }
        return false;
    }

    private static int caveEntranceFloorY(
        HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
        NativeTerrainColumn column = nativeTerrainColumn(
            world, mouth.x(), mouth.z()
        );
        return column.groundY();
    }

    private static void restoreCaveEntranceBarrierRoof(
        ServerLevel level, HexWorldPlan world, CaveEntrancePlan entrance
    ) {
        CaveMouthGeometry mouth = caveMouthGeometry(world, entrance);
        Point center = mouth.tileCenter();
        double sideX = -mouth.forwardZ();
        double sideZ = mouth.forwardX();
        int maximumForward = (int) Math.ceil(mouth.collisionDistance() + 18.0D);
        TransitionRegion entryRegion = ACTIVE_SURFACE_ENTRY_REGIONS.get(entrance.id());
        Set<Long> visited = new HashSet<>();
        for (int forward = -6; forward <= maximumForward; forward++) {
            for (int lateral = -6; lateral <= 6; lateral++) {
                int x = center.x() + (int) Math.round(
                    mouth.forwardX() * forward + sideX * lateral
                );
                int z = center.z() + (int) Math.round(
                    mouth.forwardZ() * forward + sideZ * lateral
                );
                if (!visited.add(blockColumnKey(x, z))
                    || !isHiddenBoundaryCollisionColumn(world, x, z)) {
                    continue;
                }
                int groundY = nativeTerrainColumn(world, x, z).groundY();
                int barrierStartY = Math.min(
                    groundY + 1, caveEntranceFloorY(world, entrance) + 1
                );
                for (int y = barrierStartY; y < level.getMaxBuildHeight(); y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (entryRegion != null && entryRegion.contains(position)) {
                        continue;
                    }
                    if (isCaveBarrierOpening(world, x, y, z, groundY)) {
                        if (level.getBlockState(position).is(Blocks.BARRIER)) {
                            level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                        }
                    } else {
                        placeBarrierInAirOrWater(level, position);
                    }
                }
            }
        }
    }

    private static int clearStaleBarrierColumn(
        ServerLevel level, HexWorldPlan world, int x, int z
    ) {
        int removed = 0;
        NativeTerrainColumn column = nativeTerrainColumn(world, x, z);
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            BlockPos position = new BlockPos(x, y, z);
            if (!level.getBlockState(position).is(Blocks.BARRIER)) {
                continue;
            }
            BlockState replacement;
            if (y > column.groundY() && y <= column.waterTopY()) {
                replacement = Blocks.WATER.defaultBlockState();
            } else if (y <= DEEP_FOUNDATION_MAX_Y) {
                replacement = Blocks.BEDROCK.defaultBlockState();
            } else if (y <= column.groundY()) {
                replacement = Blocks.STONE.defaultBlockState();
            } else {
                replacement = Blocks.AIR.defaultBlockState();
            }
            level.setBlock(position, replacement, 2);
            removed++;
        }
        return removed;
    }

    private static boolean placeBarrierInAirOrWater(
        ServerLevel level, BlockPos position
    ) {
        BlockState state = level.getBlockState(position);
        if (!state.isAir() && !state.is(Blocks.WATER)) {
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
            // Falling back to oak would brighten an authored dark-forest edge.
            featureIds = List.of("dark_oak_checked");
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

    static boolean placeNaturalGateTree(
        ServerLevel level, String log, String leaves, BlockPos ground, long seed
    ) {
        return placeVanillaTree(
            level, ground.above(), new TreeProfile(log, leaves, 1, 5, 10), seed
        );
    }

    static BlockState naturalGateGroundDecoration(
        ServerLevel level, String terrainType, BlockPos ground, long seed
    ) {
        return openBiomeGroundDecoration(
            level, emptyTerrainBiome(terrainType), ground, seed
        );
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
        int[] streetDecorations = decoratePlannedTownStreets(
            level, world, settlement, biome, baseSeed
        );
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
            "Town landscaping completed: settlement={}, biome={}, cells={}, trees={}, groundDecorations={}, streetLamps={}, streetTrees={}, benches={}, flowerBeds={}, fountains={}",
            settlement.id(), biome, townCellCount(world, settlement.id()), trees,
            groundDecorations, streetDecorations[0], streetDecorations[1],
            streetDecorations[2], streetDecorations[3], streetDecorations[4]
        );
    }

    private static int[] decoratePlannedTownStreets(
        ServerLevel level,
        HexWorldPlan world,
        SettlementPlan settlement,
        String biome,
        long baseSeed
    ) {
        int[] placed = new int[5];
        Point center = new Point(settlement.center().x(), settlement.center().z());
        for (TownDecoration decoration : generateTownLayout(settlement).decorations()) {
            int x = center.x() + decoration.x();
            int z = center.z() + decoration.z();
            TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
            if (sample == null || !sample.kind().equals("town")
                || !sample.owner().equals(settlement.id())) {
                continue;
            }
            int groundY = terrainGroundY(world, sample, x, z);
            BlockPos ground = new BlockPos(x, groundY, z);
            TownDecorationTemplate template = townDecorationTemplate(decoration.type());
            if (template == null) {
                continue;
            }
            TownDecorationPlacement placement = townDecorationPlacement(
                level, decoration, template, ground
            );
            if (placement == null
                || townDecorationIntersectsBuilding(settlement, placement, 1)
                || !isTownDecorationVolumeClear(level, placement)) {
                continue;
            }
            clearVegetationAroundPlot(
                level, placement.minX(), placement.minZ(),
                placement.width(), placement.depth(), 0
            );
            if (placeTownDecorationTemplate(level, template, placement)) {
                placed[template.counterIndex()]++;
            }
        }
        return placed;
    }

    private static TownDecorationPlacement townDecorationPlacement(
        ServerLevel level, TownDecoration decoration,
        TownDecorationTemplate template, BlockPos ground
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(template.structure());
        if (structureId == null) {
            return null;
        }
        var loaded = level.getStructureManager().get(structureId);
        if (loaded.isEmpty()) {
            LOGGER.error("Town decoration template is missing: {}", template.structure());
            return null;
        }
        var size = loaded.orElseThrow().getSize();
        boolean quarterTurn = decoration.rotation().equals("clockwise_90")
            || decoration.rotation().equals("counterclockwise_90");
        int width = quarterTurn ? size.getZ() : size.getX();
        int depth = quarterTurn ? size.getX() : size.getZ();
        int minX = ground.getX() - width / 2;
        int minZ = ground.getZ() - depth / 2;
        BlockPoint origin = rotatedTemplateOrigin(
            minX,
            ground.getY() + BuildingRuntimeSystem.placementYOffset(template.structure()),
            minZ,
            size.getX(), size.getZ(), decoration.rotation()
        );
        return new TownDecorationPlacement(
            origin, minX, minZ, width, depth, size.getY(), decoration.rotation()
        );
    }

    private static boolean placeTownDecorationTemplate(
        ServerLevel level, TownDecorationTemplate template,
        TownDecorationPlacement placement
    ) {
        return placeTemplateLoaded(
            level, template.structure(), placement.origin(), placement.rotation()
        );
    }

    private static boolean townDecorationIntersectsBuilding(
        SettlementPlan settlement, TownDecorationPlacement decoration, int clearance
    ) {
        Point center = new Point(settlement.center().x(), settlement.center().z());
        TownLayout layout = generateTownLayout(settlement);
        for (TownPlot plot : layout.houses()) {
            if (townDecorationIntersectsPlot(center, plot, decoration, clearance)) {
                return true;
            }
        }
        for (TownPlot plot : layout.facilities().values()) {
            if (townDecorationIntersectsPlot(center, plot, decoration, clearance)) {
                return true;
            }
        }
        return false;
    }

    private static boolean townDecorationIntersectsPlot(
        Point center, TownPlot plot, TownDecorationPlacement decoration, int clearance
    ) {
        int plotMinX = center.x() + (int) Math.round(plot.x());
        int plotMinZ = center.z() + (int) Math.round(plot.z());
        int decorationMaxX = decoration.minX() + decoration.width();
        int decorationMaxZ = decoration.minZ() + decoration.depth();
        return decoration.minX() - clearance < plotMinX + plot.width()
            && decorationMaxX + clearance > plotMinX
            && decoration.minZ() - clearance < plotMinZ + plot.depth()
            && decorationMaxZ + clearance > plotMinZ;
    }

    private static boolean isTownDecorationVolumeClear(
        ServerLevel level, TownDecorationPlacement decoration
    ) {
        int minimumY = decoration.origin().y() + 1;
        int maximumY = Math.min(
            level.getMaxBuildHeight() - 1,
            decoration.origin().y() + decoration.height() - 1
        );
        for (int x = decoration.minX(); x < decoration.minX() + decoration.width(); x++) {
            for (int z = decoration.minZ(); z < decoration.minZ() + decoration.depth(); z++) {
                BlockState ground = level.getBlockState(
                    new BlockPos(x, decoration.origin().y(), z)
                );
                if (!ground.isAir() && !isNaturalVegetation(ground)
                    && !isTownDecorationGround(ground)) {
                    return false;
                }
                for (int y = minimumY; y <= maximumY; y++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir() && !isNaturalVegetation(state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static TownDecorationTemplate townDecorationTemplate(String type) {
        return switch (type) {
            case "street_lamp" -> new TownDecorationTemplate(
                "cobbleventure:town_decorations/street_lamp", 0
            );
            case "street_tree" -> new TownDecorationTemplate(
                "cobbleventure:town_decorations/street_tree", 1
            );
            case "bench" -> new TownDecorationTemplate(
                "cobbleventure:town_decorations/bench", 2
            );
            case "flower_bed" -> new TownDecorationTemplate(
                "cobbleventure:town_decorations/flower_bed", 3
            );
            case "fountain" -> new TownDecorationTemplate(
                "cobbleventure:town_decorations/fountain", 4
            );
            default -> null;
        };
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
        if (!isTownDecorationGround(level.getBlockState(new BlockPos(x, groundY, z)))) {
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

    private static boolean isTownDecorationGround(BlockState state) {
        return isNaturalTownGround(state)
            || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.MOSSY_COBBLESTONE)
            || state.is(Blocks.STONE)
            || state.is(Blocks.STONE_BRICKS)
            || state.is(Blocks.BRICKS)
            || state.is(Blocks.DIRT_PATH)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.PACKED_MUD)
            || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.POLISHED_DIORITE);
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

    private static void spawnRouteNpcs(ServerLevel level, HexWorldPlan world) {
        int spawned = 0;
        for (ConnectionPath route : world.paths()) {
            if (route.centerline().size() < 2) continue;
            for (RouteNpcPlacement placement : route.npcPlacements()) {
                double roll = Math.floorMod(
                    Objects.hash(world.seed(), route.id(), placement.id()), 100_000
                ) / 100_000.0D;
                if (roll >= placement.spawnChance()) continue;
                RouteNpcPoint point = routeNpcPoint(route.centerline(), placement.progressPercent());
                double side = placement.side().equals("left") ? -1.0D
                    : placement.side().equals("right") ? 1.0D : 0.0D;
                int x = (int) Math.round(point.x() + point.normalX() * placement.offsetBlocks() * side);
                int z = (int) Math.round(point.z() + point.normalZ() * placement.offsetBlocks() * side);
                loadRouteNpcChunk(level, x, z);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double facingX = placement.facing().equals("against") ? -point.tangentX() : point.tangentX();
                double facingZ = placement.facing().equals("against") ? -point.tangentZ() : point.tangentZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-facingX, facingZ));
                if (spawnRegionalNpc(
                    level, placement.npc(), new BlockPos(x, y, z), yaw,
                    placement.triggerOverride()
                )) spawned++;
            }
            RegionalTrainerPopulation population = route.trainerPopulation();
            int count = Math.min(population.count(), population.candidates().size());
            for (int index = 0; population.enabled() && index < count; index++) {
                int preferredProgress = Math.round((index + 1) * 100.0F / (count + 1));
                RouteNpcPoint point = null;
                int x = 0;
                int z = 0;
                double side = index % 2 == 0 ? 1.0D : -1.0D;
                for (int attempt = 0; attempt <= 18; attempt++) {
                    int step = (attempt + 1) / 2 * 5;
                    int progress = Math.max(5, Math.min(
                        95, preferredProgress + (attempt % 2 == 0 ? -step : step)
                    ));
                    RouteNpcPoint candidate = routeNpcPoint(route.centerline(), progress);
                    int candidateX = (int) Math.round(
                        candidate.x() + candidate.normalX() * 3.0D * side
                    );
                    int candidateZ = (int) Math.round(
                        candidate.z() + candidate.normalZ() * 3.0D * side
                    );
                    if (isRegionalEntranceHex(world, candidateX, candidateZ)) continue;
                    point = candidate;
                    x = candidateX;
                    z = candidateZ;
                    break;
                }
                if (point == null) {
                    LOGGER.warn(
                        "Route trainer has no position outside entrance tiles: route={}, npc={}",
                        route.id(), population.candidates().get(index)
                    );
                    continue;
                }
                loadRouteNpcChunk(level, x, z);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                float yaw = (float) Math.toDegrees(Math.atan2(-point.tangentX(), point.tangentZ()));
                if (spawnRegionalNpc(
                    level, population.candidates().get(index), new BlockPos(x, y, z), yaw,
                    population.triggerFor(population.candidates().get(index))
                )) spawned++;
            }
        }
        if (spawned > 0) LOGGER.info("Route NPC placement completed: spawned={}", spawned);
    }

    private static void loadRouteNpcChunk(ServerLevel level, int x, int z) {
        // getHeight can return the minimum build height while a native route chunk is
        // still absent. Resolve the full chunk before selecting a standing position.
        level.getChunk(x >> 4, z >> 4);
    }

    private static boolean isRegionalEntranceHex(HexWorldPlan world, int x, int z) {
        HexCoord coordinate = world.grid().worldToHex(x + 0.5D, z + 0.5D);
        return world.gates().stream().anyMatch(gate -> gate.anchor().equals(coordinate))
            || world.caveEntrances().stream().anyMatch(
                entrance -> entrance.anchor().equals(coordinate)
            );
    }

    private static RouteNpcPoint routeNpcPoint(List<Point> centerline, int progressPercent) {
        double total = 0.0D;
        for (int index = 1; index < centerline.size(); index++) {
            Point a = centerline.get(index - 1), b = centerline.get(index);
            total += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        double remaining = total * Math.max(0, Math.min(100, progressPercent)) / 100.0D;
        for (int index = 1; index < centerline.size(); index++) {
            Point a = centerline.get(index - 1), b = centerline.get(index);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            if (remaining <= length || index == centerline.size() - 1) {
                double ratio = length <= 0.0001D ? 0.0D : Math.min(1.0D, remaining / length);
                double tangentX = length <= 0.0001D ? 0.0D : dx / length;
                double tangentZ = length <= 0.0001D ? 1.0D : dz / length;
                return new RouteNpcPoint(
                    a.x() + dx * ratio, a.z() + dz * ratio,
                    tangentX, tangentZ, tangentZ, -tangentX
                );
            }
            remaining -= length;
        }
        Point last = centerline.getLast();
        return new RouteNpcPoint(last.x(), last.z(), 0.0D, 1.0D, 1.0D, 0.0D);
    }

    static boolean spawnRegionalNpc(
        ServerLevel level, String npcId, BlockPos position, float yaw, String triggerOverride
    ) {
        BlockPos safePosition = findRegionalNpcPosition(level, position);
        if (safePosition == null) {
            LOGGER.warn(
                "Regional NPC has no safe standing position: npc={}, requested={}",
                npcId, position
            );
            return false;
        }
        if (!safePosition.equals(position)) {
            LOGGER.info(
                "Regional NPC position adjusted: npc={}, requested={}, resolved={}",
                npcId, position, safePosition
            );
        }
        AABB nearby = new AABB(safePosition).inflate(1.75D, 2.5D, 1.75D);
        Set<Entity> nearbyEntities = new LinkedHashSet<>(
            level.getEntitiesOfClass(Entity.class, nearby)
        );
        if (!safePosition.equals(position)) {
            nearbyEntities.addAll(level.getEntitiesOfClass(
                Entity.class, new AABB(position).inflate(1.75D, 2.5D, 1.75D)
            ));
        }
        List<Entity> existingNpcs = nearbyEntities.stream()
            .filter(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
            .getNamespace().equals("easy_npc"))
            .toList();
        boolean cvesV5 = npcPresetSuffix(level, npcId).equals("__v5");
        String suffix = RegionalNpcPresetSelection.suffix(cvesV5, triggerOverride);
        boolean useCvesV5 = suffix.startsWith("__v5");
        Entity currentNpc = existingNpcs.stream()
            .filter(entity -> RegionalNpcPresetSelection.matches(
                useCvesV5, triggerOverride, entity.getTags()
            ))
            .findFirst()
            .orElse(null);
        if (currentNpc != null) {
            currentNpc.teleportTo(
                safePosition.getX() + 0.5D, safePosition.getY(), safePosition.getZ() + 0.5D
            );
            currentNpc.setYRot(yaw);
            for (Entity duplicate : existingNpcs) {
                if (duplicate != currentNpc) duplicate.discard();
            }
            return true;
        }
        // Regional NPCs are authored from generated presets. Refresh the NPC
        // occupying this slot once when its revision changes. Current NPCs are
        // kept across restarts so their UUID-based per-player battle state is
        // stable.
        for (Entity existingNpc : existingNpcs) {
            existingNpc.discard();
        }
        if (!existingNpcs.isEmpty()) {
            LOGGER.info(
                "Refreshing regional NPC preset: npc={}, position={}, replaced={}",
                npcId, safePosition, existingNpcs.size()
            );
        }
        String slug = npcId.substring(Math.max(npcId.lastIndexOf('/'), npcId.lastIndexOf(':')) + 1);
        String command = "easy_npc preset import_new data easy_npc:preset/encounter/"
            + slug + suffix + ".npc.snbt " + safePosition.getX() + " "
            + safePosition.getY() + " " + safePosition.getZ();
        try {
            int result = level.getServer().getCommands().getDispatcher().execute(
                command,
                level.getServer().createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(Vec3.atLowerCornerOf(safePosition))
                    .withRotation(new Vec2(0.0F, yaw))
                    .withPermission(4)
                    .withSuppressedOutput()
            );
            if (result == 0) LOGGER.warn("Regional NPC command returned no result: npc={}, position={}", npcId, safePosition);
            return result != 0;
        } catch (CommandSyntaxException error) {
            LOGGER.error("Regional NPC placement failed: npc={}, position={}", npcId, safePosition, error);
            return false;
        }
    }

    static String npcPresetSuffix(ServerLevel level, String npcId) {
        JsonObject catalog = readJsonResource(level, "catalogs/npc-placement-profiles.json");
        for (JsonElement element : catalog.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            if (npcId.equals(requiredString(profile, "npc"))
                && profile.has("event_engine")
                && "cves_v5".equals(profile.get("event_engine").getAsString())) {
                return "__v5";
            }
        }
        return "";
    }

    private static BlockPos findRegionalNpcPosition(ServerLevel level, BlockPos requested) {
        for (int vertical = 0; vertical <= 8; vertical++) {
            int[] offsets = vertical == 0
                ? new int[] {0} : new int[] {vertical, -vertical};
            for (int dy : offsets) {
                for (int radius = 0; radius <= 5; radius++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (radius > 0 && Math.abs(dx) != radius
                                && Math.abs(dz) != radius) {
                                continue;
                            }
                            BlockPos candidate = requested.offset(dx, dy, dz);
                            if (canRegionalNpcStandAt(level, candidate)
                                && hasRegionalNpcExit(level, candidate)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean canRegionalNpcStandAt(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinBuildHeight()
            || feet.getY() >= level.getMaxBuildHeight() - 1) return false;
        BlockPos floor = feet.below();
        BlockPos head = feet.above();
        AABB npcBounds = new AABB(
            feet.getX() + 0.15D, feet.getY(), feet.getZ() + 0.15D,
            feet.getX() + 0.85D, feet.getY() + 1.9D, feet.getZ() + 0.85D
        );
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
            && level.getFluidState(floor).isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getFluidState(feet).isEmpty()
            && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
            && level.getFluidState(head).isEmpty()
            && level.noCollision(npcBounds);
    }

    private static boolean hasRegionalNpcExit(ServerLevel level, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = feet.relative(direction);
            if (canRegionalNpcStandAt(level, adjacent)
                || canRegionalNpcStandAt(level, adjacent.above())
                || canRegionalNpcStandAt(level, adjacent.below())) {
                return true;
            }
        }
        return false;
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
            if (connection.surfaceStyle().equals("log_bridge")) {
                drawLogBridge(level, world, connection, centerline);
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

    private static void drawLogBridge(
        ServerLevel level, HexWorldPlan world,
        ConnectionPath connection, List<Point> centerline
    ) {
        for (int index = 1; index < centerline.size(); index++) {
            Point start = centerline.get(index - 1);
            Point end = centerline.get(index);
            int dx = end.x() - start.x();
            int dz = end.z() - start.z();
            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps == 0) continue;
            double length = Math.max(1.0D, Math.hypot(dx, dz));
            double normalX = -dz / length;
            double normalZ = dx / length;
            for (int step = 0; step <= steps; step++) {
                double factor = step / (double) steps;
                int centerX = (int) Math.round(start.x() + dx * factor);
                int centerZ = (int) Math.round(start.z() + dz * factor);
                for (int side = -2; side <= 2; side++) {
                    int x = (int) Math.round(centerX + normalX * side);
                    int z = (int) Math.round(centerZ + normalZ * side);
                    LogBridgeDeckPlan plan = logBridgeDeckAt(world, x, z);
                    if (plan == null) continue;
                    if (plan.support()) {
                        TerrainSample sample = terrainAt(world, x + 0.5D, z + 0.5D);
                        int floorY = terrainGroundY(world, sample, x, z);
                        for (int y = floorY + 1; y < plan.y(); y++) {
                            level.setBlock(
                                new BlockPos(x, y, z),
                                Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), 2
                            );
                        }
                    }
                    level.setBlock(new BlockPos(x, plan.y(), z), plan.state(), 2);
                    for (int y = plan.y() + 1; y <= plan.y() + 4; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        LOGGER.info(
            "Log bridge completed: route={}, deck=unlit_campfire, supports=stripped_spruce_log",
            connection.id()
        );
    }

    private static void repairLogBridgeChunk(ServerLevel level, ChunkPos chunk) {
        HexWorldPlan world = activeHexWorld;
        if (world == null || world.paths().stream().noneMatch(route ->
            route.surfaceStyle().equals("log_bridge")
                && route.bounds().contains(
                    chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(),
                    route.corridorWidthBlocks()
                ))) {
            return;
        }
        int repaired = 0;
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                LogBridgeDeckPlan deck = logBridgeDeckAt(world, x, z);
                if (deck == null) continue;
                NativeTerrainColumn terrain = nativeTerrainColumn(world, x, z);
                if (deck.overOcean()) {
                    BlockPos floorPosition = new BlockPos(x, terrain.groundY(), z);
                    if (!level.getBlockState(floorPosition).is(Blocks.GRAVEL)) {
                        level.setBlock(floorPosition, Blocks.GRAVEL.defaultBlockState(), 2);
                        repaired++;
                    }
                    for (int y = terrain.groundY() + 1; y <= WATER_SURFACE_Y; y++) {
                        BlockPos waterPosition = new BlockPos(x, y, z);
                        if (!level.getBlockState(waterPosition).is(Blocks.WATER)) {
                            level.setBlock(waterPosition, Blocks.WATER.defaultBlockState(), 2);
                            repaired++;
                        }
                    }
                } else {
                    int fillStart = Math.min(
                        WATER_SURFACE_Y - 20, terrain.groundY() - 4
                    );
                    for (int y = fillStart; y < terrain.groundY(); y++) {
                        BlockPos fillerPosition = new BlockPos(x, y, z);
                        if (!level.getBlockState(fillerPosition).equals(terrain.filler())) {
                            level.setBlock(fillerPosition, terrain.filler(), 2);
                            repaired++;
                        }
                    }
                    BlockState road = worldRoadSurfaceBlock(world, x, z);
                    BlockPos roadPosition = new BlockPos(x, terrain.groundY(), z);
                    if (!level.getBlockState(roadPosition).equals(road)) {
                        level.setBlock(roadPosition, road, 2);
                        repaired++;
                    }
                    for (int y = terrain.groundY() + 1; y < deck.y(); y++) {
                        BlockPos airPosition = new BlockPos(x, y, z);
                        if (!level.getBlockState(airPosition).isAir()) {
                            level.setBlock(airPosition, Blocks.AIR.defaultBlockState(), 2);
                            repaired++;
                        }
                    }
                }
                BlockPos oldDeckPosition = new BlockPos(x, WATER_SURFACE_Y, z);
                if (!oldDeckPosition.equals(new BlockPos(x, deck.y(), z))) {
                    BlockState oldDeck = level.getBlockState(oldDeckPosition);
                    if (oldDeck.is(Blocks.SPRUCE_PLANKS)
                        || oldDeck.is(Blocks.STRIPPED_SPRUCE_LOG)
                        || oldDeck.is(Blocks.CAMPFIRE)) {
                        level.setBlock(
                            oldDeckPosition, deck.overOcean()
                                ? Blocks.WATER.defaultBlockState()
                                : terrain.filler(), 2
                        );
                        repaired++;
                    }
                }
                if (deck.support()) {
                    for (int y = terrain.groundY() + 1; y < deck.y(); y++) {
                        BlockPos supportPosition = new BlockPos(x, y, z);
                        if (!level.getBlockState(supportPosition).is(
                            Blocks.STRIPPED_SPRUCE_LOG
                        )) {
                            level.setBlock(
                                supportPosition,
                                Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), 2
                            );
                            repaired++;
                        }
                    }
                }
                BlockPos deckPosition = new BlockPos(x, deck.y(), z);
                if (!level.getBlockState(deckPosition).equals(deck.state())) {
                    level.setBlock(deckPosition, deck.state(), 2);
                    repaired++;
                }
            }
        }
        if (repaired > 0) {
            LOGGER.info("Repaired log bridge chunk: chunk={}, blocks={}", chunk, repaired);
        }
    }

    private record RouteNpcPoint(
        double x, double z, double tangentX, double tangentZ,
        double normalX, double normalZ
    ) {}

    private static boolean insideConnectionTownCore(
        HexWorldPlan world, ConnectionPath connection, Point point
    ) {
        return insideSettlementRoadClip(world, connection.from(), point)
            || insideSettlementRoadClip(world, connection.to(), point);
    }

    private static boolean insideSettlementRoadClip(
        HexWorldPlan world, String settlementId, Point point
    ) {
        TerrainSample underlying = cellInfluence(
            world, point.x() + 0.5D, point.z() + 0.5D
        );
        return underlying != null
            && underlying.kind().equals("town")
            && underlying.owner().equals(settlementId);
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
        if (connection.surfaceStyle().equals("log_bridge")) {
            Set<HexCoord> routeArea = new HashSet<>(cells);
            HexSettlement from = settlements.get(connection.from());
            HexSettlement to = settlements.get(connection.to());
            if (from != null) routeArea.addAll(townFootprint(from));
            if (to != null) routeArea.addAll(townFootprint(to));
            controls = orthogonalLogBridgeControls(
                grid, controls, routeArea, connection.logBridgeLayout()
            );
            return List.copyOf(densifyRouteControls(controls, 12.0D));
        }
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

    private static List<WarpedPoint> orthogonalLogBridgeControls(
        HexGrid grid,
        List<WarpedPoint> controls,
        Set<HexCoord> routeArea,
        LogBridgeLayout layout
    ) {
        if (controls.size() < 2) {
            return controls;
        }
        List<WarpedPoint> orthogonal = new ArrayList<>();
        appendRouteControl(orthogonal, controls.getFirst());
        for (int segment = 1; segment < controls.size(); segment++) {
            WarpedPoint start = controls.get(segment - 1);
            WarpedPoint end = controls.get(segment);
            boolean horizontalFirst = Math.floorMod(segment, 2) == 0;
            WarpedPoint preferredElbow = horizontalFirst
                ? new WarpedPoint(end.x(), start.z())
                : new WarpedPoint(start.x(), end.z());
            WarpedPoint alternateElbow = horizontalFirst
                ? new WarpedPoint(start.x(), end.z())
                : new WarpedPoint(end.x(), start.z());
            List<WarpedPoint> bend = axisBend(start, preferredElbow, end);
            if (!axisControlsInsideRouteArea(grid, routeArea, bend, 3)) {
                bend = axisBend(start, alternateElbow, end);
            }
            if (!axisControlsInsideRouteArea(grid, routeArea, bend, 3)) {
                bend = midpointAxisBend(start, end, horizontalFirst);
            }
            if (!axisControlsInsideRouteArea(grid, routeArea, bend, 3)) {
                bend = midpointAxisBend(start, end, !horizontalFirst);
            }
            if (!axisControlsInsideRouteArea(grid, routeArea, bend, 3)) {
                bend = axisBend(start, preferredElbow, end);
            }
            if (!axisControlsInsideRouteArea(grid, routeArea, bend, 0)) {
                bend = axisBend(start, alternateElbow, end);
            }
            if (!axisControlsInsideRouteArea(grid, routeArea, bend, 0)) {
                throw new IllegalStateException(
                    "Could not keep axis-aligned log bridge inside its authored tiles: "
                        + start + " -> " + end
                );
            }
            for (int leg = 1; leg < bend.size(); leg++) {
                appendPatternedLogBridgeSegment(
                    grid, routeArea, orthogonal,
                    bend.get(leg - 1), bend.get(leg),
                    segment * 2 - 2 + leg, layout
                );
            }
        }
        return orthogonal;
    }

    private static List<WarpedPoint> axisBend(
        WarpedPoint start, WarpedPoint elbow, WarpedPoint end
    ) {
        List<WarpedPoint> bend = new ArrayList<>();
        appendRouteControl(bend, start);
        appendRouteControl(bend, elbow);
        appendRouteControl(bend, end);
        return bend;
    }

    private static List<WarpedPoint> midpointAxisBend(
        WarpedPoint start, WarpedPoint end, boolean horizontalMiddle
    ) {
        List<WarpedPoint> bend = new ArrayList<>();
        appendRouteControl(bend, start);
        if (horizontalMiddle) {
            double middleZ = (start.z() + end.z()) * 0.5D;
            appendRouteControl(bend, new WarpedPoint(start.x(), middleZ));
            appendRouteControl(bend, new WarpedPoint(end.x(), middleZ));
        } else {
            double middleX = (start.x() + end.x()) * 0.5D;
            appendRouteControl(bend, new WarpedPoint(middleX, start.z()));
            appendRouteControl(bend, new WarpedPoint(middleX, end.z()));
        }
        appendRouteControl(bend, end);
        return bend;
    }

    private static void appendPatternedLogBridgeSegment(
        HexGrid grid,
        Set<HexCoord> routeArea,
        List<WarpedPoint> controls,
        WarpedPoint start,
        WarpedPoint end,
        int segment,
        LogBridgeLayout layout
    ) {
        if (start.equals(end)) {
            return;
        }
        double dx = end.x() - start.x();
        double dz = end.z() - start.z();
        if (dx != 0.0D && dz != 0.0D) {
            throw new IllegalArgumentException(
                "Patterned log bridge segment must be axis-aligned"
            );
        }
        double length = Math.abs(dx) + Math.abs(dz);
        int pattern = logBridgePatternAt(layout.pattern(), segment);
        if (length < 72.0D || pattern < 0) {
            appendRouteControl(controls, end);
            return;
        }

        double requested = Math.max(6.0D, Math.min(24.0D, layout.detourBlocks()));
        for (double detour = requested; detour >= 6.0D; detour -= 2.0D) {
            for (double direction : new double[] {1.0D, -1.0D}) {
                List<WarpedPoint> candidate = patternedLogBridgeSegment(
                    start, end, pattern, detour * direction
                );
                if (!axisControlsInsideRouteArea(grid, routeArea, candidate, 3)) continue;
                for (int index = 1; index < candidate.size(); index++) {
                    appendRouteControl(controls, candidate.get(index));
                }
                return;
            }
        }
        appendRouteControl(controls, end);
    }

    private static int logBridgePatternAt(String pattern, int segment) {
        return switch (pattern) {
            case "u_turn" -> Math.floorMod(segment, 4) == 1 ? 0 : -1;
            case "zigzag" -> Math.floorMod(segment, 4) == 1 ? 1 : -1;
            case "alternating" -> Math.floorMod(segment, 6) == 1 ? 0
                : Math.floorMod(segment, 6) == 4 ? 1 : -1;
            default -> -1;
        };
    }

    private static List<WarpedPoint> patternedLogBridgeSegment(
        WarpedPoint start, WarpedPoint end, int pattern, double side
    ) {
        List<WarpedPoint> candidate = new ArrayList<>();
        appendRouteControl(candidate, start);
        double dx = end.x() - start.x();
        double dz = end.z() - start.z();
        double length = Math.abs(dx) + Math.abs(dz);
        double directionX = Math.signum(dx);
        double directionZ = Math.signum(dz);
        if (pattern == 0) {
            appendAlongRoute(candidate, start, directionX, directionZ, length * 0.34D);
            appendAcrossRoute(candidate, directionX, directionZ, side);
            appendAlongRoute(candidate, candidate.getLast(), directionX, directionZ, length * 0.32D);
            appendAcrossRoute(candidate, directionX, directionZ, -side);
        } else {
            appendAlongRoute(candidate, start, directionX, directionZ, length * 0.25D);
            appendAcrossRoute(candidate, directionX, directionZ, side);
            appendAlongRoute(candidate, candidate.getLast(), directionX, directionZ, length * 0.25D);
            appendAcrossRoute(candidate, directionX, directionZ, -side * 2.0D);
            appendAlongRoute(candidate, candidate.getLast(), directionX, directionZ, length * 0.25D);
            appendAcrossRoute(candidate, directionX, directionZ, side);
        }
        appendRouteControl(candidate, end);
        return candidate;
    }

    private static boolean axisControlsInsideRouteArea(
        HexGrid grid, Set<HexCoord> routeArea,
        List<WarpedPoint> controls, int margin
    ) {
        for (int segment = 1; segment < controls.size(); segment++) {
            WarpedPoint start = controls.get(segment - 1);
            WarpedPoint end = controls.get(segment);
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            if (dx != 0.0D && dz != 0.0D) return false;
            int steps = Math.max(1, (int) Math.ceil(Math.abs(dx) + Math.abs(dz)));
            for (int step = 0; step <= steps; step++) {
                double progress = step / (double) steps;
                double x = start.x() + dx * progress;
                double z = start.z() + dz * progress;
                int stepSize = Math.max(1, margin);
                for (int offsetX = -margin; offsetX <= margin; offsetX += stepSize) {
                    for (int offsetZ = -margin; offsetZ <= margin; offsetZ += stepSize) {
                        HexCoord cell = grid.worldToHex(x + offsetX, z + offsetZ);
                        if (!routeArea.contains(cell)) return false;
                    }
                }
            }
        }
        return true;
    }

    private static void appendAlongRoute(
        List<WarpedPoint> controls,
        WarpedPoint start,
        double directionX,
        double directionZ,
        double distance
    ) {
        appendRouteControl(controls, new WarpedPoint(
            start.x() + directionX * distance,
            start.z() + directionZ * distance
        ));
    }

    private static void appendAcrossRoute(
        List<WarpedPoint> controls,
        double directionX,
        double directionZ,
        double distance
    ) {
        WarpedPoint start = controls.getLast();
        appendRouteControl(controls, new WarpedPoint(
            start.x() - directionZ * distance,
            start.z() + directionX * distance
        ));
    }

    private static void appendRouteControl(
        List<WarpedPoint> controls, WarpedPoint point
    ) {
        if (!controls.isEmpty() && controls.getLast().equals(point)) {
            return;
        }
        controls.add(point);
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
        Set<HexCoord> footprint = townFootprint(settlement);
        int firstOutside = -1;
        for (int index = 0; index < ordered.size(); index++) {
            WarpedPoint point = ordered.get(index);
            if (!footprint.contains(grid.worldToHex(point.x(), point.z()))) {
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
        double edgeRadius = townRouteEdgeRadius(
            grid, settlement, dx, dz
        );
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

    private static double townRouteEdgeRadius(
        HexGrid grid, HexSettlement settlement, double directionX, double directionZ
    ) {
        double length = Math.hypot(directionX, directionZ);
        if (length < 1.0D) {
            return grid.radius() + 2.0D;
        }
        double unitX = directionX / length;
        double unitZ = directionZ / length;
        Point center = townFootprintWorldCenter(grid, settlement);
        Set<HexCoord> footprint = townFootprint(settlement);
        int maximumDistance = Math.max(
            grid.radius() * 2,
            (townFootprintRadius(settlement) + 2) * grid.radius() * 2
        );
        for (int distance = 0; distance <= maximumDistance; distance++) {
            HexCoord coordinate = grid.worldToHex(
                center.x() + unitX * distance,
                center.z() + unitZ * distance
            );
            if (!footprint.contains(coordinate)) {
                return distance + 2.0D;
            }
        }
        throw new IllegalStateException(
            "Could not find town footprint edge: " + settlement.settlement()
        );
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
            Point approach = settlementRouteApproach(
                world, connection.centerline(), settlement.id(), toTown
            );
            if (approach == null) {
                LOGGER.warn(
                    "Town route could not find a land approach: settlement={}, route={}",
                    settlement.id(), connection.id()
                );
                continue;
            }
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
                level, gateRoad, approach, settlement.roadProfile(),
                true, true
            );
            drawTownGate(
                level, world, settlement, gateRoad, direction,
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

    private static boolean isConfiguredRoadSlab(BlockState state) {
        return state.is(Blocks.STONE_BRICK_SLAB)
            || state.is(Blocks.COBBLESTONE_SLAB)
            || state.is(Blocks.MUD_BRICK_SLAB)
            || state.is(Blocks.SANDSTONE_SLAB)
            || state.is(Blocks.QUARTZ_SLAB);
    }

    private static boolean isConfiguredRoadStair(BlockState state) {
        return state.is(Blocks.STONE_BRICK_STAIRS)
            || state.is(Blocks.COBBLESTONE_STAIRS)
            || state.is(Blocks.MOSSY_COBBLESTONE_STAIRS)
            || state.is(Blocks.ANDESITE_STAIRS)
            || state.is(Blocks.STONE_STAIRS)
            || state.is(Blocks.BRICK_STAIRS)
            || state.is(Blocks.MUD_BRICK_STAIRS)
            || state.is(Blocks.SANDSTONE_STAIRS)
            || state.is(Blocks.QUARTZ_STAIRS);
    }

    private static Point settlementRouteApproach(
        HexWorldPlan world, List<Point> centerline, String settlementId, boolean reverse
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
            if (!insideSettlementRoadClip(world, settlementId, point)) {
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
        Point reusable = null;
        double reusableScore = Double.POSITIVE_INFINITY;
        TownLayout layout = generateTownLayout(settlement);
        Set<Point> endpoints = new HashSet<>();
        for (Point exit : layout.externalExits()) {
            endpoints.add(townCenter.translate(exit.x(), exit.z()));
        }
        // Compiled exits are preferred authoring hints, but they may not include
        // every direction used by the world graph. Include every main-road end
        // so a route is never abandoned just because no authored exit faces it.
        for (TownRoad road : layout.roads()) {
            endpoints.add(townCenter.translate(road.x1(), road.z1()));
            endpoints.add(townCenter.translate(road.x2(), road.z2()));
        }
        for (Point endpoint : endpoints) {
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
            if (score < reusableScore) {
                reusable = new Point(x, z);
                reusableScore = score;
            }
            if (usedGateRoads.contains(endpoint)) continue;
            if (score < bestScore) {
                best = new Point(x, z);
                bestScore = score;
            }
        }
        return best != null ? best : reusable;
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
        // The gate lies in town terrain, so the regional-road painter would fall
        // back to the legacy fixed Y level and excavate a three-wide depression.
        // Repaint the opening from the already loaded town surface instead.
        drawConfiguredRoad(
            level, gate, gate,
            new RoadProfile(Math.max(1, pathWidth), settlement.roadProfile().material()),
            true, true
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
        Set<Long> roadColumns = new HashSet<>();
        for (int step = 0; step <= steps; step++) {
            double factor = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.x() + dx * factor);
            int z = (int) Math.round(start.z() + dz * factor);
            drawRoadDisk(
                level, world, connection, new Point(x, z), radius, roadColumns
            );
        }
    }

    private static void drawRoadDisk(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        Point center,
        double radius
    ) {
        Set<Long> roadColumns = new HashSet<>();
        drawRoadDisk(level, world, connection, center, radius, roadColumns);
    }

    private static void drawRoadDisk(
        ServerLevel level,
        HexWorldPlan world,
        ConnectionPath connection,
        Point center,
        double radius,
        Set<Long> roadColumns
    ) {
        TerrainSample roadSample = new TerrainSample(
            connection.biome(), connection.boundaryProfile(),
            "route", connection.id(), connection.terrainProfile(),
            connection.accessRequirement(), connection.surfaceStyle()
        );
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
                if (sample == null) {
                    continue;
                }
                RoadColumnPlan column = roadColumnPlan(
                    world, connection.id(), x, z
                );
                int groundY = column.groundY();
                clearTreesIntersectingRoad(
                    level,
                    Set.of(blockColumnKey(x, z)),
                    Map.of(blockColumnKey(x, z), groundY)
                );
                clearVegetationColumn(level, x, groundY, z, 32);
                supportRoadColumn(level, x, groundY, z);
                level.setBlock(
                    new BlockPos(x, groundY, z),
                    roadSurfaceBlock(world, roadSample, x, z),
                    2
                );
                roadColumns.add(blockColumnKey(x, z));
                for (int y = groundY + 1; y <= groundY + 4; y++) {
                    level.setBlock(
                        new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2
                    );
                }
            }
        }
    }

    private static void supportRoadColumn(
        ServerLevel level, int x, int roadY, int z
    ) {
        for (int depth = 1; depth <= 12; depth++) {
            BlockPos position = new BlockPos(x, roadY - depth, z);
            BlockState existing = level.getBlockState(position);
            if (!existing.isAir() && !existing.is(Blocks.WATER)) {
                return;
            }
            level.setBlock(position, Blocks.STONE.defaultBlockState(), 2);
        }
    }

    private static BlockState roadSurfaceBlock(
        HexWorldPlan world, TerrainSample sample, int x, int z
    ) {
        int choice = roadSurfaceChoice(world, x, z);
        if (sample.kind().equals("route") && (sample.surfaceStyle().equals("road")
            || (sample.surfaceStyle().equals("log_bridge")
                && !logBridgeOverOceanAt(world, x, z)))) {
            Direction stairDirection = roadColumnPlan(
                world, sample.owner(), x, z
            ).stairDirection();
            if (stairDirection != null) {
                return roadStairBlock(choice, stairDirection);
            }
        }
        return fullRoadSurfaceBlock(choice);
    }

    private static BlockState fullRoadSurfaceBlock(
        HexWorldPlan world, TerrainSample sample, int x, int z
    ) {
        return fullRoadSurfaceBlock(roadSurfaceChoice(world, x, z));
    }

    static BlockState worldRoadSurfaceBlock(HexWorldPlan world, int x, int z) {
        return fullRoadSurfaceBlock(roadSurfaceChoice(world, x, z));
    }

    static int prepareWorldRoadColumn(
        ServerLevel level, HexWorldPlan world, int x, int z
    ) {
        int groundY = nativeTerrainColumn(world, x, z).groundY();
        long columnKey = blockColumnKey(x, z);
        clearTreesIntersectingRoad(
            level, Set.of(columnKey), Map.of(columnKey, groundY)
        );
        clearVegetationColumn(level, x, groundY, z, 32);
        supportRoadColumn(level, x, groundY, z);
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        return groundY;
    }

    private static int roadSurfaceChoice(HexWorldPlan world, int x, int z) {
        long pattern = Double.doubleToLongBits(layeredNoise(
            world.seed(), "world:road-material", x, z, 9.0D
        ));
        return Math.floorMod((int) (pattern ^ pattern >>> 32), 20);
    }

    private static BlockState fullRoadSurfaceBlock(int choice) {
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

    private static BlockState roadStairBlock(int choice, Direction direction) {
        BlockState stair = choice < 7
            ? Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            : choice < 12
                ? Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                : choice < 15
                    ? Blocks.ANDESITE_STAIRS.defaultBlockState()
                    : choice < 18
                        ? Blocks.STONE_STAIRS.defaultBlockState()
                        : Blocks.MOSSY_STONE_BRICK_STAIRS.defaultBlockState();
        return stair.setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
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

    static BuildingRuntimeSystem.SpawnDestination resolveStarterSpawn(
        MinecraftServer server, StarterSpawnSystem.StarterConfig config
    ) {
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(
                "cobbleventure", "generation_" + config.generation()
            )
        );
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            LOGGER.error("Starter generation dimension is missing: {}", dimension.location());
            return null;
        }
        if (config.generation() != 1) {
            return new BuildingRuntimeSystem.SpawnDestination(
                level, level.getSharedSpawnPos(), 0.0F
            );
        }
        SettlementPlan settlement = activeSettlements.get(config.town());
        if (settlement == null) {
            LOGGER.error("Starter settlement is missing: {}", config.town());
            return null;
        }
        if (config.mode().equals("town")) {
            BlockPos safe = safeTeleportPosition(
                level, settlement.playerSpawn().x(), settlement.playerSpawn().z()
            );
            return new BuildingRuntimeSystem.SpawnDestination(
                level, safe == null ? settlement.playerSpawn().toBlockPos().above() : safe,
                0.0F
            );
        }
        FacilityPlacement facility = settlement.facilities().stream()
            .filter(candidate -> candidate.id().equals(config.building()))
            .findFirst().orElse(null);
        if (facility == null) {
            LOGGER.error(
                "Starter building is missing: settlement={}, building={}",
                settlement.id(), config.building()
            );
            return null;
        }
        String eventSpaceId = buildingEventSpaceId(settlement.id(), facility.id());
        BuildingRuntimeSystem.PlacedBuilding existing =
            BuildingRuntimeSystem.resolvePlacedBuilding(
                server, level.dimension(), facility.structure(), eventSpaceId
            );
        String rotation;
        BlockPoint placedOrigin;
        if (existing != null) {
            placedOrigin = existing.origin();
            rotation = existing.rotation() == null || existing.rotation().isBlank()
                ? facilityRuntimeRotation(settlement, facility)
                : existing.rotation();
            LOGGER.debug(
                "Reusing placed starter building: player-independent eventSpace={}, origin={}",
                eventSpaceId, placedOrigin
            );
        } else {
            BlockPoint resolved = facility.mode().equals("instanced_entry")
                ? facility.instanceOrigin()
                : resolveDirectFacilityPosition(level, settlement, facility);
            BlockPoint position = resolved == null ? null
                : applyBuildingPlacementYOffset(facility.structure(), resolved);
            if (position == null) {
                return null;
            }
            rotation = facilityRuntimeRotation(settlement, facility);
            placedOrigin = facility.mode().equals("direct_template")
                ? facilityPlacementOrigin(level, facility, position, rotation) : position;
        }
        BuildingRuntimeSystem.onStructurePlaced(
            level, facility.structure(), placedOrigin, rotation,
            eventSpaceId,
            isDepartmentStoreFacility(facility.id())
                ? settlement.vendorAssignments() : null
        );
        BuildingRuntimeSystem.SpawnDestination destination =
            BuildingRuntimeSystem.resolveStarterSpawn(
                level, facility.structure(), placedOrigin, rotation,
                config.space(), config.mode().equals("slot") ? config.npcSlot() : null
            );
        if (destination == null) {
            LOGGER.error(
                "Starter building destination is missing: settlement={}, building={}, space={}, slot={}",
                settlement.id(), facility.id(), config.space(), config.npcSlot()
            );
        }
        return destination;
    }

    private static void movePlayerToStart(
        ServerPlayer player,
        ServerLevel level,
        BlockPos spawnPos
    ) {
        if (StarterSpawnSystem.movePlayerToDefaultStart(player, level, spawnPos)) {
            player.getPersistentData().putBoolean(PLAYER_STARTED, true);
            player.getPersistentData().remove(PLAYER_WAITING);
        }
    }

    static void markPlayerStarted(ServerPlayer player) {
        player.getPersistentData().putBoolean(PLAYER_STARTED, true);
        player.getPersistentData().remove(PLAYER_WAITING);
    }

    private static void moveWaitingPlayersToStart(ServerLevel level, BlockPos spawnPos) {
        for (ServerPlayer player : List.copyOf(level.players())) {
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

    private static final class TownGenerationDisplay {
        private final ServerLevel level;
        private final ServerBossEvent bossBar = new ServerBossEvent(
            Component.literal("도시 생성 준비 중"),
            BossEvent.BossBarColor.GREEN,
            BossEvent.BossBarOverlay.PROGRESS
        );

        private TownGenerationDisplay(ServerLevel level) {
            this.level = level;
        }

        private void update(WorldInitializationJob job, SettlementPlan settlement) {
            int total = (int) job.runtime.settlements().values().stream()
                .filter(SettlementPlan::enabled)
                .count();
            int completed = (int) job.runtime.settlements().values().stream()
                .filter(SettlementPlan::enabled)
                .filter(candidate -> job.data.isSettlementGenerated(candidate.id()))
                .count();
            double cityProgress = switch (job.phase) {
                case -1 -> job.townChunks.isEmpty()
                    ? 0.0D
                    : job.nextTownChunk / (double) job.townChunks.size() * 0.7D;
                case 0 -> 0.75D;
                case 1 -> 0.85D;
                default -> 0.95D;
            };
            double overallProgress = total == 0
                ? 1.0D
                : Math.min(1.0D, (completed + cityProgress) / total);
            int percent = (int) Math.floor(overallProgress * 100.0D);
            int remaining = Math.max(0, total - completed - 1);
            bossBar.setName(Component.literal(
                "도시 생성 | 현재: " + settlement.displayName()
                    + " | 남은 도시: " + remaining + " | " + percent + "%"
            ));
            bossBar.setProgress((float) overallProgress);
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                bossBar.addPlayer(player);
            }
        }

        private void close() {
            bossBar.removeAllPlayers();
        }

        private void complete() {
            bossBar.setName(Component.literal("모든 도시 생성 완료 | 남은 도시: 0 | 100%"));
            bossBar.setProgress(1.0F);
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                bossBar.addPlayer(player);
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
        private final TownGenerationDisplay display;
        private final boolean initialGeneration;
        private final List<ChunkPos> townChunks = new ArrayList<>();
        private int index;
        private int phase = -1;
        private int nextTownChunk;
        private int lastReportedReadyChunks = -1;
        private long chunkPreparationStartedAt;
        private long townStartedAt;
        private long chunkFinishedAt;
        private long roadElapsedNanos;
        private long facilityElapsedNanos;
        private long previousTownCompletedAt;

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
            this.display = new TownGenerationDisplay(level);
            this.initialGeneration = initialGeneration;
        }
    }

    private static BlockPos surfacePosition(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** Finds safe standing room while ignoring vertical boundary barrier columns. */
    private static BlockPos safeTeleportPosition(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int highestFloor = Math.min(height - 1, level.getMaxBuildHeight() - 3);
        BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();
        for (int y = highestFloor; y >= level.getMinBuildHeight(); y--) {
            floor.set(x, y, z);
            BlockState floorState = level.getBlockState(floor);
            if (floorState.is(Blocks.BARRIER)
                || !supportsTeleport(level, floor, floorState)) {
                continue;
            }
            feet.set(x, y + 1, z);
            head.set(x, y + 2, z);
            if (isOpenForTeleport(level, feet) && isOpenForTeleport(level, head)) {
                return new BlockPos(x, y + 1, z);
            }
        }
        return null;
    }

    private static boolean supportsTeleport(
        ServerLevel level, BlockPos position, BlockState state
    ) {
        return !state.getCollisionShape(level, position).isEmpty()
            || state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isOpenForTeleport(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty()
            && state.getFluidState().isEmpty();
    }

    record Point(int x, int z) {
        Point translate(int deltaX, int deltaZ) {
            return new Point(x + deltaX, z + deltaZ);
        }
    }

    record PlayableEdge(int distance, int groundY, boolean aquatic) {}

    record TownConnector(int x, int z, int direction, int depth) {}

    record TownRoad(int x1, int z1, int x2, int z2) {}

    private record RuntimeAccessRoad(
        TownRoad road, int width, boolean entranceSegment
    ) {}

    record TownSlot(int roadIndex, double ratio, int side) {}

    record TownTemplatePlacement(String structure, BlockPoint position, String rotation) {}

    record FacilityWorkerPlacement(String vendorUnitId, String structure, BlockPoint offset) {}

    record TownPlot(
        double x,
        double z,
        int width,
        int depth,
        String id,
        String structure,
        String rotation,
        int roadConnectionX,
        int roadConnectionZ,
        String entranceFacing
    ) {
        TownPlot(
            double x, double z, int width, int depth, String id,
            String structure, String rotation, int roadConnectionX, int roadConnectionZ
        ) {
            this(x, z, width, depth, id, structure, rotation,
                roadConnectionX, roadConnectionZ, facilityCanonicalEntranceFacing(id));
        }

        TownPlot(double x, double z, int width, int depth, String id) {
            this(x, z, width, depth, id, null, "none", 0, 0,
                facilityCanonicalEntranceFacing(id));
        }
    }

    record TownLayout(
        List<TownRoad> roads,
        List<TownRoad> accessRoads,
        Map<String, List<TownRoad>> buildingAccessRoads,
        Map<String, TownPlot> facilities,
        List<TownPlot> houses,
        List<TownDecoration> decorations,
        List<Point> externalExits
    ) {}

    record TownDecoration(String type, int x, int z, String rotation) {}

    record TownNpcPlacement(
        String npc, String classification, String placementArea,
        String building, int slot
    ) {}

    record TownDecorationTemplate(
        String structure, int counterIndex
    ) {}

    record TownDecorationPlacement(
        BlockPoint origin, int minX, int minZ,
        int width, int depth, int height, String rotation
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

    record SeededNoise(long seed, NormalNoise noise) {}

    record TerrainColumnKey(int worldIdentity, long seed, int x, int z) {
        @Override
        public int hashCode() {
            return CacheKeyHash.spatial(worldIdentity, seed, x, z);
        }
    }

    record TerrainHeightKey(
        int worldIdentity,
        long seed,
        TerrainSample sample,
        long xBits,
        long zBits
    ) {
        @Override
        public int hashCode() {
            return CacheKeyHash.spatial(
                worldIdentity, seed, sample, xBits, zBits
            );
        }
    }

    record TownFootprintCenterKey(HexGrid grid, String settlementId) {}

    record OceanMoundKey(long seed, int cellX, int cellZ) {}

    record RoadColumnPlan(int groundY, Direction stairDirection) {}

    record SafeWaterPosition(ResourceKey<Level> dimension, Vec3 position) {}

    record NativeTerrainColumn(
        int groundY,
        int waterTopY,
        BlockState surface,
        BlockState filler,
        String biome,
        boolean blocked,
        boolean rocky,
        TerrainSample sample
    ) {}

    record LogBridgeDeckPlan(
        int y, BlockState state, boolean support, boolean overOcean
    ) {}

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

    record FacilityMusicZone(
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String context
    ) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY
                && z >= minZ && z < maxZ;
        }
    }

    record RoadProfile(int width, String material) {}

    record SettlementPlan(
        String id,
        String displayName,
        boolean enabled,
        int loadOrder,
        int townRadiusCells,
        String structure,
        String houseStyle,
        boolean disableCommercialOneOff,
        String layoutShape,
        String roadLayoutTemplate,
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
        String shopCatalogId,
        List<String> vendorUnits,
        List<ShopVendorAssignment> vendorAssignments,
        TownLayout compiledLayout,
        List<TownNpcPlacement> automaticNpcPlacements
    ) {}

    record ShopVendorAssignment(String slotId, String vendorUnit) {}

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
        private final Set<String> spawnedTownNpcs = new HashSet<>();
        private final Set<Long> pendingTownDebrisCleanup = new HashSet<>();

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
            String spawnedNpcs = tag.getString("spawnedTownNpcs");
            if (!spawnedNpcs.isBlank()) {
                data.spawnedTownNpcs.addAll(Arrays.asList(spawnedNpcs.split(",")));
            }
            for (long chunkKey : tag.getLongArray("pendingTownDebrisCleanup")) {
                data.pendingTownDebrisCleanup.add(chunkKey);
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

        boolean hasSpawnedTownNpc(String spawnKey) {
            return spawnedTownNpcs.contains(spawnKey);
        }

        void markTownNpcSpawned(String spawnKey) {
            if (spawnedTownNpcs.add(spawnKey)) {
                setDirty();
            }
        }

        boolean isTownDebrisCleanupPending(long chunkKey) {
            return pendingTownDebrisCleanup.contains(chunkKey);
        }

        void markTownDebrisCleanupPending(Set<Long> chunkKeys) {
            if (pendingTownDebrisCleanup.addAll(chunkKeys)) {
                setDirty();
            }
        }

        void markTownDebrisCleanupComplete(long chunkKey) {
            if (pendingTownDebrisCleanup.remove(chunkKey)) {
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
            tag.putString("spawnedTownNpcs", String.join(",", spawnedTownNpcs));
            tag.putLongArray(
                "pendingTownDebrisCleanup",
                pendingTownDebrisCleanup.stream().mapToLong(Long::longValue).toArray()
            );
            return tag;
        }
    }
}
