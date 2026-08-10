package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * JSON-map-backed world generation. Terrain is written while each chunk is in
 * the NOISE stage, so Minecraft can schedule it on its normal worldgen workers
 * and no full-map fillbiome or ServerLevel#setBlock pass is required.
 */
final class NativeWorldGeneration {
    private static final String NAMESPACE = "cobbleventure";
    private static final String TYPE = "hex_map";
    private static final long DEFAULT_SEED = 19_960_227L;
    private static final int MIN_Y = 0;
    private static final int DEPTH = 256;
    private static final int BEDROCK_TOP_Y = 9;
    private static final int SEA_LEVEL = 64;

    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
        DeferredRegister.create(Registries.BIOME_SOURCE, NAMESPACE);
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, NAMESPACE);

    static {
        BIOME_SOURCES.register(TYPE, () -> HexMapBiomeSource.CODEC);
        CHUNK_GENERATORS.register(TYPE, () -> HexMapChunkGenerator.CODEC);
    }

    private NativeWorldGeneration() {}

    static void register(IEventBus modBus) {
        BIOME_SOURCES.register(modBus);
        CHUNK_GENERATORS.register(modBus);
    }

    static boolean usesNativeGenerator(ChunkGenerator generator) {
        return generator instanceof HexMapChunkGenerator;
    }

    static final class HexMapBiomeSource extends BiomeSource {
        static final MapCodec<HexMapBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                Biome.CODEC.listOf().fieldOf("biomes")
                    .forGetter(source -> source.biomes)
            ).apply(instance, instance.stable(HexMapBiomeSource::new))
        );

        private final List<Holder<Biome>> biomes;
        private final Map<String, Holder<Biome>> byId;
        private final Holder<Biome> fallback;
        private final CobbleventureBootstrap.HexWorldPlan world;

        private HexMapBiomeSource(List<Holder<Biome>> biomes) {
            if (biomes.isEmpty()) {
                throw new IllegalArgumentException("hex_map biome list must not be empty");
            }
            this.biomes = List.copyOf(biomes);
            Map<String, Holder<Biome>> indexed = new LinkedHashMap<>();
            for (Holder<Biome> biome : biomes) {
                biome.unwrapKey().ifPresent(key ->
                    indexed.put(key.location().toString(), biome)
                );
            }
            this.byId = Map.copyOf(indexed);
            this.fallback = indexed.getOrDefault(
                "cobbleventure:sealed_dark_forest", biomes.getFirst()
            );
            this.world = WorldMapCache.load(DEFAULT_SEED);
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return biomes.stream();
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            return CODEC;
        }

        @Override
        public Holder<Biome> getNoiseBiome(
            int quartX, int quartY, int quartZ, net.minecraft.world.level.biome.Climate.Sampler sampler
        ) {
            int x = QuartPos.toBlock(quartX);
            int z = QuartPos.toBlock(quartZ);
            CobbleventureBootstrap.TerrainSample sample =
                CobbleventureBootstrap.terrainAt(world, x + 0.5D, z + 0.5D);
            String id = sample == null
                ? CobbleventureBootstrap.emptyTerrainBiome(
                    CobbleventureBootstrap.emptyTerrainAt(world, x + 0.5D, z + 0.5D)
                )
                : sample.biome();
            return byId.getOrDefault(id, fallback);
        }
    }

    static final class HexMapChunkGenerator extends ChunkGenerator {
        static final MapCodec<HexMapChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                BiomeSource.CODEC.fieldOf("biome_source")
                    .forGetter(generator -> generator.biomeSource),
                Codec.LONG.optionalFieldOf("seed", DEFAULT_SEED)
                    .forGetter(generator -> generator.seed)
            ).apply(instance, instance.stable(HexMapChunkGenerator::new))
        );

        private final long seed;
        private final CobbleventureBootstrap.HexWorldPlan world;

        private HexMapChunkGenerator(BiomeSource biomeSource, long seed) {
            super(biomeSource);
            this.seed = seed;
            this.world = WorldMapCache.load(seed);
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            return CODEC;
        }

        @Override
        public ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> structures, RandomState randomState, long levelSeed
        ) {
            return ChunkGeneratorStructureState.createForNormal(
                randomState, levelSeed, biomeSource, structures
            );
        }

        @Override
        public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk
        ) {
            return CompletableFuture.supplyAsync(
                Util.wrapThreadWithTaskName("cobbleventure_hex_terrain", () -> {
                    fillChunk(chunk);
                    return chunk;
                }),
                Util.backgroundExecutor()
            );
        }

        private void fillChunk(ChunkAccess chunk) {
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(
                Heightmap.Types.OCEAN_FLOOR_WG
            );
            Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(
                Heightmap.Types.WORLD_SURFACE_WG
            );
            int startX = chunk.getPos().getMinBlockX();
            int startZ = chunk.getPos().getMinBlockZ();
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int z = startZ + localZ;
                    CobbleventureBootstrap.NativeTerrainColumn column =
                        CobbleventureBootstrap.nativeTerrainColumn(world, x, z);
                    writeColumn(
                        chunk, position, oceanFloor, worldSurface,
                        localX, localZ, x, z, column
                    );
                }
            }
        }

        private void writeColumn(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos position,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            int localX,
            int localZ,
            int worldX,
            int worldZ,
            CobbleventureBootstrap.NativeTerrainColumn column
        ) {
            int groundY = Math.min(MIN_Y + DEPTH - 1, column.groundY());
            for (int y = MIN_Y; y <= groundY; y++) {
                BlockState state = stateAt(column, y);
                setBlock(chunk, position, oceanFloor, worldSurface, localX, y, localZ, state);
            }
            for (int y = groundY + 1; y <= column.waterTopY(); y++) {
                setBlock(
                    chunk, position, oceanFloor, worldSurface,
                    localX, y, localZ, Blocks.WATER.defaultBlockState()
                );
            }
            if (column.blocked() && isBoundaryColumn(worldX, worldZ)) {
                int barrierStart = Math.max(groundY, column.waterTopY()) + 1;
                for (int y = barrierStart; y < MIN_Y + DEPTH; y++) {
                    setBlock(
                        chunk, position, oceanFloor, worldSurface,
                        localX, y, localZ, Blocks.BARRIER.defaultBlockState()
                    );
                }
            }
        }

        private boolean isBoundaryColumn(int x, int z) {
            if (CobbleventureBootstrap.terrainAt(world, x + 0.5D, z + 0.5D) != null) {
                return false;
            }
            return CobbleventureBootstrap.terrainAt(world, x - 0.5D, z + 0.5D) != null
                || CobbleventureBootstrap.terrainAt(world, x + 1.5D, z + 0.5D) != null
                || CobbleventureBootstrap.terrainAt(world, x + 0.5D, z - 0.5D) != null
                || CobbleventureBootstrap.terrainAt(world, x + 0.5D, z + 1.5D) != null;
        }

        private static BlockState stateAt(
            CobbleventureBootstrap.NativeTerrainColumn column, int y
        ) {
            if (y <= BEDROCK_TOP_Y) {
                return Blocks.BEDROCK.defaultBlockState();
            }
            if (y == column.groundY()) {
                return column.surface();
            }
            if (y >= column.groundY() - 3) {
                return column.filler();
            }
            return Blocks.STONE.defaultBlockState();
        }

        private static void setBlock(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos position,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            int x,
            int y,
            int z,
            BlockState state
        ) {
            chunk.setBlockState(position.set(x, y, z), state, false);
            oceanFloor.update(x, y, z, state);
            worldSurface.update(x, y, z, state);
        }

        @Override
        public int getBaseHeight(
            int x, int z, Heightmap.Types type,
            LevelHeightAccessor heightAccessor, RandomState randomState
        ) {
            CobbleventureBootstrap.NativeTerrainColumn column =
                CobbleventureBootstrap.nativeTerrainColumn(world, x, z);
            int top = Math.max(column.groundY(), column.waterTopY());
            return Math.min(heightAccessor.getMaxBuildHeight(), top + 1);
        }

        @Override
        public NoiseColumn getBaseColumn(
            int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState
        ) {
            BlockState[] states = new BlockState[heightAccessor.getHeight()];
            CobbleventureBootstrap.NativeTerrainColumn column =
                CobbleventureBootstrap.nativeTerrainColumn(world, x, z);
            for (int index = 0; index < states.length; index++) {
                int y = heightAccessor.getMinBuildHeight() + index;
                if (y <= column.groundY()) {
                    states[index] = stateAt(column, y);
                } else if (y <= column.waterTopY()) {
                    states[index] = Blocks.WATER.defaultBlockState();
                } else {
                    states[index] = Blocks.AIR.defaultBlockState();
                }
            }
            return new NoiseColumn(heightAccessor.getMinBuildHeight(), states);
        }

        @Override
        public void buildSurface(
            WorldGenRegion region, StructureManager structures,
            RandomState randomState, ChunkAccess chunk
        ) {}

        @Override
        public void applyCarvers(
            WorldGenRegion region, long seed, RandomState randomState,
            BiomeManager biomeManager, StructureManager structures,
            ChunkAccess chunk, GenerationStep.Carving carving
        ) {}

        @Override
        public void spawnOriginalMobs(WorldGenRegion region) {}

        @Override
        public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
            return 69;
        }

        @Override
        public int getMinY() {
            return MIN_Y;
        }

        @Override
        public int getGenDepth() {
            return DEPTH;
        }

        @Override
        public int getSeaLevel() {
            return SEA_LEVEL;
        }

        @Override
        public void addDebugScreenInfo(
            List<String> lines, RandomState randomState, BlockPos position
        ) {
            lines.add("Cobbleventure JSON hex map");
        }
    }

    private static final class WorldMapCache {
        private static final Map<Long, CobbleventureBootstrap.HexWorldPlan> WORLDS =
            new ConcurrentHashMap<>();

        private WorldMapCache() {}

        static CobbleventureBootstrap.HexWorldPlan load(long seed) {
            return WORLDS.computeIfAbsent(seed, WorldMapCache::read);
        }

        private static CobbleventureBootstrap.HexWorldPlan read(long seed) {
            JsonObject world = readJson("hex_worlds/generation_1.json");
            JsonObject boundaryProfiles = readJson("catalogs/boundary-profiles.json");
            Map<String, Integer> townRadii = new LinkedHashMap<>();
            for (JsonElement element : world.getAsJsonArray("settlements")) {
                String settlementId = element.getAsJsonObject()
                    .get("settlement").getAsString();
                String slug = settlementId.substring(settlementId.lastIndexOf('/') + 1);
                JsonObject settlement = readJson(
                    "settlements/generation_1/" + slug + ".json"
                );
                townRadii.put(settlementId, settlement.get("town_radius_cells").getAsInt());
            }
            return CobbleventureBootstrap.parseHexWorldPlan(
                world,
                Map.copyOf(townRadii),
                CobbleventureBootstrap.parseBoundaryProfiles(boundaryProfiles),
                seed
            );
        }

        private static JsonObject readJson(String path) {
            String resourcePath = "/data/cobbleventure/" + path;
            try (InputStream stream = NativeWorldGeneration.class
                    .getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    throw new IllegalStateException(
                        "Missing native world generation resource: " + resourcePath
                    );
                }
                try (Reader reader = new InputStreamReader(
                    stream, StandardCharsets.UTF_8
                )) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                }
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException(
                    "Invalid native world generation resource: " + resourcePath,
                    error
                );
            }
        }
    }
}
