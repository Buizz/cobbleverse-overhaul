package dev.buizz.cobbleventure.bootstrap;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap.BlockPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;

final class NaturalCaveGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LAYOUT_VERSION = 6;
    private static final int SHELL_THICKNESS = 4;

    private NaturalCaveGenerator() {}

    static void generate(ServerLevel level, long worldSeed, List<Entrance> configuredEntrances) {
        Map<String, List<Entrance>> byCave = new LinkedHashMap<>();
        for (Entrance entrance : configuredEntrances) {
            byCave.computeIfAbsent(entrance.cave(), ignored -> new ArrayList<>()).add(entrance);
        }
        for (Map.Entry<String, List<Entrance>> entry : byCave.entrySet()) {
            List<Entrance> entrances = entry.getValue().stream()
                .sorted(Comparator.comparingInt(value -> value.portalAnchor().x()))
                .toList();
            if (entrances.size() < 2) {
                continue;
            }
            long seed = worldSeed ^ entry.getKey().hashCode() * 341873128712L
                ^ entrances.getFirst().settings().seedSalt();
            generateCave(level, entry.getKey(), seed, entrances, entrances.getFirst().settings());
        }
    }

    private static void generateCave(
        ServerLevel level, String caveId, long seed, List<Entrance> entrances, Settings settings
    ) {
        int markerX = entrances.stream().mapToInt(value -> value.portalAnchor().x()).sum()
            / entrances.size();
        int markerZ = entrances.stream().mapToInt(value -> value.portalAnchor().z()).sum()
            / entrances.size();
        BlockPos marker = new BlockPos(markerX, 116 + LAYOUT_VERSION, markerZ);
        if (level.getBlockState(marker).is(Blocks.REINFORCED_DEEPSLATE)) {
            return;
        }

        removeLegacyStraightTunnel(level, entrances);
            CaveLayout layout = settings.manualLayout().enabled()
                ? createManualLayout(entrances, settings)
                : createLayout(seed, entrances, settings);
        List<Blob> blobs = new ArrayList<>();
        for (Room room : layout.rooms()) {
            blobs.add(new Blob(
                room.x(), room.floorY() + room.height() * 0.48D, room.z(),
                room.radiusX(), room.height() * 0.62D, room.radiusZ()
            ));
        }
        int pathIndex = 0;
        for (CavePath cavePath : layout.paths()) {
            List<PathPoint> path = cavePath.points();
            for (int edge = 0; edge < path.size() - 1; edge++) {
                List<PathPoint> samples = sampleEdge(
                    path.get(edge), path.get(edge + 1), seed, pathIndex * 31 + edge, 2.75D
                );
                for (PathPoint sample : samples) {
                    double tunnelRadius = cavePath.width() > 0
                        ? Math.max(2.0D, cavePath.width() * 0.62D)
                        : settings.minimumTunnelRadius()
                            + (settings.maximumTunnelRadius() - settings.minimumTunnelRadius())
                                * (0.5D + signedNoise(seed, pathIndex, edge, sample.floorY()) * 0.35D);
                    blobs.add(new Blob(
                        sample.x(), sample.floorY() + 5.0D, sample.z(),
                        tunnelRadius, Math.max(4.5D, tunnelRadius * 0.88D), tunnelRadius
                    ));
                }
            }
            pathIndex++;
        }

        for (Blob blob : blobs) {
            fillRockShell(level, blob, seed, settings.surfaceRoughness());
        }
        for (Blob blob : blobs) {
            carveInterior(level, blob, seed, settings.surfaceRoughness());
        }

        int stairs = 0;
        pathIndex = 0;
        for (CavePath path : layout.paths()) {
            stairs += placeWalkablePath(level, path.points(), seed, pathIndex++, path.width());
            if (path.kind().equals("bridge")) {
                placeNaturalBridge(level, path.points(), seed, path.width());
            }
        }
        for (Room room : layout.rooms()) {
            prepareRoomFloor(level, room);
        }
        decorateRooms(level, layout, seed, settings);
        buildEntranceLandmarks(level, layout, entrances);
        int flooded = floodSubmergedCave(level, blobs, settings.waterLevel());
        applyInternalBiomes(level, layout, settings);
        level.setBlock(marker, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
        LOGGER.info(
            "Natural cave generated: cave={}, rooms={}, paths={}, blobs={}, stairs={}, flooded={}",
            caveId, layout.rooms().size(), layout.paths().size(), blobs.size(), stairs, flooded
        );
    }

    private static CaveLayout createLayout(
        long seed, List<Entrance> entrances, Settings settings
    ) {
        Random random = new Random(seed);
        Entrance west = entrances.getFirst();
        Entrance east = entrances.getLast();
        BlockPoint start = west.portalAnchor();
        BlockPoint end = east.portalAnchor();
        int roomCount = Math.max(3, settings.mainRooms());
        List<Room> mainRooms = new ArrayList<>();
        List<PathPoint> mainPath = new ArrayList<>();
        for (int index = 0; index < roomCount; index++) {
            double progress = index / (double) (roomCount - 1);
            double x = lerp(start.x(), end.x(), progress);
            double z = lerp(start.z(), end.z(), progress);
            int floorY = (int) Math.round(lerp(start.y(), end.y(), progress));
            if (index > 0 && index < roomCount - 1) {
                z += Math.sin(index * 1.37D) * 27.0D + random.nextInt(-12, 13);
                int verticalAmplitude = Math.max(4, settings.verticalRange() / 2);
                floorY += (int) Math.round(Math.sin(index * 1.71D) * verticalAmplitude * 0.72D)
                    + random.nextInt(-Math.max(2, verticalAmplitude / 4), Math.max(3, verticalAmplitude / 4 + 1));
                if (index == Math.max(2, roomCount * 2 / 3)) {
                    floorY += Math.max(5, verticalAmplitude / 2);
                }
            }
            floorY = Math.max(34, Math.min(72, floorY));
            boolean grandRoom = index == roomCount / 2;
            double radiusX = index == 0 || index == roomCount - 1
                ? Math.max(9.0D, settings.minimumRoomRadius())
                : randomBetween(random, settings.minimumRoomRadius(), settings.maximumRoomRadius());
            double radiusZ = index == 0 || index == roomCount - 1
                ? Math.max(9.0D, settings.minimumRoomRadius() - 1.0D)
                : randomBetween(random, settings.minimumRoomRadius(), settings.maximumRoomRadius());
            double height = index == 0 || index == roomCount - 1
                ? 10.0D : randomBetween(
                    random,
                    Math.max(9.0D, settings.minimumRoomRadius() * 0.72D),
                    Math.max(11.0D, settings.maximumRoomRadius() * 0.72D)
                );
            if (grandRoom) {
                radiusX = Math.max(radiusX, settings.maximumRoomRadius() * settings.grandRoomScale());
                radiusZ = Math.max(radiusZ, settings.maximumRoomRadius() * settings.grandRoomScale() * 0.82D);
                height = Math.max(height, settings.maximumRoomRadius() * settings.grandRoomScale() * 0.78D);
            }
            Room room = new Room(x, floorY, z, radiusX, height, radiusZ, grandRoom ? "grand" : "main");
            mainRooms.add(room);
            mainPath.add(room.pathPoint());
        }

        List<Room> rooms = new ArrayList<>(mainRooms);
        List<CavePath> paths = new ArrayList<>();
        paths.add(new CavePath(List.copyOf(mainPath), "main", 0));
        for (int branchIndex = 0; branchIndex < settings.branchCount(); branchIndex++) {
            int rootIndex = 1 + (branchIndex + 1) * (roomCount - 2) / (settings.branchCount() + 1);
            Room root = mainRooms.get(Math.max(1, Math.min(roomCount - 2, rootIndex)));
            int direction = branchIndex % 2 == 0 ? -1 : 1;
            double endX = root.x() + random.nextInt(-24, 25);
            double endZ = root.z() + direction * (58 + random.nextInt(29));
            int endY = Math.max(30, Math.min(
                76,
                root.floorY() + random.nextInt(-settings.verticalRange() / 2, settings.verticalRange() / 2 + 1)
            ));
            String kind = branchIndex == 0 ? "moon" : "wild";
            double branchRadiusX = randomBetween(random, settings.minimumRoomRadius(), settings.maximumRoomRadius());
            double branchRadiusZ = randomBetween(random, settings.minimumRoomRadius(), settings.maximumRoomRadius());
            Room branchRoom = new Room(
                endX, endY, endZ,
                branchRadiusX,
                randomBetween(
                    random,
                    Math.max(9.0D, settings.minimumRoomRadius() * 0.7D),
                    Math.max(11.0D, settings.maximumRoomRadius() * 0.72D)
                ),
                branchRadiusZ,
                kind
            );
            rooms.add(branchRoom);
            PathPoint middle = new PathPoint(
                (root.x() + endX) * 0.5D + random.nextInt(-10, 11),
                (root.floorY() + endY) / 2,
                (root.z() + endZ) * 0.5D
            );
            paths.add(new CavePath(List.of(root.pathPoint(), middle, branchRoom.pathPoint()), "branch", 0));
        }

        if (roomCount >= 5 && random.nextDouble() <= settings.loopChance()) {
            Room loopFrom = mainRooms.get(Math.max(1, roomCount / 3));
            Room loopTo = mainRooms.get(Math.min(roomCount - 2, roomCount * 2 / 3));
            int loopY = Math.max(34, Math.min(74, (loopFrom.floorY() + loopTo.floorY()) / 2 + 7));
            paths.add(new CavePath(List.of(
                loopFrom.pathPoint(),
                new PathPoint(
                    (loopFrom.x() + loopTo.x()) * 0.5D,
                    loopY,
                    Math.min(loopFrom.z(), loopTo.z()) - 58.0D
                ),
                loopTo.pathPoint()
            ), "loop", 0));
        }
        if (settings.elevatedCrossing() && roomCount >= 5) {
            int grandIndex = roomCount / 2;
            Room grand = mainRooms.get(grandIndex);
            Room bridgeFrom = mainRooms.get(Math.min(roomCount - 2, grandIndex + 1));
            Room bridgeTo = mainRooms.get(Math.min(roomCount - 1, grandIndex + 2));
            int bridgeY = Math.min(76, grand.floorY() + Math.max(10, settings.bridgeClearance()));
            double span = Math.max(16.0D, grand.radiusZ() * 0.72D);
            paths.add(new CavePath(List.of(
                bridgeFrom.pathPoint(),
                new PathPoint(grand.x() + grand.radiusX() * 0.62D, bridgeY, grand.z() - span),
                new PathPoint(grand.x(), bridgeY, grand.z()),
                new PathPoint(grand.x() - grand.radiusX() * 0.62D, bridgeY, grand.z() + span),
                bridgeTo.pathPoint()
            ), "bridge", 5));
        }
        return new CaveLayout(List.copyOf(rooms), List.copyOf(paths));
    }

    private static CaveLayout createManualLayout(List<Entrance> entrances, Settings settings) {
        Map<String, PathPoint> points = new LinkedHashMap<>();
        for (Entrance entrance : entrances) {
            points.put(entrance.id(), new PathPoint(
                entrance.portalAnchor().x(), entrance.portalAnchor().y(), entrance.portalAnchor().z()
            ));
        }
        List<Room> rooms = new ArrayList<>();
        for (ManualAnchor anchor : settings.manualLayout().anchors()) {
            PathPoint point = new PathPoint(anchor.x(), anchor.y(), anchor.z());
            points.put(anchor.id(), point);
            rooms.add(new Room(
                anchor.x(), anchor.y(), anchor.z(), anchor.radiusX(), anchor.height(), anchor.radiusZ(),
                switch (anchor.kind()) {
                    case "grand" -> "grand";
                    case "landmark" -> "moon";
                    case "junction" -> "main";
                    default -> "wild";
                }
            ));
        }
        List<CavePath> paths = new ArrayList<>();
        for (ManualConnection connection : settings.manualLayout().connections()) {
            PathPoint from = points.get(connection.from());
            PathPoint to = points.get(connection.to());
            if (from == null || to == null) {
                LOGGER.warn("Manual cave connection skipped: id={}, from={}, to={}", connection.id(), connection.from(), connection.to());
                continue;
            }
            paths.add(new CavePath(List.of(from, to), connection.kind(), connection.width()));
        }
        return new CaveLayout(List.copyOf(rooms), List.copyOf(paths));
    }

    private static void fillRockShell(
        ServerLevel level, Blob blob, long seed, double roughness
    ) {
        int minX = (int) Math.floor(blob.x() - blob.radiusX() - SHELL_THICKNESS);
        int maxX = (int) Math.ceil(blob.x() + blob.radiusX() + SHELL_THICKNESS);
        int minY = (int) Math.floor(blob.y() - blob.radiusY() - SHELL_THICKNESS);
        int maxY = (int) Math.ceil(blob.y() + blob.radiusY() + SHELL_THICKNESS);
        int minZ = (int) Math.floor(blob.z() - blob.radiusZ() - SHELL_THICKNESS);
        int maxZ = (int) Math.ceil(blob.z() + blob.radiusZ() + SHELL_THICKNESS);
        double radiusX = blob.radiusX() + SHELL_THICKNESS;
        double radiusY = blob.radiusY() + SHELL_THICKNESS;
        double radiusZ = blob.radiusZ() + SHELL_THICKNESS;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double distance = ellipsoidDistance(blob, x, y, z, radiusX, radiusY, radiusZ);
                    if (distance > 1.04D + signedNoise(seed, x, y, z) * roughness * 0.15D) {
                        continue;
                    }
                    level.setBlock(new BlockPos(x, y, z), caveRock(seed, x, y, z), 2);
                }
            }
        }
    }

    private static void carveInterior(
        ServerLevel level, Blob blob, long seed, double roughness
    ) {
        int minX = (int) Math.floor(blob.x() - blob.radiusX());
        int maxX = (int) Math.ceil(blob.x() + blob.radiusX());
        int minY = (int) Math.floor(blob.y() - blob.radiusY());
        int maxY = (int) Math.ceil(blob.y() + blob.radiusY());
        int minZ = (int) Math.floor(blob.z() - blob.radiusZ());
        int maxZ = (int) Math.ceil(blob.z() + blob.radiusZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double distance = ellipsoidDistance(
                        blob, x, y, z, blob.radiusX(), blob.radiusY(), blob.radiusZ()
                    );
                    if (distance <= 0.96D
                        + signedNoise(seed ^ 0xC0FFEE, x, y, z) * roughness * 0.27D) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static int placeWalkablePath(
        ServerLevel level, List<PathPoint> path, long seed, int pathIndex, int configuredWidth
    ) {
        int stairs = 0;
        int halfWidth = configuredWidth > 0 ? Math.max(1, configuredWidth / 2) : 2;
        PathPoint previous = null;
        for (int edge = 0; edge < path.size() - 1; edge++) {
            List<PathPoint> samples = sampleEdge(
                path.get(edge), path.get(edge + 1), seed, pathIndex * 31 + edge, 0.9D
            );
            for (int index = 0; index < samples.size(); index++) {
                PathPoint sample = samples.get(index);
                int x = (int) Math.round(sample.x());
                int z = (int) Math.round(sample.z());
                int floorY = sample.floorY();
                for (int offsetX = -halfWidth; offsetX <= halfWidth; offsetX++) {
                    for (int offsetZ = -halfWidth; offsetZ <= halfWidth; offsetZ++) {
                        if (offsetX * offsetX + offsetZ * offsetZ > halfWidth * halfWidth + 1) {
                            continue;
                        }
                        level.setBlock(
                            new BlockPos(x + offsetX, floorY, z + offsetZ),
                            naturalFloor(seed, x + offsetX, floorY, z + offsetZ), 2
                        );
                        for (int y = floorY + 1; y <= floorY + 5; y++) {
                            level.setBlock(
                                new BlockPos(x + offsetX, y, z + offsetZ),
                                Blocks.AIR.defaultBlockState(), 2
                            );
                        }
                    }
                }
                if (previous != null && previous.floorY() != floorY) {
                    Direction direction = horizontalDirection(previous, sample);
                    if (floorY < previous.floorY()) {
                        direction = direction.getOpposite();
                    }
                    BlockState stair = Blocks.COBBLED_DEEPSLATE_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
                    int sideX = direction.getStepZ();
                    int sideZ = -direction.getStepX();
                    int stairHalfWidth = Math.max(1, halfWidth - 1);
                    for (int lateral = -stairHalfWidth; lateral <= stairHalfWidth; lateral++) {
                        level.setBlock(
                            new BlockPos(x + sideX * lateral, floorY, z + sideZ * lateral),
                            stair, 2
                        );
                    }
                    stairs += stairHalfWidth * 2 + 1;
                }
                if (index % 18 == 9) {
                    level.setBlock(
                        new BlockPos(x, floorY, z), Blocks.OCHRE_FROGLIGHT.defaultBlockState(), 2
                    );
                }
                previous = sample;
            }
        }
        return stairs;
    }

    private static void placeNaturalBridge(
        ServerLevel level, List<PathPoint> path, long seed, int configuredWidth
    ) {
        int sampleIndex = 0;
        int halfWidth = Math.max(2, configuredWidth / 2);
        int firstEdge = path.size() <= 2 ? 0 : 1;
        int lastEdgeExclusive = path.size() <= 2 ? path.size() - 1 : path.size() - 2;
        for (int edge = firstEdge; edge < lastEdgeExclusive; edge++) {
            List<PathPoint> samples = sampleEdge(path.get(edge), path.get(edge + 1), seed, 700 + edge, 0.72D);
            for (PathPoint sample : samples) {
                int x = (int) Math.round(sample.x());
                int y = sample.floorY();
                int z = (int) Math.round(sample.z());
                Direction forward = edge + 1 < path.size()
                    ? horizontalDirection(sample, path.get(edge + 1)) : Direction.EAST;
                int sideX = forward.getStepZ();
                int sideZ = -forward.getStepX();
                for (int lateral = -halfWidth; lateral <= halfWidth; lateral++) {
                    BlockState bridgeRock = Math.floorMod(sampleIndex + lateral, 5) == 0
                        ? Blocks.TUFF.defaultBlockState()
                        : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
                    level.setBlock(new BlockPos(x + sideX * lateral, y, z + sideZ * lateral), bridgeRock, 2);
                    for (int clearY = y + 1; clearY <= y + 5; clearY++) {
                        level.setBlock(new BlockPos(x + sideX * lateral, clearY, z + sideZ * lateral), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                if (sampleIndex % 9 == 4) {
                    for (int lateral : new int[] {-halfWidth - 1, halfWidth + 1}) {
                        level.setBlock(
                            new BlockPos(x + sideX * lateral, y + 1, z + sideZ * lateral),
                            Blocks.COBBLED_DEEPSLATE_WALL.defaultBlockState(), 2
                        );
                    }
                }
                if (sampleIndex % 17 == 8) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.OCHRE_FROGLIGHT.defaultBlockState(), 2);
                }
                sampleIndex++;
            }
        }
    }

    private static void prepareRoomFloor(ServerLevel level, Room room) {
        int centerX = (int) Math.round(room.x());
        int centerZ = (int) Math.round(room.z());
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                level.setBlock(
                    new BlockPos(x, room.floorY(), z), Blocks.DEEPSLATE.defaultBlockState(), 2
                );
                for (int y = room.floorY() + 1; y <= room.floorY() + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void decorateRooms(
        ServerLevel level, CaveLayout layout, long seed, Settings settings
    ) {
        Random random = new Random(seed ^ 0x5EEDBEEFL);
        for (Room room : layout.rooms()) {
            int decorations = room.kind().equals("main") ? 3 : 7;
            for (int index = 0; index < decorations; index++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double distance = 0.55D + random.nextDouble() * 0.25D;
                int x = (int) Math.round(room.x() + Math.cos(angle) * room.radiusX() * distance);
                int z = (int) Math.round(room.z() + Math.sin(angle) * room.radiusZ() * distance);
                int floorY = findNaturalFloor(level, x, room.floorY(), z);
                if (floorY == Integer.MIN_VALUE) {
                    continue;
                }
                int height = room.kind().equals("main") ? 1 + random.nextInt(2) : 1 + random.nextInt(3);
                BlockState decoration = room.kind().equals("moon")
                    ? (index % 2 == 0
                        ? Blocks.AMETHYST_BLOCK.defaultBlockState()
                        : Blocks.CALCITE.defaultBlockState())
                    : room.kind().equals("wild")
                        ? Blocks.MOSS_BLOCK.defaultBlockState()
                        : Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                for (int y = 1; y <= height; y++) {
                    level.setBlock(new BlockPos(x, floorY + y, z), decoration, 2);
                }
                if (room.kind().equals("moon")) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                            if (Math.abs(offsetX) + Math.abs(offsetZ) <= 1) {
                                level.setBlock(
                                    new BlockPos(x + offsetX, floorY, z + offsetZ),
                                    Blocks.CALCITE.defaultBlockState(), 2
                                );
                            }
                        }
                    }
                }
            }
            for (int lightIndex = 0; lightIndex < 3; lightIndex++) {
                double angle = Math.PI * 2.0D * lightIndex / 3.0D + 0.35D;
                int lightX = (int) Math.round(room.x() + Math.cos(angle) * room.radiusX() * 0.42D);
                int lightZ = (int) Math.round(room.z() + Math.sin(angle) * room.radiusZ() * 0.42D);
                int ceilingY = findCeiling(level, lightX, room.floorY() + 4, lightZ);
                if (ceilingY != Integer.MIN_VALUE) {
                    level.setBlock(
                        new BlockPos(lightX, ceilingY, lightZ),
                        room.kind().equals("moon")
                            ? Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState()
                            : Blocks.SHROOMLIGHT.defaultBlockState(),
                        2
                    );
                }
            }
        }
    }

    private static int floodSubmergedCave(
        ServerLevel level, List<Blob> blobs, int waterLevel
    ) {
        int flooded = 0;
        for (Blob blob : blobs) {
            int minX = (int) Math.floor(blob.x() - blob.radiusX());
            int maxX = (int) Math.ceil(blob.x() + blob.radiusX());
            int minY = (int) Math.floor(blob.y() - blob.radiusY());
            int maxY = Math.min(waterLevel, (int) Math.ceil(blob.y() + blob.radiusY()));
            int minZ = (int) Math.floor(blob.z() - blob.radiusZ());
            int maxZ = (int) Math.ceil(blob.z() + blob.radiusZ());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (ellipsoidDistance(
                            blob, x, y, z, blob.radiusX(), blob.radiusY(), blob.radiusZ()
                        ) > 0.96D) {
                            continue;
                        }
                        BlockPos position = new BlockPos(x, y, z);
                        if (level.getBlockState(position).isAir()) {
                            level.setBlock(position, Blocks.WATER.defaultBlockState(), 2);
                            flooded++;
                        }
                    }
                }
            }
        }
        return flooded;
    }

    private static void buildEntranceLandmarks(
        ServerLevel level, CaveLayout layout, List<Entrance> entrances
    ) {
        for (Entrance entrance : entrances) {
            PathPoint portal = new PathPoint(
                entrance.portalAnchor().x(), entrance.portalAnchor().y(), entrance.portalAnchor().z()
            );
            Room interior = layout.rooms().stream()
                .filter(room -> horizontalDistanceSquared(room.pathPoint(), portal) > 144.0D)
                .min(Comparator.comparingDouble(room -> horizontalDistanceSquared(room.pathPoint(), portal)))
                .orElse(layout.rooms().getFirst());
            Direction inward = horizontalDirection(portal, interior.pathPoint());
            int sideX = inward.getStepZ();
            int sideZ = -inward.getStepX();
            int centerX = entrance.portalAnchor().x();
            int centerZ = entrance.portalAnchor().z();
            int floorY = entrance.portalAnchor().y();
            for (int lateral = -6; lateral <= 6; lateral++) {
                for (int vertical = 0; vertical <= 8; vertical++) {
                    boolean sidePillar = Math.abs(lateral) >= 4 && vertical <= 6;
                    boolean lintel = vertical >= 6
                        && Math.abs(lateral) <= Math.max(3, 8 - vertical);
                    if (!sidePillar && !lintel) {
                        continue;
                    }
                    int x = centerX + sideX * lateral;
                    int z = centerZ + sideZ * lateral;
                    BlockState frame = (vertical + Math.abs(lateral)) % 5 == 0
                        ? Blocks.CALCITE.defaultBlockState()
                        : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                    level.setBlock(new BlockPos(x, floorY + vertical, z), frame, 2);
                }
            }
            for (int lateral = -3; lateral <= 3; lateral++) {
                for (int vertical = 1; vertical <= 5; vertical++) {
                    int x = centerX + sideX * lateral;
                    int z = centerZ + sideZ * lateral;
                    level.setBlock(new BlockPos(x, floorY + vertical, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            int[][] lights = {
                {-5, 2}, {5, 2}, {-4, 5}, {4, 5}, {0, 7}
            };
            for (int[] light : lights) {
                int x = centerX + sideX * light[0];
                int z = centerZ + sideZ * light[0];
                level.setBlock(
                    new BlockPos(x, floorY + light[1], z),
                    Blocks.SEA_LANTERN.defaultBlockState(), 2
                );
            }
            BlockState stair = Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, inward);
            for (int lateral = -2; lateral <= 2; lateral++) {
                int x = centerX + sideX * lateral;
                int z = centerZ + sideZ * lateral;
                level.setBlock(new BlockPos(x, floorY, z), stair, 2);
            }
            for (int depth : new int[] {4, 10, 16}) {
                int x = centerX + inward.getStepX() * depth;
                int z = centerZ + inward.getStepZ() * depth;
                level.setBlock(
                    new BlockPos(x, floorY, z), Blocks.OCHRE_FROGLIGHT.defaultBlockState(), 2
                );
            }
        }
    }

    private static void applyInternalBiomes(
        ServerLevel level, CaveLayout layout, Settings settings
    ) {
        List<String> biomes = settings.internalBiomes().isEmpty()
            ? List.of("minecraft:dripstone_caves") : settings.internalBiomes();
        int painted = 0;
        for (int index = 0; index < layout.rooms().size(); index++) {
            Room room = layout.rooms().get(index);
            int biomeIndex = room.kind().equals("main") ? 0
                : room.kind().equals("moon") || room.kind().equals("water")
                    ? Math.min(1, biomes.size() - 1)
                    : Math.floorMod(index, biomes.size());
            fillBiomeBox(
                level,
                (int) Math.floor(room.x() - room.radiusX()),
                room.floorY() - 12,
                (int) Math.floor(room.z() - room.radiusZ()),
                (int) Math.ceil(room.x() + room.radiusX()),
                room.floorY() + (int) Math.ceil(room.height()) + 10,
                (int) Math.ceil(room.z() + room.radiusZ()),
                biomes.get(biomeIndex)
            );
            painted++;
        }
        LOGGER.info("Natural cave biome zones applied: rooms={}, biomes={}", painted, biomes);
    }

    private static void fillBiomeBox(
        ServerLevel level,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        String biome
    ) {
        for (int x = minX; x <= maxX; x += 32) {
            int sectionMaxX = Math.min(maxX, x + 31);
            for (int y = minY; y <= maxY; y += 32) {
                int sectionMaxY = Math.min(maxY, y + 31);
                for (int z = minZ; z <= maxZ; z += 32) {
                    int sectionMaxZ = Math.min(maxZ, z + 31);
                    try {
                        level.getServer().getCommands().getDispatcher().execute(
                            "fillbiome " + x + " " + y + " " + z + " "
                                + sectionMaxX + " " + sectionMaxY + " " + sectionMaxZ
                                + " " + biome,
                            level.getServer().createCommandSourceStack()
                                .withLevel(level)
                                .withPermission(4)
                                .withSuppressedOutput()
                        );
                    } catch (CommandSyntaxException error) {
                        throw new IllegalStateException(
                            "Natural cave biome painting failed: " + biome, error
                        );
                    }
                }
            }
        }
    }

    private static double horizontalDistanceSquared(PathPoint first, PathPoint second) {
        double dx = first.x() - second.x();
        double dz = first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private static List<PathPoint> sampleEdge(
        PathPoint from, PathPoint to, long seed, int edgeIndex, double spacing
    ) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double length = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        int steps = Math.max(1, (int) Math.ceil(length / spacing));
        double sideX = -dz / length;
        double sideZ = dx / length;
        double bend = signedNoise(seed, edgeIndex, from.floorY(), to.floorY())
            * Math.min(12.0D, length * 0.16D);
        List<PathPoint> samples = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double progress = step / (double) steps;
            double curve = Math.sin(Math.PI * progress) * bend;
            samples.add(new PathPoint(
                lerp(from.x(), to.x(), progress) + sideX * curve,
                (int) Math.round(lerp(from.floorY(), to.floorY(), progress)),
                lerp(from.z(), to.z(), progress) + sideZ * curve
            ));
        }
        return samples;
    }

    private static void removeLegacyStraightTunnel(ServerLevel level, List<Entrance> entrances) {
        BlockPos legacyMarker = new BlockPos(0, 60, 0);
        if (!level.getBlockState(legacyMarker).is(Blocks.LODESTONE)) {
            return;
        }
        int minX = entrances.stream().mapToInt(value -> value.destination().x()).min().orElse(-320) - 10;
        int maxX = entrances.stream().mapToInt(value -> value.destination().x()).max().orElse(320) + 10;
        int centerZ = entrances.getFirst().destination().z();
        int floorY = entrances.stream().mapToInt(value -> value.destination().y()).min().orElse(49) - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = centerZ - 8; z <= centerZ + 8; z++) {
                for (int y = floorY; y <= floorY + 10; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        level.setBlock(legacyMarker, Blocks.AIR.defaultBlockState(), 2);
    }

    private static int findNaturalFloor(ServerLevel level, int x, int expectedY, int z) {
        for (int y = expectedY + 8; y >= expectedY - 10; y--) {
            BlockPos floor = new BlockPos(x, y, z);
            if (!level.getBlockState(floor).isAir()
                && level.getBlockState(floor.above()).isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int findCeiling(ServerLevel level, int x, int startY, int z) {
        for (int y = startY; y <= startY + 24; y++) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static Direction horizontalDirection(PathPoint from, PathPoint to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private static BlockState caveRock(long seed, int x, int y, int z) {
        double value = signedNoise(seed ^ 0xA11CE, x >> 2, y >> 2, z >> 2);
        if (value > 0.84D) return Blocks.TUFF.defaultBlockState();
        if (value < -0.84D) return Blocks.ANDESITE.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState naturalFloor(long seed, int x, int y, int z) {
        return signedNoise(seed ^ 0xF100D, x >> 2, y, z >> 2) > 0.62D
            ? Blocks.TUFF.defaultBlockState()
            : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
    }

    private static double ellipsoidDistance(
        Blob blob, int x, int y, int z, double radiusX, double radiusY, double radiusZ
    ) {
        double dx = (x + 0.5D - blob.x()) / radiusX;
        double dy = (y + 0.5D - blob.y()) / radiusY;
        double dz = (z + 0.5D - blob.z()) / radiusZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double signedNoise(long seed, int x, int y, int z) {
        long value = seed;
        value ^= x * 341873128712L;
        value ^= y * 132897987541L;
        value ^= z * 42317861L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return ((value & 0xFFFFFFL) / (double) 0x7FFFFF) - 1.0D;
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private static double randomBetween(Random random, double minimum, double maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        return minimum + random.nextDouble() * (maximum - minimum);
    }

    record Entrance(
        String id,
        String cave,
        BlockPoint destination,
        BlockPoint portalAnchor,
        Settings settings
    ) {}

    record Settings(
        long seedSalt,
        int mainRooms,
        int branchCount,
        double loopChance,
        int verticalRange,
        double minimumRoomRadius,
        double maximumRoomRadius,
        double minimumTunnelRadius,
        double maximumTunnelRadius,
        double surfaceRoughness,
        int waterLevel,
        double grandRoomScale,
        boolean elevatedCrossing,
        int bridgeClearance,
        ManualLayout manualLayout,
        List<String> internalBiomes
    ) {
        static Settings defaults() {
            return new Settings(
                0L, 7, 4, 0.35D, 28, 10.0D, 28.0D, 3.0D, 7.0D, 0.18D, 38,
                1.65D, false, 13,
                ManualLayout.disabled(),
                List.of("minecraft:dripstone_caves")
            );
        }
    }

    private record CaveLayout(List<Room> rooms, List<CavePath> paths) {}

    record ManualLayout(boolean enabled, List<ManualAnchor> anchors, List<ManualConnection> connections) {
        static ManualLayout disabled() {
            return new ManualLayout(false, List.of(), List.of());
        }
    }

    record ManualAnchor(
        String id, String kind, int x, int y, int z, double radiusX, double radiusZ, double height
    ) {}

    record ManualConnection(String id, String from, String to, String kind, int width) {}

    private record CavePath(List<PathPoint> points, String kind, int width) {}

    private record Room(
        double x,
        int floorY,
        double z,
        double radiusX,
        double height,
        double radiusZ,
        String kind
    ) {
        PathPoint pathPoint() {
            return new PathPoint(x, floorY, z);
        }
    }

    private record PathPoint(double x, int floorY, double z) {}

    private record Blob(
        double x,
        double y,
        double z,
        double radiusX,
        double radiusY,
        double radiusZ
    ) {}
}
