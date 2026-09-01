package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Places reusable NBT world objects such as villain bases and legendary sites. */
final class WorldStructureSystem {
    private static final Logger LOGGER = LogUtils.getLogger();

    private WorldStructureSystem() {}

    static List<WorldStructure> parse(JsonArray objects) {
        List<WorldStructure> structures = new ArrayList<>();
        for (JsonElement element : objects) {
            JsonObject value = element.getAsJsonObject();
            String type = requiredString(value, "type");
            if (!List.of("structure", "villain_base", "legendary_site").contains(type)) {
                continue;
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            if (anchor == null) {
                throw new IllegalStateException("World structure anchor is missing");
            }
            String resource = requiredString(value, "resource");
            if (ResourceLocation.tryParse(resource) == null) {
                throw new IllegalStateException("Invalid world structure resource: " + resource);
            }
            List<DungeonConnection> connections = new ArrayList<>();
            JsonObject properties = value.has("properties")
                && value.get("properties").isJsonObject()
                ? value.getAsJsonObject("properties") : new JsonObject();
            String placementAnchor = properties.has("center_placement")
                && properties.get("center_placement").getAsBoolean()
                ? "center"
                : properties.has("placement_anchor")
                    ? requiredString(properties, "placement_anchor") : "center";
            if (!List.of("center", "road_anchor", "door").contains(placementAnchor)) {
                throw new IllegalStateException(
                    "Invalid world structure placement anchor: " + placementAnchor
                );
            }
            if (value.has("connections")) {
                for (JsonElement connectionElement : value.getAsJsonArray("connections")) {
                    JsonObject connection = connectionElement.getAsJsonObject();
                    JsonObject target = connection.getAsJsonObject("target");
                    if (target == null || !"dungeon".equals(requiredString(target, "type"))) {
                        continue;
                    }
                    String from = requiredString(connection, "from");
                    if (!from.startsWith("structure:") || from.length() == "structure:".length()) {
                        throw new IllegalStateException(
                            "Dungeon structure connection requires structure:<anchor>: " + from
                        );
                    }
                    connections.add(new DungeonConnection(
                        from.substring("structure:".length()),
                        requiredString(target, "entrance_id")
                    ));
                }
            }
            structures.add(new WorldStructure(
                requiredString(value, "id"), type,
                new HexCoord(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                resource,
                value.has("rotation") ? value.get("rotation").getAsInt() : 0,
                placementAnchor,
                List.copyOf(connections)
            ));
        }
        return List.copyOf(structures);
    }

    static void placeAll(ServerLevel level, HexWorldPlan world) {
        for (WorldStructure structure : world.worldStructures()) {
            place(level, world, structure);
        }
    }

    private static void place(
        ServerLevel level, HexWorldPlan world, WorldStructure configured
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(configured.structure());
        var optional = structureId == null
            ? java.util.Optional.<StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (optional.isEmpty()) {
            throw new IllegalStateException(
                "World structure NBT is missing: " + configured.structure()
            );
        }
        StructureTemplate template = optional.orElseThrow();
        CobbleventureBootstrap.Point center = world.grid().worldCenter(configured.anchor());
        int effectiveRotation = configured.rotation();
        Rotation rotation = rotation(effectiveRotation);
        Vec3i rotatedSize = template.getSize(rotation);
        CobbleventureBootstrap.Point placementPoint = center;
        CobbleventureBootstrap.Point roadPoint = null;
        BlockPos entranceAnchor = null;
        if (!configured.placementAnchor().equals("center")) {
            RoadAlignedPlacement aligned = roadAlignedPlacement(
                level, world, configured, template, center
            );
            if (aligned != null) {
                effectiveRotation = aligned.rotation();
                rotation = rotation(effectiveRotation);
                rotatedSize = template.getSize(rotation);
                placementPoint = aligned.entrance();
                roadPoint = aligned.road();
                entranceAnchor = aligned.entranceAnchor();
            }
        }
        String rotationName = rotationName(effectiveRotation);
        if (entranceAnchor == null && configured.placementAnchor().equals("road_anchor")) {
            entranceAnchor = BuildingRuntimeSystem.exteriorRoadAnchorOffset(
                level, configured.structure(), rotationName
            );
        } else if (entranceAnchor == null
            && configured.placementAnchor().equals("door")) {
            entranceAnchor = BuildingRuntimeSystem.exteriorDoorOffset(
                configured.structure(), rotationName
            );
        }
        CobbleventureBootstrap.Point heightReference = roadPoint == null
            ? placementPoint : roadPoint;
        int floorY = CobbleventureBootstrap.nativeTerrainColumn(
            world, heightReference.x(), heightReference.z()
        ).groundY();
        BlockPos origin;
        if (configured.placementAnchor().equals("road_anchor")) {
            if (entranceAnchor == null) {
                throw new IllegalStateException(
                    "Road-aligned world structure requires its configured entrance anchor: "
                        + configured.id() + " (" + configured.structure() + ", "
                        + configured.placementAnchor() + ")"
                );
            }
            origin = new BlockPos(
                placementPoint.x() - entranceAnchor.getX(),
                floorY - entranceAnchor.getY(),
                placementPoint.z() - entranceAnchor.getZ()
            );
        } else if (configured.placementAnchor().equals("door")) {
            if (entranceAnchor == null) {
                throw new IllegalStateException(
                    "Road-aligned world structure requires its configured entrance anchor: "
                        + configured.id() + " (" + configured.structure() + ", door)"
                );
            }
            // Door metadata points one block above the authored foundation. Only X/Z
            // are alignment coordinates; lowering the origin by door Y buries the yard.
            origin = new BlockPos(
                placementPoint.x() - entranceAnchor.getX(),
                floorY,
                placementPoint.z() - entranceAnchor.getZ()
            );
        } else {
            int minX = center.x() - rotatedSize.getX() / 2;
            int minZ = center.z() - rotatedSize.getZ() / 2;
            origin = rotatedTemplateOrigin(
                minX, floorY, minZ,
                template.getSize().getX(), template.getSize().getZ(), rotation
            );
        }
        BlockPos marker = new BlockPos(
            center.x(), world.grid().origin().y() - 18, center.z()
        );
        if (!level.getBlockState(marker).is(Blocks.RESPAWN_ANCHOR)) {
            if (roadPoint != null) {
                layAccessRoad(level, world, roadPoint, placementPoint, floorY);
            }
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE);
            ExplicitAirPlacementProcessor.configure(template, settings);
            if (!template.placeInWorld(
                level, origin, origin, settings,
                RandomSource.create(level.getSeed() ^ origin.asLong()), 2
            )) {
                throw new IllegalStateException(
                    "World structure placement failed: " + configured.id()
                );
            }
            replacePlacedRoadAnchors(level, template, origin, settings);
            level.setBlock(marker, Blocks.RESPAWN_ANCHOR.defaultBlockState(), 2);
            LOGGER.info(
                "World structure generated: id={}, type={}, anchor={}, origin={}",
                configured.id(), configured.type(), configured.anchor(), origin
            );
        }
        CobbleventureBootstrap.scheduleGenerationDebrisCleanup(
            level, configured.structure(), origin, template, rotation
        );
        BuildingRuntimeSystem.onStructurePlaced(
            level, configured.structure(),
            new CobbleventureBootstrap.BlockPoint(
                origin.getX(), origin.getY(), origin.getZ()
            ),
            rotationName
        );
        DungeonSystem.registerWorldPlacement(
            level, configured, origin, rotation
        );
    }

    private static RoadAlignedPlacement roadAlignedPlacement(
        ServerLevel level, HexWorldPlan world, WorldStructure configured,
        StructureTemplate template, CobbleventureBootstrap.Point center
    ) {
        List<RouteClearance> routes = world.paths().stream()
            .filter(path -> path.corridorWidthBlocks() > 0.0D)
            .filter(path -> RegionalRouteGeometry.corridorOverlapsHexTile(
                path.centerline(), center, world.grid().radius(),
                path.corridorWidthBlocks()
            ))
            .map(path -> new RouteClearance(
                path.id(), path.centerline(), roadClearance(path)
            ))
            .toList();
        RouteClearance route = routes.stream()
            .min(Comparator
                .comparingDouble((RouteClearance candidate) ->
                    distanceToPolyline(candidate.centerline(), center.x(), center.z())
                )
                .thenComparing(RouteClearance::id))
            .orElse(null);
        if (route == null) return null;

        RouteProjection centerProjection = nearestRouteProjection(
            route.centerline(), center.x(), center.z()
        );
        if (centerProjection == null) return null;
        double tangentLength = Math.hypot(
            centerProjection.tangentX(), centerProjection.tangentZ()
        );
        if (tangentLength < 0.0001D) return null;
        double normalX = -centerProjection.tangentZ() / tangentLength;
        double normalZ = centerProjection.tangentX() / tangentLength;
        List<CobbleventureBootstrap.Point> reservations = reservedStructurePoints(
            world, configured, center
        );

        RoadAlignedPlacement selected = null;
        double selectedReservationDistance = Double.NEGATIVE_INFINITY;
        for (int offset = 0; offset < 4; offset++) {
            int candidateRotation = Math.floorMod(configured.rotation() + offset, 4);
            String candidateRotationName = rotationName(candidateRotation);
            Direction candidateOutside;
            BlockPos candidateAnchor;
            if (configured.placementAnchor().equals("road_anchor")) {
                candidateOutside = BuildingRuntimeSystem.exteriorRoadAnchorOutsideDirection(
                    level, configured.structure(), candidateRotationName
                );
                candidateAnchor = BuildingRuntimeSystem.exteriorRoadAnchorOffset(
                    level, configured.structure(), candidateRotationName
                );
            } else {
                candidateOutside = BuildingRuntimeSystem.exteriorDoorOutsideDirection(
                    configured.structure(), candidateRotationName
                );
                candidateAnchor = BuildingRuntimeSystem.exteriorDoorOffset(
                    configured.structure(), candidateRotationName
                );
            }
            if (candidateOutside == null || candidateAnchor == null
                || !candidateOutside.getAxis().isHorizontal()) {
                continue;
            }
            double facingAcrossRoad = normalX * candidateOutside.getStepX()
                + normalZ * candidateOutside.getStepZ();
            if (Math.abs(facingAcrossRoad) < 0.5D) {
                continue;
            }
            Rotation candidateTransform = rotation(candidateRotation);
            CobbleventureBootstrap.Point entrance = roadClearingPlacementPoint(
                route.centerline(), routes, center, candidateOutside,
                candidateAnchor, template.getSize(), candidateTransform,
                route.clearance()
            );
            int originX = entrance.x() - candidateAnchor.getX();
            int originZ = entrance.z() - candidateAnchor.getZ();
            StructureFootprint footprint = structureFootprint(
                originX, originZ, template.getSize().getX(),
                template.getSize().getZ(), candidateTransform
            );
            if (routes.stream().anyMatch(candidate ->
                distanceToFootprint(candidate.centerline(), footprint)
                    < candidate.clearance())) {
                continue;
            }
            double reservationDistance = reservations.stream()
                .mapToDouble(point -> pointToRectangleDistance(
                    point.x(), point.z(), footprint
                ))
                .min().orElse(Double.POSITIVE_INFINITY);
            RouteProjection entranceProjection = nearestRouteProjection(
                route.centerline(), entrance.x(), entrance.z()
            );
            if (entranceProjection == null) continue;
            RoadAlignedPlacement candidate = new RoadAlignedPlacement(
                candidateRotation, entrance,
                new CobbleventureBootstrap.Point(
                    (int) Math.round(entranceProjection.x()),
                    (int) Math.round(entranceProjection.z())
                ),
                candidateAnchor
            );
            if (selected == null
                || reservationDistance > selectedReservationDistance + 0.001D) {
                selected = candidate;
                selectedReservationDistance = reservationDistance;
            }
        }
        return selected;
    }

    private static List<CobbleventureBootstrap.Point> reservedStructurePoints(
        HexWorldPlan world, WorldStructure configured,
        CobbleventureBootstrap.Point center
    ) {
        int distance = (int) Math.round(world.grid().radius() * 7.0D / 16.0D);
        List<CobbleventureBootstrap.Point> reserved = new ArrayList<>();
        for (var entrance : world.caveEntrances()) {
            if (!entrance.anchor().equals(configured.anchor())) continue;
            Direction facing = horizontalDirection(entrance.facing());
            if (facing != null) {
                reserved.add(new CobbleventureBootstrap.Point(
                    center.x() + facing.getStepX() * distance,
                    center.z() + facing.getStepZ() * distance
                ));
            }
        }
        for (var gate : world.gates()) {
            if (!gate.anchor().equals(configured.anchor())) continue;
            Direction facing = horizontalDirection(gate.facing());
            if (facing != null) {
                reserved.add(new CobbleventureBootstrap.Point(
                    center.x() + facing.getStepX() * distance,
                    center.z() + facing.getStepZ() * distance
                ));
            }
        }
        return List.copyOf(reserved);
    }

    private static Direction horizontalDirection(String facing) {
        return switch (facing) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private static void layAccessRoad(
        ServerLevel level, HexWorldPlan world,
        CobbleventureBootstrap.Point road,
        CobbleventureBootstrap.Point entrance, int roadY
    ) {
        int dx = entrance.x() - road.x();
        int dz = entrance.z() - road.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        double length = Math.max(1.0D, Math.hypot(dx, dz));
        double normalX = -dz / length;
        double normalZ = dx / length;
        for (int step = 0; step <= steps; step++) {
            double progress = steps == 0 ? 0.0D : step / (double) steps;
            int centerX = (int) Math.round(road.x() + dx * progress);
            int centerZ = (int) Math.round(road.z() + dz * progress);
            for (int side = -1; side <= 1; side++) {
                int x = (int) Math.round(centerX + normalX * side);
                int z = (int) Math.round(centerZ + normalZ * side);
                CobbleventureBootstrap.prepareWorldRoadColumnAtY(
                    level, world, x, z, roadY
                );
                level.setBlock(
                    new BlockPos(x, roadY, z),
                    CobbleventureBootstrap.worldRoadSurfaceBlock(world, x, z), 2
                );
            }
        }
    }

    private static double roadClearance(WorldPlanModels.ConnectionPath route) {
        double edgeGrowth = Math.min(0.42D, Math.abs(route.edgeNoise()) * 1.5D);
        return route.corridorWidthBlocks() / 2.0D * (1.0D + edgeGrowth) + 2.0D;
    }

    static CobbleventureBootstrap.Point roadClearingPlacementPoint(
        List<CobbleventureBootstrap.Point> centerline,
        CobbleventureBootstrap.Point fallbackCenter, Direction outside,
        BlockPos entranceAnchor, Vec3i templateSize, Rotation rotation,
        double clearance
    ) {
        return roadClearingPlacementPoint(
            centerline,
            List.of(new RouteClearance("test", centerline, clearance)),
            fallbackCenter, outside, entranceAnchor, templateSize, rotation, clearance
        );
    }

    private static CobbleventureBootstrap.Point roadClearingPlacementPoint(
        List<CobbleventureBootstrap.Point> centerline,
        List<RouteClearance> routes,
        CobbleventureBootstrap.Point fallbackCenter, Direction outside,
        BlockPos entranceAnchor, Vec3i templateSize, Rotation rotation,
        double clearance
    ) {
        RouteProjection projection = nearestRouteProjection(
            centerline, fallbackCenter.x(), fallbackCenter.z()
        );
        if (projection == null) return fallbackCenter;
        double length = Math.hypot(projection.tangentX(), projection.tangentZ());
        if (length < 0.0001D) return fallbackCenter;
        double normalX = -projection.tangentZ() / length;
        double normalZ = projection.tangentX() / length;
        int preferredSign = normalX * outside.getStepX()
            + normalZ * outside.getStepZ() <= 0.0D ? 1 : -1;
        double maximumClearance = routes.stream()
            .mapToDouble(RouteClearance::clearance)
            .max()
            .orElse(clearance);
        int maximumDistance = (int) Math.ceil(maximumClearance
            + Math.hypot(templateSize.getX(), templateSize.getZ()) * 4.0D + 64.0D);
        int startDistance = Math.max(1, (int) Math.ceil(clearance));
        for (int distance = startDistance; distance <= maximumDistance; distance++) {
            int entranceX = (int) Math.round(
                projection.x() + normalX * preferredSign * distance
            );
            int entranceZ = (int) Math.round(
                projection.z() + normalZ * preferredSign * distance
            );
            int originX = entranceX - entranceAnchor.getX();
            int originZ = entranceZ - entranceAnchor.getZ();
            StructureFootprint footprint = structureFootprint(
                originX, originZ, templateSize.getX(), templateSize.getZ(), rotation
            );
            boolean clearsEveryRoad = routes.stream().allMatch(route ->
                distanceToFootprint(route.centerline(), footprint)
                    >= route.clearance()
            );
            if (clearsEveryRoad) {
                return new CobbleventureBootstrap.Point(entranceX, entranceZ);
            }
        }
        int setback = Math.max(1, (int) Math.ceil(clearance));
        return offsetEntranceFromRoadCenter(fallbackCenter, outside, setback);
    }

    private static StructureFootprint structureFootprint(
        int originX, int originZ, int width, int depth, Rotation rotation
    ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new StructureFootprint(
                originX - depth + 1, originZ, originX, originZ + width - 1
            );
            case CLOCKWISE_180 -> new StructureFootprint(
                originX - width + 1, originZ - depth + 1, originX, originZ
            );
            case COUNTERCLOCKWISE_90 -> new StructureFootprint(
                originX, originZ - width + 1, originX + depth - 1, originZ
            );
            default -> new StructureFootprint(
                originX, originZ, originX + width - 1, originZ + depth - 1
            );
        };
    }

    private static RouteProjection nearestRouteProjection(
        List<CobbleventureBootstrap.Point> centerline, double x, double z
    ) {
        if (centerline.size() < 2) return null;
        RouteProjection selected = null;
        double selectedDistance = Double.POSITIVE_INFINITY;
        for (int index = 1; index < centerline.size(); index++) {
            CobbleventureBootstrap.Point start = centerline.get(index - 1);
            CobbleventureBootstrap.Point end = centerline.get(index);
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            double lengthSquared = dx * dx + dz * dz;
            if (lengthSquared <= 0.0D) continue;
            double factor = ((x - start.x()) * dx + (z - start.z()) * dz)
                / lengthSquared;
            factor = Math.max(0.0D, Math.min(1.0D, factor));
            double projectedX = start.x() + factor * dx;
            double projectedZ = start.z() + factor * dz;
            double distance = Math.hypot(x - projectedX, z - projectedZ);
            if (distance < selectedDistance) {
                selectedDistance = distance;
                selected = new RouteProjection(projectedX, projectedZ, dx, dz);
            }
        }
        return selected;
    }

    private static double distanceToPolyline(
        List<CobbleventureBootstrap.Point> centerline, double x, double z
    ) {
        RouteProjection projection = nearestRouteProjection(centerline, x, z);
        return projection == null ? Double.POSITIVE_INFINITY
            : Math.hypot(x - projection.x(), z - projection.z());
    }

    private static double distanceToFootprint(
        List<CobbleventureBootstrap.Point> centerline, StructureFootprint footprint
    ) {
        double closest = Double.POSITIVE_INFINITY;
        for (int index = 1; index < centerline.size(); index++) {
            CobbleventureBootstrap.Point start = centerline.get(index - 1);
            CobbleventureBootstrap.Point end = centerline.get(index);
            closest = Math.min(closest, segmentToRectangleDistance(
                start.x(), start.z(), end.x(), end.z(), footprint
            ));
        }
        return closest;
    }

    private static double segmentToRectangleDistance(
        double x1, double z1, double x2, double z2, StructureFootprint footprint
    ) {
        if (segmentIntersectsRectangle(x1, z1, x2, z2, footprint)) return 0.0D;
        double closest = Math.min(
            pointToRectangleDistance(x1, z1, footprint),
            pointToRectangleDistance(x2, z2, footprint)
        );
        for (double[] corner : new double[][] {
            {footprint.minX(), footprint.minZ()},
            {footprint.maxX(), footprint.minZ()},
            {footprint.maxX(), footprint.maxZ()},
            {footprint.minX(), footprint.maxZ()},
        }) {
            closest = Math.min(closest, pointToSegmentDistance(
                corner[0], corner[1], x1, z1, x2, z2
            ));
        }
        return closest;
    }

    private static boolean segmentIntersectsRectangle(
        double x1, double z1, double x2, double z2, StructureFootprint footprint
    ) {
        if (insideRectangle(x1, z1, footprint)
            || insideRectangle(x2, z2, footprint)) return true;
        double minX = footprint.minX();
        double maxX = footprint.maxX();
        double minZ = footprint.minZ();
        double maxZ = footprint.maxZ();
        return segmentsIntersect(x1, z1, x2, z2, minX, minZ, maxX, minZ)
            || segmentsIntersect(x1, z1, x2, z2, maxX, minZ, maxX, maxZ)
            || segmentsIntersect(x1, z1, x2, z2, maxX, maxZ, minX, maxZ)
            || segmentsIntersect(x1, z1, x2, z2, minX, maxZ, minX, minZ);
    }

    private static boolean insideRectangle(
        double x, double z, StructureFootprint footprint
    ) {
        return x >= footprint.minX() && x <= footprint.maxX()
            && z >= footprint.minZ() && z <= footprint.maxZ();
    }

    private static boolean segmentsIntersect(
        double ax, double az, double bx, double bz,
        double cx, double cz, double dx, double dz
    ) {
        double first = cross(ax, az, bx, bz, cx, cz);
        double second = cross(ax, az, bx, bz, dx, dz);
        double third = cross(cx, cz, dx, dz, ax, az);
        double fourth = cross(cx, cz, dx, dz, bx, bz);
        double epsilon = 0.0000001D;
        boolean properIntersection = ((first > epsilon && second < -epsilon)
            || (first < -epsilon && second > epsilon))
            && ((third > epsilon && fourth < -epsilon)
            || (third < -epsilon && fourth > epsilon));
        if (properIntersection) return true;
        return Math.abs(first) <= epsilon && onSegment(ax, az, bx, bz, cx, cz, epsilon)
            || Math.abs(second) <= epsilon && onSegment(ax, az, bx, bz, dx, dz, epsilon)
            || Math.abs(third) <= epsilon && onSegment(cx, cz, dx, dz, ax, az, epsilon)
            || Math.abs(fourth) <= epsilon && onSegment(cx, cz, dx, dz, bx, bz, epsilon);
    }

    private static boolean onSegment(
        double ax, double az, double bx, double bz,
        double px, double pz, double epsilon
    ) {
        return px >= Math.min(ax, bx) - epsilon && px <= Math.max(ax, bx) + epsilon
            && pz >= Math.min(az, bz) - epsilon && pz <= Math.max(az, bz) + epsilon;
    }

    private static double cross(
        double ax, double az, double bx, double bz, double cx, double cz
    ) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
    }

