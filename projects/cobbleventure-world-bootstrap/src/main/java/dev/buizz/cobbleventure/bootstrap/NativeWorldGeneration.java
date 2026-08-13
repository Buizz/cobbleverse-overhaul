package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.TerrainSample;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
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
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
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

    private static BiomeGenerationSettings surfaceGenerationSettings(
        Holder<Biome> biome
    ) {
        List<HolderSet<PlacedFeature>> source = biome.value()
            .getGenerationSettings().features();
        List<HolderSet<PlacedFeature>> filtered = new ArrayList<>(source.size());
        int vegetation = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        int topLayer = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();
        for (int index = 0; index < source.size(); index++) {
            filtered.add(index == vegetation || index == topLayer
                ? source.get(index) : HolderSet.empty());
        }
        return new BiomeGenerationSettings(Map.of(), List.copyOf(filtered));
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
        private final HexWorldPlan world;
        private final Map<Long, Holder<Biome>> surfaceBiomeCache = new ConcurrentHashMap<>();

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
            this.world = WorldPlanRepository.load(DEFAULT_SEED);
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
            long cacheKey = ((long) quartX << 32) ^ (quartZ & 0xffffffffL);
            return surfaceBiomeCache.computeIfAbsent(
                cacheKey, ignored -> surfaceBiomeAt(quartX, quartZ)
            );
        }

        private Holder<Biome> surfaceBiomeAt(int quartX, int quartZ) {
            int x = QuartPos.toBlock(quartX);
            int z = QuartPos.toBlock(quartZ);
            TerrainSample sample =
                CobbleventureBootstrap.terrainAt(world, x + 0.5D, z + 0.5D);
            String id = sample == null
                ? CobbleventureBootstrap.emptyTerrainBiome(world, x + 0.5D, z + 0.5D)
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
        private final HexWorldPlan world;

        private HexMapChunkGenerator(BiomeSource biomeSource, long seed) {
            super(biomeSource, NativeWorldGeneration::surfaceGenerationSettings);
            this.seed = seed;
            this.world = WorldPlanRepository.load(seed);
        }

        @Override
        protected MapCodec<? extends ChunkGenerator> codec() {
            return CODEC;
        }

        @Override
        public ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> structures, RandomState randomState, long levelSeed
        ) {
            // Settlements and facilities in this dimension are placed only from
            // the authored Cobbleventure map. Passing the global registry here
            // would also schedule vanilla, BCA and RGS structure sets anywhere
            // their biome predicates happen to match.
            return ChunkGeneratorStructureState.createForFlat(
                randomState, levelSeed, biomeSource, Stream.empty()
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
            CobbleventureBootstrap.NativeTerrainColumn[][] columns =
                new CobbleventureBootstrap.NativeTerrainColumn[16][16];
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int z = startZ + localZ;
                    CobbleventureBootstrap.NativeTerrainColumn column =
                        CobbleventureBootstrap.nativeTerrainColumn(world, x, z);
                    columns[localX][localZ] = column;
                    writeColumn(
                        chunk, position, oceanFloor, worldSurface,
                        localX, localZ, x, z, column
                    );
                }
            }
            for (int localX = 0; localX < 16; localX++) {
                int x = startX + localX;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int z = startZ + localZ;
                    applyTownGroundCover(
                        chunk, position, oceanFloor, worldSurface,
                        localX, localZ, x, z, columns[localX][localZ]
                    );
                    applyEmptyTerrainGroundCover(
                        chunk, position, oceanFloor, worldSurface,
                        localX, localZ, x, z, columns[localX][localZ]
                    );
                }
            }
            placeEmptyTerrainFeatures(
                chunk, position, oceanFloor, worldSurface, startX, startZ
            );
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
                BlockState state = stateAt(column, worldX, y, worldZ);
                setBlock(chunk, position, oceanFloor, worldSurface, localX, y, localZ, state);
            }
            for (int y = groundY + 1; y <= column.waterTopY(); y++) {
                setBlock(
                    chunk, position, oceanFloor, worldSurface,
                    localX, y, localZ, Blocks.WATER.defaultBlockState()
                );
            }
            if (column.blocked() && isBoundaryColumn(worldX, worldZ)) {
                int barrierStart = groundY + 1;
                for (int y = barrierStart; y < MIN_Y + DEPTH; y++) {
                    setBlock(
                        chunk, position, oceanFloor, worldSurface,
                        localX, y, localZ, Blocks.BARRIER.defaultBlockState()
                    );
                }
            }
        }

        private boolean isBoundaryColumn(int x, int z) {
            if (CobbleventureBootstrap.isCaveEntrancePassage(world, x, z)) {
                return false;
            }
            return CobbleventureBootstrap.isHiddenBoundaryCollisionColumn(world, x, z);
        }

        private BlockState stateAt(
            CobbleventureBootstrap.NativeTerrainColumn column,
            int x, int y, int z
        ) {
            if (y <= BEDROCK_TOP_Y) {
                return Blocks.BEDROCK.defaultBlockState();
            }
            if (column.rocky()) {
                if (y < CobbleventureBootstrap.WATER_SURFACE_Y) {
                    return column.blocked()
                        ? Blocks.BARRIER.defaultBlockState()
                        : CobbleventureBootstrap.oceanCliffRock(world, x, y, z);
                }
                return y == column.groundY()
                    ? CobbleventureBootstrap.oceanBoundaryRock(world, x, y, z)
                    : CobbleventureBootstrap.oceanCliffRock(world, x, y, z);
            }
            if (y == column.groundY()) {
                return column.surface();
            }
            if (y >= column.groundY() - 3) {
                return column.filler();
            }
            return Blocks.STONE.defaultBlockState();
        }

        private void applyTownGroundCover(
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
            TerrainSample sample = column.sample();
            if (sample == null || !sample.kind().equals("town")
                || sample.surfaceStyle().equals("road")
                || sample.surfaceStyle().equals("water")) {
                return;
            }
            long value = coordinateHash(worldX, worldZ, 0x544F574E47524F57L);
            int chance = Math.floorMod((int) value, 100);
            BlockState decoration;
            if (chance < 2) {
                decoration = Blocks.DANDELION.defaultBlockState();
            } else if (chance < 4) {
                decoration = Blocks.POPPY.defaultBlockState();
            } else if (chance < 24) {
                decoration = Blocks.SHORT_GRASS.defaultBlockState();
            } else {
                return;
            }
            if (column.waterTopY() > column.groundY()) {
                return;
            }
            setBlock(
                chunk, position, oceanFloor, worldSurface,
                localX, column.groundY() + 1, localZ, decoration
            );
        }

        private void applyEmptyTerrainGroundCover(
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
            if (column.sample() != null || isBoundaryColumn(worldX, worldZ)) {
                return;
            }
            String type = CobbleventureBootstrap.emptyTerrainAt(
                world, worldX + 0.5D, worldZ + 0.5D
            );
            int chance = Math.floorMod(
                (int) coordinateHash(worldX, worldZ, 0x454D50545947524FL), 100
            );
            BlockState decoration = null;
            if (type.equals("ocean") && !column.rocky()) {
                if (chance < 22) decoration = Blocks.SEAGRASS.defaultBlockState();
            } else if (type.equals("high_forest") && !column.rocky()) {
                if (chance < 18) decoration = Blocks.FERN.defaultBlockState();
                else if (chance < 36) decoration = Blocks.SHORT_GRASS.defaultBlockState();
                else if (chance < 39) decoration = Blocks.BROWN_MUSHROOM.defaultBlockState();
                if (chance >= 68 && chance < 82) {
                    setBlock(
                        chunk, position, oceanFloor, worldSurface,
                        localX, column.groundY(), localZ,
                        chance % 2 == 0
                            ? Blocks.PODZOL.defaultBlockState()
                            : Blocks.MOSS_BLOCK.defaultBlockState()
                    );
                }
            } else if (type.equals("desert") && chance < 5) {
                decoration = Blocks.DEAD_BUSH.defaultBlockState();
            } else if (type.equals("snow_mountain") && chance < 8) {
                decoration = Blocks.SHORT_GRASS.defaultBlockState();
            }
            if (decoration != null && column.waterTopY() <= column.groundY()) {
                setBlock(
                    chunk, position, oceanFloor, worldSurface,
                    localX, column.groundY() + 1, localZ, decoration
                );
            }
        }

        private void placeEmptyTerrainFeatures(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos position,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            int startX,
            int startZ
        ) {
            for (int anchorX = startX - 3; anchorX <= startX + 18; anchorX++) {
                for (int anchorZ = startZ - 3; anchorZ <= startZ + 18; anchorZ++) {
                    boolean snowCandidate = Math.floorMod(anchorX, 12) == 0
                        && Math.floorMod(anchorZ, 12) == 0;
                    boolean desertCandidate = Math.floorMod(anchorX, 13) == 0
                        && Math.floorMod(anchorZ, 13) == 0;
                    if (!snowCandidate && !desertCandidate) {
                        continue;
                    }
                    if (CobbleventureBootstrap.terrainAt(
                        world, anchorX + 0.5D, anchorZ + 0.5D
                    ) != null || isBoundaryColumn(anchorX, anchorZ)) {
                        continue;
                    }
                    String type = CobbleventureBootstrap.emptyTerrainAt(
                        world, anchorX + 0.5D, anchorZ + 0.5D
                    );
                    if (type.equals("snow_mountain") && snowCandidate) {
                        if (Math.floorMod((int) coordinateHash(
                                anchorX, anchorZ, 0x534E4F5753505255L
                            ), 100) < 62) {
                            CobbleventureBootstrap.NativeTerrainColumn column =
                                CobbleventureBootstrap.nativeTerrainColumn(
                                    world, anchorX, anchorZ
                                );
                            if (column.waterTopY() > column.groundY()) continue;
                            int height = 7 + Math.floorMod(
                                (int) coordinateHash(
                                    anchorX, anchorZ, 0x534E4F5748454947L
                                ), 5
                            );
                            placeSyntheticTree(
                                chunk, position, oceanFloor, worldSurface,
                                startX, startZ, anchorX, anchorZ,
                                column.groundY(), height,
                                Blocks.SPRUCE_LOG.defaultBlockState(),
                                Blocks.SPRUCE_LEAVES.defaultBlockState(), 2
                            );
                        }
                    } else if (type.equals("desert") && desertCandidate) {
                        if (Math.floorMod((int) coordinateHash(
                                anchorX, anchorZ, 0x4445534552544341L
                            ), 100) < 45) {
                            CobbleventureBootstrap.NativeTerrainColumn column =
                                CobbleventureBootstrap.nativeTerrainColumn(
                                    world, anchorX, anchorZ
                                );
                            if (column.waterTopY() > column.groundY()) continue;
                            int height = 2 + Math.floorMod(
                                (int) coordinateHash(
                                    anchorX, anchorZ, 0x4341435455534845L
                                ), 3
                            );
                            for (int y = 1; y <= height; y++) {
                                writeTreeBlock(
                                    chunk, position, oceanFloor, worldSurface,
                                    startX, startZ, anchorX,
                                    column.groundY() + y, anchorZ,
                                    Blocks.CACTUS.defaultBlockState()
                                );
                            }
                        }
                    }
                }
            }
        }

        private void placeSyntheticTree(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos position,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            int startX,
            int startZ,
            int anchorX,
            int anchorZ,
            int groundY,
            int height,
            BlockState log,
            BlockState leaves,
            int canopyRadius
        ) {
            if (leaves.hasProperty(LeavesBlock.PERSISTENT)) {
                leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
            }
            for (int y = 1; y <= height; y++) {
                writeTreeBlock(
                    chunk, position, oceanFloor, worldSurface,
                    startX, startZ, anchorX, groundY + y, anchorZ, log
                );
            }
            int canopyY = groundY + height;
            for (int dx = -canopyRadius; dx <= canopyRadius; dx++) {
                for (int dz = -canopyRadius; dz <= canopyRadius; dz++) {
                    for (int dy = -2; dy <= 1; dy++) {
                        int radius = dy == 1 ? 1 : dy == -2
                            ? Math.max(1, canopyRadius - 1) : canopyRadius;
                        if (Math.abs(dx) + Math.abs(dz) > radius + 1
                            || (Math.abs(dx) == radius && Math.abs(dz) == radius)) {
                            continue;
                        }
                        writeTreeBlock(
                            chunk, position, oceanFloor, worldSurface,
                            startX, startZ,
                            anchorX + dx, canopyY + dy, anchorZ + dz, leaves
                        );
                    }
                }
            }
        }

        private static void writeTreeBlock(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos position,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            int startX,
            int startZ,
            int worldX,
            int y,
            int worldZ,
            BlockState state
        ) {
            int localX = worldX - startX;
            int localZ = worldZ - startZ;
            if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
                return;
            }
            if (chunk.getBlockState(position.set(localX, y, localZ)).is(Blocks.BARRIER)) {
                return;
            }
            setBlock(
                chunk, position, oceanFloor, worldSurface,
                localX, y, localZ, state
            );
        }

        private long coordinateHash(int x, int z, long salt) {
            long value = seed ^ salt;
            value ^= (long) x * 0x9E3779B97F4A7C15L;
            value ^= (long) z * 0xC2B2AE3D27D4EB4FL;
            value ^= value >>> 30;
            value *= 0xBF58476D1CE4E5B9L;
            value ^= value >>> 27;
            value *= 0x94D049BB133111EBL;
            return value ^ value >>> 31;
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
                    states[index] = stateAt(column, x, y, z);
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

}
