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
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import org.slf4j.Logger;

final class NaturalCaveGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LAYOUT_VERSION = 14;
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

    static InstanceResult generateInstance(
        ServerLevel level,
        String caveId,
        long seed,
        BlockPos origin,
        BlockPos bounds,
        int mainRooms,
        int branchCount,
        double loopChance
    ) {
        return generateInstance(
            level, caveId, seed, origin, bounds, "critical_path_branches",
            mainRooms, branchCount, 1, loopChance
        );
    }

    static InstanceResult generateInstance(
        ServerLevel level,
        String caveId,
        long seed,
        BlockPos origin,
        BlockPos bounds,
        String layoutMode,
        int mainRooms,
        int branchCount,
        double loopChance
    ) {
        return generateInstance(
            level, caveId, seed, origin, bounds, layoutMode,
            mainRooms, branchCount, 1, loopChance
        );
    }

    static InstanceResult generateInstance(
        ServerLevel level,
        String caveId,
        long seed,
        BlockPos origin,
        BlockPos bounds,
        String layoutMode,
        int mainRooms,
        int branchCount,
        int branchDepth,
        double loopChance
    ) {
        InstancePlan plan = planInstance(
            caveId, seed, origin, bounds, layoutMode,
            mainRooms, branchCount, branchDepth, loopChance
        );
        generateCave(level, caveId, seed, plan.entrances(), plan.settings());
        return new InstanceResult(
            plan.entryPosition(), plan.exitPosition(),
            plan.mainRoomPositions(), plan.branchRoomPositions()
        );
    }

    static InstancePlan planInstance(
        String caveId,
        long seed,
        BlockPos origin,
        BlockPos bounds,
        int mainRooms,
        int branchCount,
        double loopChance
    ) {
        return planInstance(
            caveId, seed, origin, bounds, "critical_path_branches",
            mainRooms, branchCount, 1, loopChance
        );
    }

    static InstancePlan planInstance(
        String caveId,
        long seed,
        BlockPos origin,
        BlockPos bounds,
        String layoutMode,
        int mainRooms,
        int branchCount,
        double loopChance
    ) {
        return planInstance(
            caveId, seed, origin, bounds, layoutMode,
            mainRooms, branchCount, 1, loopChance
        );
    }

    static InstancePlan planInstance(
        String caveId,
        long seed,
        BlockPos origin,
        BlockPos bounds,
        String layoutMode,
        int mainRooms,
        int branchCount,
        int branchDepth,
        double loopChance
    ) {
        if (bounds.getX() < 112 || bounds.getY() < 32 || bounds.getZ() < 112) {
            throw new IllegalArgumentException(
                "Procedural cave dungeon bounds must be at least 112x32x112"
            );
        }
        if (!List.of(
                "critical_path_branches", "maze", "rooms_and_corridors"
            ).contains(layoutMode)
            || mainRooms < 3 || mainRooms > 64 || branchCount < 0 || branchCount > 32
            || branchDepth < 1 || branchDepth > 8
            || loopChance < 0.0D || loopChance > 1.0D) {
            throw new IllegalArgumentException("Invalid procedural cave dungeon layout");
        }
        Random random = new Random(seed);
        int margin = 14;
        int floorY = origin.getY() + Math.max(8, bounds.getY() / 3);
        int centerZ = origin.getZ() + bounds.getZ() / 2;
        BlockPoint entry = new BlockPoint(origin.getX() + margin, floorY, centerZ);
        BlockPoint exit = new BlockPoint(
            origin.getX() + bounds.getX() - margin - 1, floorY, centerZ
        );

        List<ManualAnchor> anchors = new ArrayList<>();
        List<ManualConnection> connections = new ArrayList<>();
        String previous = "entry";
        int authoredRooms = Math.max(1, mainRooms - 2);
        for (int index = 0; index < authoredRooms; index++) {
            double progress = (index + 1.0D) / (authoredRooms + 1.0D);
            int x = (int) Math.round(lerp(entry.x(), exit.x(), progress));
            int lateral = Math.min(18, bounds.getZ() / 5);
            int z = switch (layoutMode) {
                case "maze" -> centerZ + ((index / 2) % 2 == 0 ? -lateral : lateral)
                    + random.nextInt(-2, 3);
                case "rooms_and_corridors" -> centerZ + random.nextInt(-3, 4);
                default -> centerZ + (int) Math.round(
                    Math.sin((index + 1) * 1.43D) * lateral
                ) + random.nextInt(-4, 5);
            };
            int y = floorY + random.nextInt(
                -Math.max(2, bounds.getY() / 10),
                Math.max(3, bounds.getY() / 10 + 1)
            );
            String id = "main_" + index;
            double radius = switch (layoutMode) {
                case "maze" -> index == authoredRooms / 2 ? 8.0D : 6.0D;
                case "rooms_and_corridors" -> index == authoredRooms / 2 ? 14.0D : 10.0D;
                default -> index == authoredRooms / 2 ? 11.0D : 8.0D;
            };
            anchors.add(new ManualAnchor(
                id, index == authoredRooms / 2 ? "grand" : "room",
                x, y, z, radius, radius, index == authoredRooms / 2 ? 11.0D : 9.0D,
                "rock"
            ));
            connections.add(new ManualConnection(
                "main_path_" + index, previous, id, "main",
                layoutMode.equals("maze") ? 3 : 5,
                layoutMode.equals("maze") ? "rugged" : "natural"
            ));
            previous = id;
        }
        connections.add(new ManualConnection(
            "main_path_exit", previous, "exit", "main",
            layoutMode.equals("maze") ? 3 : 5,
            layoutMode.equals("maze") ? "rugged" : "natural"
        ));

        for (int index = 0; index < branchCount; index++) {
            int rootIndex = Math.floorMod(index * 2 + 1, authoredRooms);
            ManualAnchor root = anchors.get(rootIndex);
            int side = index % 2 == 0 ? -1 : 1;
            int branchReach = layoutMode.equals("rooms_and_corridors")
                ? bounds.getZ() / 3 : bounds.getZ() / 2 - margin;
            String previousBranch = root.id();
            for (int depth = 0; depth < branchDepth; depth++) {
                double progress = (depth + 1.0D) / branchDepth;
                int z = centerZ + side * (int) Math.round(branchReach * progress);
                int x = Math.max(
                    origin.getX() + margin,
                    Math.min(origin.getX() + bounds.getX() - margin - 1,
                        root.x() + random.nextInt(-10, 11))
                );
                String id = "branch_" + index + "_" + depth;
                anchors.add(new ManualAnchor(
                    id, "branch", x, root.y(), z,
                    layoutMode.equals("maze") ? 5.5D : 7.0D,
                    layoutMode.equals("maze") ? 5.5D : 7.0D,
                    layoutMode.equals("maze") ? 7.0D : 8.0D, "rock"
                ));
                connections.add(new ManualConnection(
                    "branch_path_" + index + "_" + depth,
                    previousBranch, id, "branch",
                    layoutMode.equals("maze") ? 3 : 4, "rugged"
                ));
                previousBranch = id;
            }
        }

        if (mainRooms >= 5 && loopChance > 0.0D && random.nextDouble() <= loopChance) {
            String from = anchors.get(Math.max(0, authoredRooms / 3)).id();
            String to = anchors.get(Math.min(authoredRooms - 1, authoredRooms * 2 / 3)).id();
            if (!from.equals(to)) {
                connections.add(new ManualConnection(
                    "main_loop", from, to, "loop", 4, "rugged"
                ));
            }
        }

        Settings defaults = Settings.defaults(false);
        Settings settings = new Settings(
            seed, "rock", mainRooms, branchCount, loopChance,
            Math.max(8, bounds.getY() / 3),
            layoutMode.equals("maze") ? 5.5D : 7.0D,
            layoutMode.equals("rooms_and_corridors") ? 14.0D : 11.0D,
            layoutMode.equals("maze") ? 2.5D : 3.0D,
            layoutMode.equals("maze") ? 4.0D : 5.0D,
            defaults.surfaceRoughness(),
            origin.getY() - 16, 0, 1.25D, false,
            defaults.bridgeClearance(), false,
            new ManualLayout(true, List.copyOf(anchors), List.copyOf(connections)),
            defaults.internalBiomes(), defaults.roomTypes(), defaults.pathTypes(),
            defaults.decorations()
        );
        List<Entrance> entrances = List.of(
            new Entrance("entry", caveId, entry, entry, settings),
            new Entrance("exit", caveId, exit, exit, settings)
        );
        List<BlockPos> mainRoomPositions = anchors.stream()
            .filter(anchor -> !anchor.kind().equals("branch"))
            .map(anchor -> new BlockPos(anchor.x(), anchor.y() + 1, anchor.z())
                .subtract(origin))
            .toList();
        List<BlockPos> branchRoomPositions = anchors.stream()
            .filter(anchor -> anchor.kind().equals("branch"))
            .map(anchor -> new BlockPos(anchor.x(), anchor.y() + 1, anchor.z())
                .subtract(origin))
            .toList();
        return new InstancePlan(
            entrances, settings,
            entry.toBlockPos().subtract(origin).above(),
            exit.toBlockPos().subtract(origin).above(),
            mainRoomPositions, branchRoomPositions
        );
    }

    private static void generateCave(
        ServerLevel level, String caveId, long seed, List<Entrance> entrances, Settings settings
    ) {
        CaveStyleProfile style = caveStyleProfile(settings.style());
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
                    double tunnelRadius = (cavePath.width() > 0
                        ? Math.max(2.0D, cavePath.width() * 0.62D)
                        : settings.minimumTunnelRadius()
                            + (settings.maximumTunnelRadius() - settings.minimumTunnelRadius())
                                * (0.5D + signedNoise(seed, pathIndex, edge, sample.floorY()) * 0.35D))
                        * style.tunnelScale();
                    blobs.add(new Blob(
                        sample.x(), sample.floorY() + 5.0D, sample.z(),
                        tunnelRadius,
                        Math.max(4.5D, tunnelRadius * 0.88D * style.tunnelHeightScale()),
                        tunnelRadius
                    ));
                }
            }
            pathIndex++;
        }

        for (Blob blob : blobs) {
            fillRockShell(
                level, blob, seed,
                Math.min(1.0D, settings.surfaceRoughness() * style.roughnessScale()),
                settings.style()
            );
        }
        for (Blob blob : blobs) {
            carveInterior(
                level, blob, seed,
                Math.min(1.0D, settings.surfaceRoughness() * style.roughnessScale())
            );
        }

        int stairs = 0;
        pathIndex = 0;
        for (CavePath path : layout.paths()) {
            stairs += placeWalkablePath(
                level, path.points(), seed, pathIndex++, path.width(), path.pathType(),
                settings.requiresFlash(), settings.style()
            );
            if (path.kind().equals("bridge")) {
                placeNaturalBridge(
                    level, path.points(), seed, path.width(), settings.requiresFlash(),
                    settings.style()
                );
            }
        }
        for (Room room : layout.rooms()) {
            prepareRoomFloor(level, room, settings.style());
        }
        decorateRooms(level, layout, seed, settings);
        buildEntranceLandmarks(level, layout, entrances, settings.requiresFlash());
        int flooded = floodSubmergedCave(
            level, blobs, settings.waterLevel(), settings.waterDepth(), settings.style()
        );
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
        CaveStyleProfile style = caveStyleProfile(settings.style());
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
                z += (Math.sin(index * 1.37D) * 27.0D + random.nextInt(-12, 13))
                    * style.wanderScale();
                int verticalAmplitude = Math.max(
                    4, (int) Math.round(settings.verticalRange() / 2.0D * style.verticalScale())
                );
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
            radiusX *= style.radiusXScale();
            radiusZ *= style.radiusZScale();
            height *= style.heightScale();
            RoomType roomType = index == 0 || index == roomCount - 1
                ? roomType(settings, "rock") : pickRoomType(random, settings.roomTypes());
            Room room = new Room(
                x, floorY, z,
                radiusX * roomType.radiusScale(), height * roomType.heightScale(),
                radiusZ * roomType.radiusScale(), grandRoom ? "grand" : "main", roomType
            );
            mainRooms.add(room);
            mainPath.add(room.pathPoint());
        }

        List<Room> rooms = new ArrayList<>(mainRooms);
        List<CavePath> paths = new ArrayList<>();
        PathType mainPathType = pickPathType(random, settings.pathTypes());
        paths.add(new CavePath(
            List.copyOf(mainPath), "main", mainPathType.width(), mainPathType
        ));
        for (int branchIndex = 0; branchIndex < settings.branchCount(); branchIndex++) {
            int rootIndex = 1 + (branchIndex + 1) * (roomCount - 2) / (settings.branchCount() + 1);
            Room root = mainRooms.get(Math.max(1, Math.min(roomCount - 2, rootIndex)));
            int direction = branchIndex % 2 == 0 ? -1 : 1;
            double endX = root.x() + random.nextInt(-24, 25) * style.wanderScale();
            double endZ = root.z() + direction * (58 + random.nextInt(29))
                * style.branchScale();
            int endY = Math.max(30, Math.min(
                76,
                root.floorY() + (int) Math.round(
                    random.nextInt(-settings.verticalRange() / 2, settings.verticalRange() / 2 + 1)
                        * style.verticalScale()
                )
            ));
            RoomType roomType = pickRoomType(random, settings.roomTypes());
            double branchRadiusX = randomBetween(random, settings.minimumRoomRadius(), settings.maximumRoomRadius());
            double branchRadiusZ = randomBetween(random, settings.minimumRoomRadius(), settings.maximumRoomRadius());
            Room branchRoom = new Room(
                endX, endY, endZ,
                branchRadiusX * roomType.radiusScale() * style.radiusXScale(),
                randomBetween(
                    random,
                    Math.max(9.0D, settings.minimumRoomRadius() * 0.7D),
                    Math.max(11.0D, settings.maximumRoomRadius() * 0.72D)
                ) * roomType.heightScale() * style.heightScale(),
                branchRadiusZ * roomType.radiusScale() * style.radiusZScale(),
                "branch", roomType
            );
            rooms.add(branchRoom);
            PathPoint middle = new PathPoint(
                (root.x() + endX) * 0.5D + random.nextInt(-10, 11),
                (root.floorY() + endY) / 2,
                (root.z() + endZ) * 0.5D
            );
            PathType branchPathType = pickPathType(random, settings.pathTypes());
            paths.add(new CavePath(
                List.of(root.pathPoint(), middle, branchRoom.pathPoint()),
                "branch", branchPathType.width(), branchPathType
            ));
        }

        if (roomCount >= 5 && random.nextDouble() <= settings.loopChance()) {
            Room loopFrom = mainRooms.get(Math.max(1, roomCount / 3));
            Room loopTo = mainRooms.get(Math.min(roomCount - 2, roomCount * 2 / 3));
            int loopY = Math.max(34, Math.min(74, (loopFrom.floorY() + loopTo.floorY()) / 2 + 7));
            PathType loopPathType = pickPathType(random, settings.pathTypes());
            paths.add(new CavePath(List.of(
                loopFrom.pathPoint(),
                new PathPoint(
                    (loopFrom.x() + loopTo.x()) * 0.5D,
                    loopY,
                    Math.min(loopFrom.z(), loopTo.z()) - 58.0D * style.wanderScale()
                ),
                loopTo.pathPoint()
            ), "loop", loopPathType.width(), loopPathType));
        }
        if (settings.elevatedCrossing() && roomCount >= 5) {
            int grandIndex = roomCount / 2;
            Room grand = mainRooms.get(grandIndex);
            Room bridgeFrom = mainRooms.get(Math.min(roomCount - 2, grandIndex + 1));
            Room bridgeTo = mainRooms.get(Math.min(roomCount - 1, grandIndex + 2));
            int bridgeY = Math.min(76, grand.floorY() + Math.max(10, settings.bridgeClearance()));
            double span = Math.max(16.0D, grand.radiusZ() * 0.72D);
            PathType bridgePathType = pathType(settings, "rugged");
            paths.add(new CavePath(List.of(
                bridgeFrom.pathPoint(),
                new PathPoint(grand.x() + grand.radiusX() * 0.62D, bridgeY, grand.z() - span),
                new PathPoint(grand.x(), bridgeY, grand.z()),
                new PathPoint(grand.x() - grand.radiusX() * 0.62D, bridgeY, grand.z() + span),
                bridgeTo.pathPoint()
            ), "bridge", 5, bridgePathType));
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
            RoomType roomType = roomType(
                settings,
                anchor.roomType().isBlank() ? legacyRoomType(anchor.kind()) : anchor.roomType()
            );
            rooms.add(new Room(
                anchor.x(), anchor.y(), anchor.z(), anchor.radiusX(), anchor.height(), anchor.radiusZ(),
                switch (anchor.kind()) {
                    case "grand" -> "grand";
                    case "junction" -> "main";
                    default -> "branch";
                },
                roomType
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
            PathType pathType = pathType(
                settings,
                connection.pathType().isBlank() ? "natural" : connection.pathType()
            );
            paths.add(new CavePath(
                List.of(from, to), connection.kind(), connection.width(), pathType
            ));
        }
        return new CaveLayout(List.copyOf(rooms), List.copyOf(paths));
    }

    private static void fillRockShell(
        ServerLevel level, Blob blob, long seed, double roughness, String style
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
                    level.setBlock(new BlockPos(x, y, z), caveRock(seed, x, y, z, style), 2);
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
        ServerLevel level, List<PathPoint> path, long seed, int pathIndex,
        int configuredWidth, PathType pathType, boolean requiresFlash, String style
    ) {
        int stairs = 0;
        int halfWidth = configuredWidth > 0 ? Math.max(1, configuredWidth / 2) : 2;
        int lightSampleIndex = 0;
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
                            pathFloor(
                                seed, x + offsetX, floorY, z + offsetZ, pathType.floor(), style
                            ), 2
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
                    BlockState stair = caveStair(style)
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
                if (!requiresFlash && lightSampleIndex % 10 == 2) {
                    int ceilingY = findCeiling(level, x, floorY + 3, z);
                    if (ceilingY != Integer.MIN_VALUE) {
                        level.setBlock(
                            new BlockPos(x, ceilingY, z),
                            caveLight(style), 2
                        );
                    } else {
                        level.setBlock(
                            new BlockPos(x, floorY, z),
                            caveLight(style), 2
                        );
                    }
                }
                lightSampleIndex++;
                previous = sample;
            }
        }
        return stairs;
    }

    private static void placeNaturalBridge(
        ServerLevel level, List<PathPoint> path, long seed,
        int configuredWidth, boolean requiresFlash, String style
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
                    BlockState bridgeRock = caveBridgeBlock(
                        style, Math.floorMod(sampleIndex + lateral, 5) == 0
                    );
                    level.setBlock(new BlockPos(x + sideX * lateral, y, z + sideZ * lateral), bridgeRock, 2);
                    for (int clearY = y + 1; clearY <= y + 5; clearY++) {
                        level.setBlock(new BlockPos(x + sideX * lateral, clearY, z + sideZ * lateral), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                if (sampleIndex % 9 == 4) {
                    for (int lateral : new int[] {-halfWidth - 1, halfWidth + 1}) {
                        level.setBlock(
                            new BlockPos(x + sideX * lateral, y + 1, z + sideZ * lateral),
                            caveBridgeWall(style), 2
                        );
                    }
                }
                if (!requiresFlash && sampleIndex % 10 == 5) {
                    for (int lateral : new int[] {-halfWidth, halfWidth}) {
                        level.setBlock(
                            new BlockPos(x + sideX * lateral, y + 1, z + sideZ * lateral),
                            caveLight(style), 2
                        );
                    }
                }
                sampleIndex++;
            }
        }
    }

    private static void prepareRoomFloor(ServerLevel level, Room room, String style) {
        int centerX = (int) Math.round(room.x());
        int centerZ = (int) Math.round(room.z());
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                level.setBlock(
                    new BlockPos(x, room.floorY(), z), caveFloor(style), 2
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
            String decoration = room.roomType().decoration();
            double typeDensity = switch (decoration) {
                case "dripstone" -> 2.8D;
                case "lush" -> 1.1D;
                case "crystal" -> 0.8D;
                case "ice" -> 0.95D;
                case "lava" -> 1.2D;
                default -> 0.55D;
            };
            int clusters = Math.max(1, (int) Math.round(
                (room.radiusX() + room.radiusZ()) / 12.0D
                    * settings.decorations().clusterDensity() * typeDensity
            ));
            for (int index = 0; index < clusters; index++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double distance = 0.58D + random.nextDouble() * 0.24D;
                int x = (int) Math.round(room.x() + Math.cos(angle) * room.radiusX() * distance);
                int z = (int) Math.round(room.z() + Math.sin(angle) * room.radiusZ() * distance);
                if (isNearPath(
                    x, z, layout.paths(), settings.decorations().routeClearance()
                )) {
                    continue;
                }
                int radius = random.nextInt(
                    settings.decorations().minimumPatchRadius(),
                    settings.decorations().maximumPatchRadius() + 1
                );
                switch (decoration) {
                    case "dripstone" -> placeDripstoneCluster(
                        level, layout, room, x, z, radius + 1, random, settings.decorations()
                    );
                    case "crystal" -> placeSurfacePatch(
                        level, layout, room, x, z, radius, random,
                        Blocks.CALCITE.defaultBlockState(), Blocks.AMETHYST_BLOCK.defaultBlockState(),
                        true, settings.decorations().routeClearance()
                    );
                    case "lush" -> placeSurfacePatch(
                        level, layout, room, x, z, radius, random,
                        Blocks.MOSS_BLOCK.defaultBlockState(), Blocks.CLAY.defaultBlockState(),
                        false, settings.decorations().routeClearance()
                    );
                    case "ice" -> placeSurfacePatch(
                        level, layout, room, x, z, radius, random,
                        Blocks.PACKED_ICE.defaultBlockState(), Blocks.BLUE_ICE.defaultBlockState(),
                        true, settings.decorations().routeClearance()
                    );
                    case "lava" -> placeSurfacePatch(
                        level, layout, room, x, z, radius, random,
                        Blocks.BASALT.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(),
                        true, settings.decorations().routeClearance()
                    );
                    default -> placeSurfacePatch(
                        level, layout, room, x, z, radius, random,
                        Blocks.TUFF.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(),
                        true, settings.decorations().routeClearance()
                    );
                }
            }
            if (!settings.requiresFlash()) {
                int lightCount = Math.max(
                    4, (int) Math.ceil((room.radiusX() + room.radiusZ()) / 10.0D)
                );
                for (int lightIndex = 0; lightIndex < lightCount; lightIndex++) {
                    double angle = Math.PI * 2.0D * lightIndex / lightCount + 0.35D;
                    int lightX = (int) Math.round(room.x() + Math.cos(angle) * room.radiusX() * 0.42D);
                    int lightZ = (int) Math.round(room.z() + Math.sin(angle) * room.radiusZ() * 0.42D);
                    int ceilingY = findCeiling(level, lightX, room.floorY() + 4, lightZ);
                    if (ceilingY != Integer.MIN_VALUE) {
                        BlockState light = caveLight(decoration);
                        level.setBlock(
                            new BlockPos(lightX, ceilingY, lightZ), light, 2
                        );
                        int localFloorY = findNaturalFloor(
                            level, lightX, room.floorY(), lightZ
                        );
                        if (localFloorY != Integer.MIN_VALUE) {
                            level.setBlock(
                                new BlockPos(lightX, localFloorY, lightZ), light, 2
                            );
                        }
                    }
                }
                int centerFloorY = findNaturalFloor(
                    level, (int) Math.round(room.x()), room.floorY(),
                    (int) Math.round(room.z())
                );
                if (centerFloorY != Integer.MIN_VALUE) {
                    level.setBlock(
                        new BlockPos(
                            (int) Math.round(room.x()), centerFloorY,
                            (int) Math.round(room.z())
                        ),
                        caveLight(decoration),
                        2
                    );
                }
            }
        }
    }

    private static void placeDripstoneCluster(
        ServerLevel level, CaveLayout layout, Room room, int centerX, int centerZ,
        int radius, Random random, DecorationSettings settings
    ) {
        placeSurfacePatch(
            level, layout, room, centerX, centerZ, radius, random,
            Blocks.DRIPSTONE_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(),
            true, settings.routeClearance()
        );
        int formations = Math.max(4, radius * 3 + random.nextInt(radius + 1));
        for (int index = 0; index < formations; index++) {
            int x = centerX + random.nextInt(-radius, radius + 1);
            int z = centerZ + random.nextInt(-radius, radius + 1);
            if (isNearPath(x, z, layout.paths(), settings.routeClearance())) {
                continue;
            }
            int floorY = findNaturalFloor(level, x, room.floorY(), z);
            int ceilingY = findCeiling(level, x, room.floorY() + 3, z);
            if (floorY == Integer.MIN_VALUE || ceilingY == Integer.MIN_VALUE
                || ceilingY - floorY < 5) {
                continue;
            }
            int maximumLength = Math.min(
                settings.maximumDripstoneLength(), Math.max(1, (ceilingY - floorY - 2) / 2)
            );
            int minimumLength = Math.min(settings.minimumDripstoneLength(), maximumLength);
            int ceilingLength = random.nextInt(minimumLength, maximumLength + 1);
            placePointedDripstone(level, x, ceilingY - 1, z, Direction.DOWN, ceilingLength);
            if (random.nextDouble() < 0.42D) {
                int floorLength = random.nextInt(minimumLength, maximumLength + 1);
                placePointedDripstone(level, x, floorY + 1, z, Direction.UP, floorLength);
            }
        }
    }

    private static void placeSurfacePatch(
        ServerLevel level, CaveLayout layout, Room room, int centerX, int centerZ,
        int radius, Random random, BlockState primary, BlockState accent,
        boolean includeCeiling, int routeClearance
    ) {
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
                if (distance > radius + random.nextDouble() * 0.65D - 0.3D) {
                    continue;
                }
                int x = centerX + offsetX;
                int z = centerZ + offsetZ;
                if (isNearPath(x, z, layout.paths(), routeClearance)) {
                    continue;
                }
                BlockState patchBlock = random.nextDouble() < 0.18D ? accent : primary;
                int floorY = findNaturalFloor(level, x, room.floorY(), z);
                if (floorY != Integer.MIN_VALUE) {
                    level.setBlock(new BlockPos(x, floorY, z), patchBlock, 2);
                }
                if (includeCeiling && random.nextDouble() < 0.68D) {
                    int ceilingY = findCeiling(level, x, room.floorY() + 3, z);
                    if (ceilingY != Integer.MIN_VALUE) {
                        level.setBlock(new BlockPos(x, ceilingY, z), patchBlock, 2);
                    }
                }
            }
        }
    }

    private static void placePointedDripstone(
        ServerLevel level, int x, int startY, int z, Direction direction, int length
    ) {
        for (int index = 0; index < length; index++) {
            int y = startY + direction.getStepY() * index;
            BlockPos position = new BlockPos(x, y, z);
            if (!level.getBlockState(position).isAir()) {
                break;
            }
            DripstoneThickness thickness;
            if (length == 1 || index == length - 1) {
                thickness = DripstoneThickness.TIP;
            } else if (index == length - 2) {
                thickness = DripstoneThickness.FRUSTUM;
            } else if (index == 0 && length >= 4) {
                thickness = DripstoneThickness.BASE;
            } else {
                thickness = DripstoneThickness.MIDDLE;
            }
            level.setBlock(
                position,
                Blocks.POINTED_DRIPSTONE.defaultBlockState()
                    .setValue(PointedDripstoneBlock.TIP_DIRECTION, direction)
                    .setValue(PointedDripstoneBlock.THICKNESS, thickness),
                2
            );
        }
    }

    private static boolean isNearPath(
        int x, int z, List<CavePath> paths, double clearance
    ) {
        double maximumDistanceSquared = clearance * clearance;
        for (CavePath path : paths) {
            for (int index = 0; index < path.points().size() - 1; index++) {
                PathPoint from = path.points().get(index);
                PathPoint to = path.points().get(index + 1);
                double dx = to.x() - from.x();
                double dz = to.z() - from.z();
                double lengthSquared = dx * dx + dz * dz;
                double progress = lengthSquared <= 0.0001D ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D,
                        ((x - from.x()) * dx + (z - from.z()) * dz) / lengthSquared
                    ));
                double nearestX = from.x() + dx * progress;
                double nearestZ = from.z() + dz * progress;
                double distanceX = x - nearestX;
                double distanceZ = z - nearestZ;
                if (distanceX * distanceX + distanceZ * distanceZ <= maximumDistanceSquared) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int floodSubmergedCave(
        ServerLevel level, List<Blob> blobs, int waterLevel, int waterDepth, String style
    ) {
        int flooded = 0;
        int waterFloorY = waterLevel - Math.max(1, waterDepth);
        BlockState liquid = style.equals("lava")
            ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
        for (Blob blob : blobs) {
            int minX = (int) Math.floor(blob.x() - blob.radiusX());
            int maxX = (int) Math.ceil(blob.x() + blob.radiusX());
            int minY = Math.max(
                waterLevel - Math.max(1, waterDepth) + 1,
                (int) Math.floor(blob.y() - blob.radiusY())
            );
            int maxY = Math.min(waterLevel, (int) Math.ceil(blob.y() + blob.radiusY()));
            int minZ = (int) Math.floor(blob.z() - blob.radiusZ());
            int maxZ = (int) Math.ceil(blob.z() + blob.radiusZ());
            for (int x = minX; x <= maxX; x++) {
                if (waterFloorY >= (int) Math.floor(blob.y() - blob.radiusY())
                    && waterFloorY <= (int) Math.ceil(blob.y() + blob.radiusY())) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (ellipsoidDistance(
                            blob, x, waterFloorY, z,
                            blob.radiusX(), blob.radiusY(), blob.radiusZ()
                        ) <= 0.96D) {
                            BlockPos floor = new BlockPos(x, waterFloorY, z);
                            if (level.getBlockState(floor).isAir()) {
                                level.setBlock(floor, caveFloor(style), 2);
                            }
                        }
                    }
                }
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (ellipsoidDistance(
                            blob, x, y, z, blob.radiusX(), blob.radiusY(), blob.radiusZ()
                        ) > 0.96D) {
                            continue;
                        }
                        BlockPos position = new BlockPos(x, y, z);
                        if (level.getBlockState(position).isAir()) {
                            level.setBlock(position, liquid, 2);
                            flooded++;
                        }
                    }
                }
            }
        }
        return flooded;
    }

    private static void buildEntranceLandmarks(
        ServerLevel level, CaveLayout layout, List<Entrance> entrances,
        boolean requiresFlash
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
                    int x = centerX + sideX * lateral;
                    int z = centerZ + sideZ * lateral;
                    BlockPos position = new BlockPos(x, floorY + vertical, z);
                    BlockState existing = level.getBlockState(position);
                    if (!existing.is(Blocks.QUARTZ_PILLAR)
                        && !existing.is(Blocks.SMOOTH_QUARTZ)
                        && !existing.is(Blocks.QUARTZ_BLOCK)
                        && !existing.is(Blocks.QUARTZ_STAIRS)
                        && !existing.is(Blocks.SEA_LANTERN)) {
                        continue;
                    }
                    level.setBlock(
                        position,
                        vertical == 0
                            ? Blocks.COBBLED_DEEPSLATE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(),
                        2
                    );
                }
            }
            for (int lateral = -3; lateral <= 3; lateral++) {
                for (int vertical = 1; vertical <= 5; vertical++) {
                    int x = centerX + sideX * lateral;
                    int z = centerZ + sideZ * lateral;
                    level.setBlock(new BlockPos(x, floorY + vertical, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            openEntranceToOutside(
                level, centerX, centerZ, inward, sideX, sideZ
            );
            if (!requiresFlash) {
                for (int depth : new int[] {4, 10, 16}) {
                    int x = centerX + inward.getStepX() * depth;
                    int z = centerZ + inward.getStepZ() * depth;
                    level.setBlock(
                        new BlockPos(x, floorY, z), Blocks.GLOWSTONE.defaultBlockState(), 2
                    );
                }
            }
        }
    }

    /** Cuts through the complete cave shell behind an exit and places an
     * invisible full-height safety plane on the cave side of the opening. */
    private static void openEntranceToOutside(
        ServerLevel level, int centerX, int centerZ,
        Direction inward, int sideX, int sideZ
    ) {
        int outwardX = -inward.getStepX();
        int outwardZ = -inward.getStepZ();
        int cutDepth = SHELL_THICKNESS + 8;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int depth = 1; depth <= cutDepth; depth++) {
            for (int lateral = -5; lateral <= 5; lateral++) {
                int x = centerX + outwardX * depth + sideX * lateral;
                int z = centerZ + outwardZ * depth + sideZ * lateral;
                for (int y = minY; y <= maxY; y++) {
                    level.setBlock(
                        position.set(x, y, z), Blocks.AIR.defaultBlockState(), 2
                    );
                }
            }
        }
        for (int lateral = -5; lateral <= 5; lateral++) {
            int x = centerX + outwardX + sideX * lateral;
            int z = centerZ + outwardZ + sideZ * lateral;
            for (int y = minY; y <= maxY; y++) {
                level.setBlock(
                    position.set(x, y, z), Blocks.BARRIER.defaultBlockState(), 2
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
                : room.roomType().decoration().equals("crystal")
                    || room.roomType().decoration().equals("lush")
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
                    loadChunksForBiomeBox(level, x, z, sectionMaxX, sectionMaxZ);
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

    private static void loadChunksForBiomeBox(
        ServerLevel level, int minX, int minZ, int maxX, int maxZ
    ) {
        int minChunkX = minX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkX = maxX >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
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

    private static BlockState caveRock(long seed, int x, int y, int z, String style) {
        double value = signedNoise(seed ^ 0xA11CE, x >> 2, y >> 2, z >> 2);
        if (style.equals("ice")) {
            if (value > 0.72D) return Blocks.BLUE_ICE.defaultBlockState();
            if (value < -0.58D) return Blocks.CALCITE.defaultBlockState();
            return Blocks.PACKED_ICE.defaultBlockState();
        }
        if (style.equals("lava")) {
            if (value > 0.72D) return Blocks.MAGMA_BLOCK.defaultBlockState();
            if (value < -0.58D) return Blocks.BLACKSTONE.defaultBlockState();
            return Blocks.BASALT.defaultBlockState();
        }
        if (style.equals("crystal")) {
            if (value > 0.78D) return Blocks.AMETHYST_BLOCK.defaultBlockState();
            if (value < -0.68D) return Blocks.SMOOTH_BASALT.defaultBlockState();
            return Blocks.CALCITE.defaultBlockState();
        }
        if (style.equals("lush")) {
            if (value > 0.76D) return Blocks.MOSS_BLOCK.defaultBlockState();
            if (value < -0.7D) return Blocks.ROOTED_DIRT.defaultBlockState();
        }
        if (style.equals("dripstone")) {
            if (value > 0.28D) return Blocks.DRIPSTONE_BLOCK.defaultBlockState();
            if (value < -0.72D) return Blocks.TUFF.defaultBlockState();
            return Blocks.GRANITE.defaultBlockState();
        }
        if (value > 0.84D) return Blocks.TUFF.defaultBlockState();
        if (value < -0.84D) return Blocks.ANDESITE.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState caveFloor(String style) {
        return switch (style) {
            case "ice" -> Blocks.PACKED_ICE.defaultBlockState();
            case "lava" -> Blocks.BASALT.defaultBlockState();
            case "crystal" -> Blocks.CALCITE.defaultBlockState();
            case "lush" -> Blocks.MOSS_BLOCK.defaultBlockState();
            case "dripstone" -> Blocks.GRANITE.defaultBlockState();
            default -> Blocks.DEEPSLATE.defaultBlockState();
        };
    }

    private static BlockState caveLight(String style) {
        return switch (style) {
            case "crystal" -> Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState();
            case "ice" -> Blocks.SEA_LANTERN.defaultBlockState();
            case "lava" -> Blocks.GLOWSTONE.defaultBlockState();
            default -> Blocks.SHROOMLIGHT.defaultBlockState();
        };
    }

    private static BlockState caveStair(String style) {
        return switch (style) {
            case "dripstone" -> Blocks.POLISHED_GRANITE_STAIRS.defaultBlockState();
            case "ice", "crystal" -> Blocks.QUARTZ_STAIRS.defaultBlockState();
            case "lava" -> Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState();
            case "lush" -> Blocks.MOSSY_COBBLESTONE_STAIRS.defaultBlockState();
            default -> Blocks.COBBLED_DEEPSLATE_STAIRS.defaultBlockState();
        };
    }

    private static BlockState caveBridgeBlock(String style, boolean accent) {
        return switch (style) {
            case "dripstone" -> accent
                ? Blocks.DRIPSTONE_BLOCK.defaultBlockState() : Blocks.GRANITE.defaultBlockState();
            case "ice" -> accent
                ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
            case "crystal" -> accent
                ? Blocks.AMETHYST_BLOCK.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
            case "lush" -> accent
                ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case "lava" -> accent
                ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.POLISHED_BLACKSTONE.defaultBlockState();
            default -> accent
                ? Blocks.TUFF.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        };
    }

    private static BlockState caveBridgeWall(String style) {
        return switch (style) {
            case "dripstone" -> Blocks.GRANITE_WALL.defaultBlockState();
            case "ice", "crystal" -> Blocks.DIORITE_WALL.defaultBlockState();
            case "lush" -> Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState();
            case "lava" -> Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState();
            default -> Blocks.COBBLED_DEEPSLATE_WALL.defaultBlockState();
        };
    }

    private static CaveStyleProfile caveStyleProfile(String style) {
        return switch (style) {
            case "dripstone" -> new CaveStyleProfile(
                0.82D, 0.88D, 1.35D, 0.85D, 1.25D, 0.9D, 0.9D, 1.2D, 1.25D
            );
            case "crystal" -> new CaveStyleProfile(
                1.18D, 0.76D, 1.05D, 1.3D, 0.9D, 1.15D, 0.8D, 1.0D, 1.4D
            );
            case "lush" -> new CaveStyleProfile(
                1.28D, 1.2D, 0.72D, 0.72D, 0.55D, 1.1D, 1.2D, 0.78D, 0.7D
            );
            case "ice" -> new CaveStyleProfile(
                0.72D, 1.45D, 0.82D, 1.55D, 0.7D, 1.3D, 0.85D, 0.82D, 0.45D
            );
            case "lava" -> new CaveStyleProfile(
                1.12D, 0.9D, 1.45D, 1.1D, 1.5D, 0.95D, 1.15D, 1.35D, 1.55D
            );
            default -> new CaveStyleProfile(
                1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D
            );
        };
    }

    private static BlockState naturalFloor(long seed, int x, int y, int z, String style) {
        double noise = NaturalSurfaceNoise.sample2D(
            seed ^ 0xF100D ^ (long) y * 42317861L, x, z
        );
        return switch (style) {
            case "dripstone" -> noise > 0.25D
                ? Blocks.GRANITE.defaultBlockState() : Blocks.DRIPSTONE_BLOCK.defaultBlockState();
            case "ice" -> noise > 0.45D
                ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
            case "crystal" -> noise > 0.5D
                ? Blocks.AMETHYST_BLOCK.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
            case "lush" -> noise > 0.35D
                ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case "lava" -> noise > 0.5D
                ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
            default -> noise > 0.50D
                ? Blocks.TUFF.defaultBlockState() : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        };
    }

    private static BlockState pathFloor(
        long seed, int x, int y, int z, String floor, String style
    ) {
        double noise = NaturalSurfaceNoise.sample2D(
            seed ^ 0xBADC0DEL ^ (long) y * 42317861L, x, z
        );
        if (!style.equals("rock")) {
            return naturalFloor(seed, x, y, z, style);
        }
        return switch (floor) {
            case "rugged" -> noise > 0.45D
                ? Blocks.COBBLESTONE.defaultBlockState()
                : noise < -0.35D
                    ? Blocks.TUFF.defaultBlockState()
                    : Blocks.COBBLED_DEEPSLATE.defaultBlockState();
            case "worked" -> noise > 0.55D
                ? Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState()
                : noise < -0.45D
                    ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                    : Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            default -> naturalFloor(seed, x, y, z, style);
        };
    }

    private static RoomType pickRoomType(Random random, List<RoomType> types) {
        int totalWeight = types.stream().mapToInt(RoomType::weight).sum();
        int selected = random.nextInt(Math.max(1, totalWeight));
        for (RoomType type : types) {
            selected -= type.weight();
            if (selected < 0) {
                return type;
            }
        }
        return types.getFirst();
    }

    private static PathType pickPathType(Random random, List<PathType> types) {
        int totalWeight = types.stream().mapToInt(PathType::weight).sum();
        int selected = random.nextInt(Math.max(1, totalWeight));
        for (PathType type : types) {
            selected -= type.weight();
            if (selected < 0) {
                return type;
            }
        }
        return types.getFirst();
    }

    private static RoomType roomType(Settings settings, String id) {
        return settings.roomTypes().stream()
            .filter(type -> type.id().equals(id))
            .findFirst()
            .orElseGet(() -> settings.roomTypes().getFirst());
    }

    private static PathType pathType(Settings settings, String id) {
        return settings.pathTypes().stream()
            .filter(type -> type.id().equals(id))
            .findFirst()
            .orElseGet(() -> settings.pathTypes().getFirst());
    }

    private static String legacyRoomType(String kind) {
        return switch (kind) {
            case "landmark" -> "crystal";
            case "grand" -> "dripstone";
            default -> "rock";
        };
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

    record InstanceResult(
        BlockPos entryPosition,
        BlockPos exitPosition,
        List<BlockPos> mainRoomPositions,
        List<BlockPos> branchRoomPositions
    ) {}

    record InstancePlan(
        List<Entrance> entrances,
        Settings settings,
        BlockPos entryPosition,
        BlockPos exitPosition,
        List<BlockPos> mainRoomPositions,
        List<BlockPos> branchRoomPositions
    ) {}

    record Settings(
        long seedSalt,
        String style,
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
        int waterDepth,
        double grandRoomScale,
        boolean elevatedCrossing,
        int bridgeClearance,
        boolean requiresFlash,
        ManualLayout manualLayout,
        List<String> internalBiomes,
        List<RoomType> roomTypes,
        List<PathType> pathTypes,
        DecorationSettings decorations
    ) {
        static Settings defaults() {
            return defaults(false);
        }

        static Settings defaults(boolean requiresFlash) {
            return new Settings(
                0L, "rock", 7, 4, 0.35D, 28, 10.0D, 28.0D, 3.0D, 7.0D, 0.18D, 38, 8,
                1.65D, false, 13, requiresFlash,
                ManualLayout.disabled(),
                List.of("minecraft:dripstone_caves"),
                List.of(
                    new RoomType("rock", 45, "rock", 1.0D, 1.0D),
                    new RoomType("dripstone", 30, "dripstone", 0.95D, 1.18D),
                    new RoomType("crystal", 15, "crystal", 1.0D, 1.05D),
                    new RoomType("lush", 10, "lush", 1.12D, 0.88D)
                ),
                List.of(
                    new PathType("natural", 70, 5, "natural"),
                    new PathType("rugged", 20, 3, "rugged"),
                    new PathType("worked", 10, 5, "worked")
                ),
                new DecorationSettings(1.0D, 2, 4, 1, 4, 4)
            );
        }
    }

    private record CaveLayout(List<Room> rooms, List<CavePath> paths) {}

    private record CaveStyleProfile(
        double radiusXScale, double radiusZScale, double heightScale,
        double wanderScale, double verticalScale, double branchScale,
        double tunnelScale, double tunnelHeightScale, double roughnessScale
    ) {}

    record ManualLayout(boolean enabled, List<ManualAnchor> anchors, List<ManualConnection> connections) {
        static ManualLayout disabled() {
            return new ManualLayout(false, List.of(), List.of());
        }
    }

    record ManualAnchor(
        String id, String kind, int x, int y, int z, double radiusX, double radiusZ,
        double height, String roomType
    ) {}

    record ManualConnection(
        String id, String from, String to, String kind, int width, String pathType
    ) {}

    record RoomType(
        String id, int weight, String decoration, double radiusScale, double heightScale
    ) {}

    record PathType(String id, int weight, int width, String floor) {}

    record DecorationSettings(
        double clusterDensity,
        int minimumPatchRadius,
        int maximumPatchRadius,
        int minimumDripstoneLength,
        int maximumDripstoneLength,
        int routeClearance
    ) {}

    private record CavePath(
        List<PathPoint> points, String kind, int width, PathType pathType
    ) {}

    private record Room(
        double x,
        int floorY,
        double z,
        double radiusX,
        double height,
        double radiusZ,
        String kind,
        RoomType roomType
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