    private static double pointToRectangleDistance(
        double x, double z, StructureFootprint footprint
    ) {
        double dx = Math.max(footprint.minX() - x, Math.max(0.0D, x - footprint.maxX()));
        double dz = Math.max(footprint.minZ() - z, Math.max(0.0D, z - footprint.maxZ()));
        return Math.hypot(dx, dz);
    }

    private static double pointToSegmentDistance(
        double x, double z, double x1, double z1, double x2, double z2
    ) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        double factor = lengthSquared == 0.0D ? 0.0D
            : ((x - x1) * dx + (z - z1) * dz) / lengthSquared;
        factor = Math.max(0.0D, Math.min(1.0D, factor));
        return Math.hypot(x - (x1 + factor * dx), z - (z1 + factor * dz));
    }

    static CobbleventureBootstrap.Point offsetEntranceFromRoadCenter(
        CobbleventureBootstrap.Point roadCenter, Direction outside, int setback
    ) {
        // The entrance faces from the building toward the road. Move the
        // entrance in the opposite direction so the NBT body clears the road.
        int distance = Math.max(0, setback);
        return new CobbleventureBootstrap.Point(
            roadCenter.x() - outside.getStepX() * distance,
            roadCenter.z() - outside.getStepZ() * distance
        );
    }

    static BlockPos rotatedTemplateOrigin(
        int x, int y, int z, int width, int depth, Rotation rotation
    ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(x + depth - 1, y, z);
            case CLOCKWISE_180 -> new BlockPos(x + width - 1, y, z + depth - 1);
            case COUNTERCLOCKWISE_90 -> new BlockPos(x, y, z + width - 1);
            default -> new BlockPos(x, y, z);
        };
    }

    private static void replacePlacedRoadAnchors(
        ServerLevel level, StructureTemplate structure, BlockPos origin,
        StructurePlaceSettings settings
    ) {
        for (StructureTemplate.StructureBlockInfo info : structure.filterBlocks(
            origin, settings, Blocks.JIGSAW
        )) {
            if (info.nbt() == null
                || !"cobbleventure:road_anchor".equals(info.nbt().getString("name"))) {
                continue;
            }
            BlockState replacement = Blocks.AIR.defaultBlockState();
            try {
                replacement = BlockStateParser.parseForBlock(
                    level.holderLookup(Registries.BLOCK),
                    info.nbt().getString("final_state"), false
                ).blockState();
            } catch (CommandSyntaxException | RuntimeException error) {
                LOGGER.warn(
                    "Invalid world structure road_anchor final_state; replacing with air: "
                        + "position={}, value={}",
                    info.pos(), info.nbt().getString("final_state")
                );
            }
            level.setBlock(info.pos(), replacement, 2);
        }
    }

    private static Rotation rotation(int value) {
        return switch (Math.floorMod(value, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static String rotationName(int value) {
        return switch (Math.floorMod(value, 4)) {
            case 1 -> "clockwise_90";
            case 2 -> "clockwise_180";
            case 3 -> "counterclockwise_90";
            default -> "none";
        };
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalStateException("World structure string is missing: " + key);
        }
        String result = value.get(key).getAsString();
        if (result.isBlank()) {
            throw new IllegalStateException("World structure string is empty: " + key);
        }
        return result;
    }

    record WorldStructure(
        String id,
        String type,
        HexCoord anchor,
        String structure,
        int rotation,
        String placementAnchor,
        List<DungeonConnection> dungeonConnections
    ) {}

    record DungeonConnection(String anchorId, String entranceId) {}

    private record StructureFootprint(int minX, int minZ, int maxX, int maxZ) {}

    private record RouteProjection(
        double x, double z, double tangentX, double tangentZ
    ) {}

    private record RouteClearance(
        String id, List<CobbleventureBootstrap.Point> centerline, double clearance
    ) {}

    private record RoadAlignedPlacement(
        int rotation,
        CobbleventureBootstrap.Point entrance,
        CobbleventureBootstrap.Point road,
        BlockPos entranceAnchor
    ) {}
}
