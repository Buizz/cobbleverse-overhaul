package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.slf4j.Logger;

/** Builds authored two-dimensional forests inside the dedicated forest dimension. */
final class ForestDimensionGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UPDATE_FLAGS = 2;
    private static final int LAYOUT_VERSION = 6;
    private static final int MARKER_Y = 210;

    private ForestDimensionGenerator() {}

    static void generate(ServerLevel level, long worldSeed, Map<String, JsonObject> forests) {
        for (Map.Entry<String, JsonObject> entry : forests.entrySet()) {
            generateForest(level, worldSeed ^ entry.getKey().hashCode(), entry.getKey(), entry.getValue());
        }
    }

    private static void generateForest(
        ServerLevel level, long seed, String forestId, JsonObject forest
    ) {
        JsonObject dimension = forest.getAsJsonObject("dimension");
        JsonObject origin = dimension.getAsJsonObject("origin");
        JsonObject bounds = dimension.getAsJsonObject("bounds");
        int originX = origin.get("x").getAsInt();
        int originY = origin.get("y").getAsInt();
        int originZ = origin.get("z").getAsInt();
        int minX = bounds.get("min_x").getAsInt();
        int minZ = bounds.get("min_z").getAsInt();
        int maxX = bounds.get("max_x").getAsInt();
        int maxZ = bounds.get("max_z").getAsInt();
        int signature = forest.toString().hashCode() * 31 + LAYOUT_VERSION;
        BlockPos signatureMarker = new BlockPos(originX + minX, MARKER_Y, originZ + minZ);
        applyForestBiome(level, originX + minX, originZ + minZ, originX + maxX, originZ + maxZ);
        if (matchesSignature(level, signatureMarker, signature)) {
            LOGGER.info("Authored forest is current: forest={}, dimension={}", forestId, level.dimension().location());
            return;
        }

        JsonObject generator = forest.getAsJsonObject("generator");
        int cellSize = generator.get("cell_size").getAsInt();
        JsonObject barrier = forest.getAsJsonObject("tree_barrier");
        int minimumTreeHeight = barrier.get("min_height").getAsInt();
        int maximumTreeHeight = barrier.get("max_height").getAsInt();
        BlockState trunk = firstBlock(barrier.getAsJsonArray("trunk_blocks"), Blocks.SPRUCE_LOG.defaultBlockState());
        BlockState leaves = persistentLeaves(firstBlock(
            barrier.getAsJsonArray("foliage_blocks"), Blocks.SPRUCE_LEAVES.defaultBlockState()
        ));
        BlockState collision = block(
            barrier.get("barrier_block").getAsString(), Blocks.BARRIER.defaultBlockState()
        );
        JsonObject undergrowth = forest.getAsJsonObject("undergrowth");
        double undergrowthDensity = undergrowth.get("density").getAsDouble();
        int pathClearance = undergrowth.get("path_clearance").getAsInt();
        List<BlockState> undergrowthBlocks = usableUndergrowth(undergrowth.getAsJsonArray("blocks"));
        PathNetwork paths = PathNetwork.parse(
            forest.getAsJsonArray("paths"), forest.getAsJsonArray("entrances"),
            originX, originZ, pathClearance
        );
        TerrainTiles terrain = TerrainTiles.parse(
            forest.getAsJsonArray("terrain_tiles"), bounds, cellSize
        );
        int baseSurfaceY = originY - 1;
        int clearTop = originY + maximumTreeHeight + 6;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int localX = minX; localX < maxX; localX++) {
            for (int localZ = minZ; localZ < maxZ; localZ++) {
                int worldX = originX + localX;
                int worldZ = originZ + localZ;
                int surfaceY = baseSurfaceY + terrain.heightAt(localX, localZ);
                PathSample path = paths.sample(worldX + 0.5D, worldZ + 0.5D);
                for (int y = surfaceY + 1; y <= clearTop; y++) {
                    BlockPos position = cursor.set(worldX, y, worldZ);
                    if (!path.walkable() || !level.getBlockState(position).is(BlockTags.LEAVES)) {
                        level.setBlock(position, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS);
                    }
                }
                if (surfaceY < baseSurfaceY) {
                    for (int y = surfaceY + 1; y <= baseSurfaceY; y++) {
                        level.setBlock(cursor.set(worldX, y, worldZ), Blocks.AIR.defaultBlockState(), UPDATE_FLAGS);
                    }
                } else {
                    for (int y = baseSurfaceY; y < surfaceY; y++) {
                        level.setBlock(cursor.set(worldX, y, worldZ), Blocks.DIRT.defaultBlockState(), UPDATE_FLAGS);
                    }
                }
                BlockState surface = path.walkable()
                    ? naturalPathSurface(seed, worldX, worldZ, path.surface())
                    : forestFloor(seed, worldX, worldZ);
                level.setBlock(cursor.set(worldX, surfaceY, worldZ), surface, UPDATE_FLAGS);
            }
        }

        int treeSpacing = maximumTreeHeight >= 18 ? 5 : 6;
        for (int localX = minX; localX < maxX; localX++) {
            for (int localZ = minZ; localZ < maxZ; localZ++) {
                int worldX = originX + localX;
                int worldZ = originZ + localZ;
                if (paths.sample(worldX + 0.5D, worldZ + 0.5D).walkable()) {
                    continue;
                }
                int surfaceY = baseSurfaceY + terrain.heightAt(localX, localZ);
                long noise = mix(seed, worldX, worldZ);
                boolean tree = Math.floorMod(localX + (int) (noise & 3L), treeSpacing) == 0
                    && Math.floorMod(localZ + (int) ((noise >>> 3) & 3L), treeSpacing) == 0;
                if (tree) {
                    int heightRange = Math.max(1, maximumTreeHeight - minimumTreeHeight + 1);
                    int height = minimumTreeHeight + Math.floorMod((int) (noise >>> 8), heightRange);
                    placeTree(level, cursor, paths, terrain, originX, originZ, baseSurfaceY,
                        worldX, surfaceY, worldZ, height, trunk, leaves);
                    continue;
                }
                if (!undergrowthBlocks.isEmpty() && unit(noise >>> 16) < undergrowthDensity) {
                    BlockState plant = undergrowthBlocks.get(
                        Math.floorMod((int) (noise >>> 32), undergrowthBlocks.size())
                    );
                    level.setBlock(cursor.set(worldX, surfaceY + 1, worldZ), plant, UPDATE_FLAGS);
                }
                level.setBlock(cursor.set(worldX, surfaceY + 2, worldZ), collision, UPDATE_FLAGS);
            }
        }
        LOGGER.info(
            "Authored forest generated: forest={}, dimension={}, bounds={}..{},{}..{}",
            forestId, level.dimension().location(), minX, maxX, minZ, maxZ
        );
        writeSignature(level, signatureMarker, signature);
    }

    private static void applyForestBiome(
        ServerLevel level, int minX, int minZ, int maxX, int maxZ
    ) {
        Holder<Biome> darkForest = level.registryAccess()
            .registryOrThrow(Registries.BIOME)
            .getHolderOrThrow(Biomes.DARK_FOREST);
        var sampler = level.getChunkSource().randomState().sampler();
        for (int chunkX = minX >> 4; chunkX <= (maxX - 1) >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= (maxZ - 1) >> 4; chunkZ++) {
                var chunk = level.getChunk(chunkX, chunkZ);
                chunk.fillBiomesFromNoise(
                    (quartX, quartY, quartZ, ignored) -> darkForest, sampler
                );
                chunk.setUnsaved(true);
            }
        }
    }

    private static boolean matchesSignature(ServerLevel level, BlockPos marker, int signature) {
        if (!level.getBlockState(marker).is(Blocks.LODESTONE)) return false;
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            boolean expected = (signature & (1 << bit)) != 0;
            BlockState state = level.getBlockState(marker.above(bit + 1));
            if (expected != state.is(Blocks.REINFORCED_DEEPSLATE)) return false;
            if (!expected && !state.is(Blocks.BEDROCK)) return false;
        }
        return true;
    }

    private static void writeSignature(ServerLevel level, BlockPos marker, int signature) {
        level.setBlock(marker, Blocks.LODESTONE.defaultBlockState(), UPDATE_FLAGS);
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            BlockState state = (signature & (1 << bit)) != 0
                ? Blocks.REINFORCED_DEEPSLATE.defaultBlockState()
                : Blocks.BEDROCK.defaultBlockState();
            level.setBlock(marker.above(bit + 1), state, UPDATE_FLAGS);
        }
    }

    private static void placeTree(
        ServerLevel level, BlockPos.MutableBlockPos cursor, PathNetwork paths,
        TerrainTiles terrain, int originX, int originZ, int baseSurfaceY,
        int x, int surfaceY, int z, int height, BlockState trunk, BlockState leaves
    ) {
        for (int y = 1; y <= height; y++) {
            level.setBlock(cursor.set(x, surfaceY + y, z), trunk, UPDATE_FLAGS);
        }
        int crownBase = surfaceY + Math.max(3, height - 6);
        int crownTop = surfaceY + height + 2;
        for (int y = crownBase; y <= crownTop; y++) {
            int distanceFromTop = crownTop - y;
            int radius = distanceFromTop <= 1 ? 1 : distanceFromTop <= 4 ? 2 : 3;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius + 1 || (dx == 0 && dz == 0 && y <= surfaceY + height)) {
                        continue;
                    }
                    int localX = x + dx - originX;
                    int localZ = z + dz - originZ;
                    int neighboringSurface = baseSurfaceY + terrain.heightAt(localX, localZ);
                    if (y <= neighboringSurface) {
                        continue;
                    }
                    level.setBlock(cursor.set(x + dx, y, z + dz), leaves, UPDATE_FLAGS);
                }
            }
        }
    }

    private static BlockState firstBlock(JsonArray values, BlockState fallback) {
        if (values == null || values.isEmpty()) return fallback;
        return block(values.get(0).getAsString(), fallback);
    }

    private static BlockState block(String id, BlockState fallback) {
        ResourceLocation resource = ResourceLocation.tryParse(id);
        if (resource == null || !BuiltInRegistries.BLOCK.containsKey(resource)) return fallback;
        return BuiltInRegistries.BLOCK.get(resource).defaultBlockState();
    }

    private static BlockState persistentLeaves(BlockState state) {
        return state.hasProperty(BlockStateProperties.PERSISTENT)
            ? state.setValue(BlockStateProperties.PERSISTENT, true) : state;
    }

    private static List<BlockState> usableUndergrowth(JsonArray values) {
        List<BlockState> result = new ArrayList<>();
        if (values == null) return result;
        for (JsonElement value : values) {
            String id = value.getAsString();
            if (!id.equals("minecraft:tall_grass") && !id.equals("minecraft:large_fern")) {
                result.add(block(id, Blocks.SHORT_GRASS.defaultBlockState()));
            }
        }
        return List.copyOf(result);
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static double unit(long value) {
        return (value & 0xFFFFL) / 65535.0D;
    }

    private static BlockState forestFloor(long seed, int x, int z) {
        int roll = Math.floorMod((int) (mix(seed ^ 0x5F3759DFL, x, z) >>> 24), 100);
        if (roll < 68) return Blocks.PODZOL.defaultBlockState();
        if (roll < 86) return Blocks.COARSE_DIRT.defaultBlockState();
        if (roll < 95) return Blocks.ROOTED_DIRT.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static BlockState naturalPathSurface(
        long seed, int x, int z, BlockState configured
    ) {
        if (!configured.is(Blocks.DIRT_PATH)) return configured;
        // Two-by-two noise cells form soft patches instead of a checkerboard.
        int patchX = Math.floorDiv(x, 2);
        int patchZ = Math.floorDiv(z, 2);
        int roll = Math.floorMod(
            (int) (mix(seed ^ 0x6A09E667F3BCC909L, patchX, patchZ) >>> 20), 100
        );
        if (roll < 58) return configured;
        if (roll < 76) return Blocks.COARSE_DIRT.defaultBlockState();
        if (roll < 89) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (roll < 97) return Blocks.PODZOL.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    private record TerrainTiles(
        Map<Long, Integer> offsets, int minCenterX, int minCenterZ, int cellSize
    ) {
        static TerrainTiles parse(JsonArray values, JsonObject bounds, int cellSize) {
            Map<Long, Integer> offsets = new HashMap<>();
            for (JsonElement value : values) {
                JsonObject tile = value.getAsJsonObject();
                offsets.put(key(tile.get("x").getAsInt(), tile.get("z").getAsInt()),
                    tile.get("height_offset").getAsInt());
            }
            int centerOffset = (int) Math.ceil(cellSize / 2.0D);
            return new TerrainTiles(
                Map.copyOf(offsets), bounds.get("min_x").getAsInt() + centerOffset,
                bounds.get("min_z").getAsInt() + centerOffset, cellSize
            );
        }

        int heightAt(int x, int z) {
            int centerX = minCenterX + Math.floorDiv(x - minCenterX + cellSize / 2, cellSize) * cellSize;
            int centerZ = minCenterZ + Math.floorDiv(z - minCenterZ + cellSize / 2, cellSize) * cellSize;
            return offsets.getOrDefault(key(centerX, centerZ), 0);
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }
    }

    private record PathNetwork(List<Route> routes) {
        static PathNetwork parse(
            JsonArray values, JsonArray entrances,
            int originX, int originZ, int clearance
        ) {
            List<Route> routes = new ArrayList<>();
            for (JsonElement value : values) {
                JsonObject route = value.getAsJsonObject();
                List<Point> controls = new ArrayList<>();
                for (JsonElement pointValue : route.getAsJsonArray("points")) {
                    JsonObject point = pointValue.getAsJsonObject();
                    controls.add(new Point(
                        originX + point.get("x").getAsDouble(),
                        originZ + point.get("z").getAsDouble()
                    ));
                }
                JsonObject spline = route.getAsJsonObject("spline");
                boolean splineEnabled = spline != null && spline.get("enabled").getAsBoolean();
                double tension = spline != null && spline.has("tension")
                    ? spline.get("tension").getAsDouble() : 0.5D;
                List<Point> samples = splineEnabled ? spline(controls, tension) : controls;
                List<Segment> segments = new ArrayList<>();
                for (int index = 0; index + 1 < samples.size(); index++) {
                    segments.add(new Segment(samples.get(index), samples.get(index + 1)));
                }
                routes.add(new Route(
                    List.copyOf(segments), route.get("width").getAsDouble() / 2.0D + clearance,
                    block(route.get("surface").getAsString(), Blocks.DIRT_PATH.defaultBlockState())
                ));
            }
            addEntranceApproaches(routes, entrances, originX, originZ);
            return new PathNetwork(List.copyOf(routes));
        }

        private static void addEntranceApproaches(
            List<Route> routes, JsonArray entrances, int originX, int originZ
        ) {
            if (entrances == null || routes.isEmpty()) return;
            for (JsonElement value : entrances) {
                JsonObject position = value.getAsJsonObject().getAsJsonObject("position");
                Point entrance = new Point(
                    originX + position.get("x").getAsDouble(),
                    originZ + position.get("z").getAsDouble()
                );
                double centerX = originX - entrance.x();
                double centerZ = originZ - entrance.z();
                double centerDistance = Math.hypot(centerX, centerZ);
                if (centerDistance < 0.01D) continue;
                double approachLength = Math.min(32.0D, centerDistance);
                Point approachEnd = new Point(
                    entrance.x() + centerX / centerDistance * approachLength,
                    entrance.z() + centerZ / centerDistance * approachLength
                );
                Route nearestRoute = null;
                Point nearestPoint = null;
                double nearestDistance = Double.POSITIVE_INFINITY;
                for (Route route : routes) {
                    for (Segment segment : route.segments()) {
                        Point candidate = segment.closestPoint(
                            approachEnd.x(), approachEnd.z()
                        );
                        double dx = candidate.x() - approachEnd.x();
                        double dz = candidate.z() - approachEnd.z();
                        double distance = dx * dx + dz * dz;
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearestRoute = route;
                            nearestPoint = candidate;
                        }
                    }
                }
                if (nearestRoute == null || nearestPoint == null) continue;
                List<Segment> approach = new ArrayList<>();
                approach.add(new Segment(entrance, approachEnd));
                if (nearestDistance > 0.25D) {
                    approach.add(new Segment(approachEnd, nearestPoint));
                }
                routes.add(new Route(
                    List.copyOf(approach), nearestRoute.radius(), nearestRoute.surface()
                ));
            }
        }

        PathSample sample(double x, double z) {
            double nearest = Double.POSITIVE_INFINITY;
            Route nearestRoute = null;
            for (Route route : routes) {
                for (Segment segment : route.segments()) {
                    double distance = segment.distanceSquared(x, z);
                    if (distance < nearest) {
                        nearest = distance;
                        nearestRoute = route;
                    }
                }
            }
            boolean walkable = nearestRoute != null && nearest <= nearestRoute.radius() * nearestRoute.radius();
            return new PathSample(walkable,
                nearestRoute == null ? Blocks.DIRT_PATH.defaultBlockState() : nearestRoute.surface());
        }

        private static List<Point> spline(List<Point> controls, double tension) {
            if (controls.size() < 3) return controls;
            List<Point> result = new ArrayList<>();
            double blend = Math.max(0.0D, Math.min(1.0D, tension));
            for (int index = 0; index + 1 < controls.size(); index++) {
                Point p0 = controls.get(Math.max(0, index - 1));
                Point p1 = controls.get(index);
                Point p2 = controls.get(index + 1);
                Point p3 = controls.get(Math.min(controls.size() - 1, index + 2));
                for (int step = 0; step < 8; step++) {
                    double t = step / 8.0D;
                    double linearX = p1.x() + (p2.x() - p1.x()) * t;
                    double linearZ = p1.z() + (p2.z() - p1.z()) * t;
                    double curvedX = catmull(p0.x(), p1.x(), p2.x(), p3.x(), t);
                    double curvedZ = catmull(p0.z(), p1.z(), p2.z(), p3.z(), t);
                    result.add(new Point(
                        linearX + (curvedX - linearX) * blend,
                        linearZ + (curvedZ - linearZ) * blend
                    ));
                }
            }
            result.add(controls.getLast());
            return List.copyOf(result);
        }

        private static double catmull(double p0, double p1, double p2, double p3, double t) {
            double t2 = t * t;
            double t3 = t2 * t;
            return 0.5D * ((2.0D * p1) + (-p0 + p2) * t
                + (2.0D * p0 - 5.0D * p1 + 4.0D * p2 - p3) * t2
                + (-p0 + 3.0D * p1 - 3.0D * p2 + p3) * t3);
        }
    }

    private record Route(List<Segment> segments, double radius, BlockState surface) {}
    private record Point(double x, double z) {}
    private record Segment(Point from, Point to) {
        double distanceSquared(double x, double z) {
            Point closest = closestPoint(x, z);
            double ox = x - closest.x();
            double oz = z - closest.z();
            return ox * ox + oz * oz;
        }

        Point closestPoint(double x, double z) {
            double dx = to.x() - from.x();
            double dz = to.z() - from.z();
            double lengthSquared = dx * dx + dz * dz;
            if (lengthSquared < 0.0001D) {
                return from;
            }
            double t = Math.max(0.0D, Math.min(1.0D,
                ((x - from.x()) * dx + (z - from.z()) * dz) / lengthSquared));
            return new Point(from.x() + dx * t, from.z() + dz * t);
        }
    }
    private record PathSample(boolean walkable, BlockState surface) {}
}
