package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexGrid;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import dev.buizz.cobbleventure.adventure.event.EventNpcInteractionHandler;
import dev.buizz.cobbleventure.adventure.event.EventDialogueLifecycle;
import dev.buizz.cobbleventure.adventure.event.EventSessionKey;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import org.slf4j.Logger;

/** Places condition-aware gate objects declared on the hex world map. */
final class WorldGateSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DENY_COOLDOWN = "cobbleventureGateDenyCooldown";
    private static final String FOREST_PORTAL_COOLDOWN = "cobbleventureForestPortalCooldown";
    private static final String FOREST_ENTRY_MARKER = "cobbleventure:forest_entry";
    private static final String ROAD_ANCHOR_MARKER = "cobbleventure:road_anchor";
    private static final int MAX_NATURAL_GATE_FUNNEL_DEPTH = 8;
    private static final int GATE_STRUCTURE_NATURAL_CLEARANCE = 3;
    private static final int MIN_GATE_APPROACH_DEPTH = 8;
    private static final int MAX_GATE_APPROACH_DEPTH = 24;
    /**
     * Lets the trigger plane overlap the solid shoulders on both sides of the
     * authored opening. The overlap accounts for the player's body width and
     * prevents a wider passage from exposing a sub-block seam at either edge.
     */
    private static final double GATE_TRIGGER_EDGE_OVERLAP = 1.0D;
    private static final double MIN_GATE_NPC_SEARCH_RADIUS = 8.0D;
    private static final double GATE_DIALOGUE_APPROACH_DEPTH = 8.0D;
    private static final Map<UUID, Vec3> LAST_POSITIONS = new HashMap<>();
    private static final Map<UUID, HexCoord> LAST_HEX_CELLS = new HashMap<>();
    private static final Map<UUID, PendingGateDenial> PENDING_DENIALS = new HashMap<>();
    private static final Map<UUID, PendingEventDialogue> PENDING_EVENT_DIALOGUES =
        new HashMap<>();
    private static final Map<String, ForestEntryMarker> FOREST_ENTRY_MARKERS = new HashMap<>();
    private static final Map<String, ForestEntryMarker> FOREST_EXIT_MARKERS = new HashMap<>();
    private static boolean eventDialogueLifecycleRegistered;

    private WorldGateSystem() {
    }

    static synchronized void registerEventDialogueLifecycle() {
        if (eventDialogueLifecycleRegistered) {
            return;
        }
        eventDialogueLifecycleRegistered = true;
        EventDialogueLifecycle.register(WorldGateSystem::updateEventDialogueState);
    }

    static List<Gate> parse(JsonArray objects) {
        List<Gate> gates = new ArrayList<>();
        for (JsonElement element : objects) {
            JsonObject value = element.getAsJsonObject();
            if (!"gate".equals(requiredString(value, "type"))) {
                continue;
            }
            JsonObject anchor = value.getAsJsonObject("anchor");
            JsonObject properties = value.getAsJsonObject("properties");
            List<PlayerConditions.Condition> conditions = new ArrayList<>();
            if (properties.has("conditions")) {
                for (JsonElement conditionElement : properties.getAsJsonArray("conditions")) {
                    conditions.add(PlayerConditions.parse(conditionElement.getAsJsonObject()));
                }
            }
            gates.add(new Gate(
                requiredString(value, "id"),
                new HexCoord(
                    anchor.get("q").getAsInt(), anchor.get("r").getAsInt()
                ),
                gateStructure(value),
                value.has("rotation") ? value.get("rotation").getAsInt() : 0,
                optionalString(properties, "facing", "north"),
                centerPlacement(properties),
                !centerPlacement(properties).equals("npc"),
                surroundingType(properties),
                optionalString(properties, "wall_block", "minecraft:stone_bricks"),
                optionalString(properties, "tree_log", "minecraft:oak_log"),
                optionalString(properties, "tree_leaves", "minecraft:oak_leaves"),
                optionalInt(properties, "wall_thickness", 5),
                optionalInt(properties, "wall_height", 7),
                properties.has("passage_width")
                    ? properties.get("passage_width").getAsInt()
                    : optionalInt(properties, "opening_width", 7),
                optionalInt(properties, "barrier_height", 24),
                optionalString(properties, "condition_mode", "all"),
                List.copyOf(conditions),
                optionalString(properties, "deny_message", "아직 이 관문을 통과할 수 없습니다."),
                optionalString(properties, "deny_dialog", "greeting"),
                nullableString(properties, "npc"),
                nullableString(properties, "destination_forest"),
                nullableString(properties, "destination_entrance"),
                null, null, null
            ));
        }
        return List.copyOf(gates);
    }

    private static String gateStructure(JsonObject value) {
        String resource = nullableString(value, "resource");
        return "cobbleventure:gate/default".equals(resource)
            ? "cobbleventure:gate/default_gate"
            : resource;
    }

    /** Loads forest entrances as unconditional ForestGate structures, independently from world gates. */
    static List<Gate> parseForestEntrances(JsonArray entrances) {
        List<Gate> gates = new ArrayList<>();
        for (JsonElement element : entrances) {
            JsonObject value = element.getAsJsonObject();
            JsonObject anchor = value.getAsJsonObject("anchor");
            String direction = optionalString(value, "facing", "east");
            gates.add(new Gate(
                requiredString(value, "id"),
                new HexCoord(anchor.get("q").getAsInt(), anchor.get("r").getAsInt()),
                requiredString(value, "structure"),
                optionalInt(value, "rotation", 0),
                direction, "gate", true, "none", "minecraft:mossy_stone_bricks",
                optionalString(value, "tree_log", "minecraft:spruce_log"),
                optionalString(value, "tree_leaves", "minecraft:spruce_leaves"),
                optionalInt(value, "wall_thickness", 7),
                optionalInt(value, "wall_height", 14),
                optionalInt(value, "opening_width", 7),
                optionalInt(value, "barrier_height", 32),
                "all", List.of(), "숲 입구입니다.", "greeting", null,
                requiredString(value, "forest"), requiredString(value, "entrance"),
                null, null, null
            ));
        }
        return List.copyOf(gates);
    }

    private static String centerPlacement(JsonObject properties) {
        if (properties.has("center_placement")) {
            return properties.get("center_placement").getAsString();
        }
        String legacyMode = optionalString(properties, "gate_mode", "classic");
        if (legacyMode.equals("npc_only")) {
            return "npc";
        }
        return properties.has("npc") ? "gate_npc" : "gate";
    }

    private static String surroundingType(JsonObject properties) {
        String type = optionalString(properties, "surrounding_type", "wall");
        return type.equals("wall") ? "wall" : "natural";
    }

    static void placeAll(
        ServerLevel level, HexWorldPlan world
    ) {
        for (Gate gate : world.gates()) {
            place(level, world, gate);
        }
    }

    private static void place(
        ServerLevel level, HexWorldPlan world, Gate gate
    ) {
        HexGrid grid = world.grid();
        CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
        BlockPos marker = new BlockPos(
            center.x(), grid.origin().y() - 16, center.z()
        );
        BlockState markerState = level.getBlockState(marker);
        boolean forestGate = gate.destinationForest() != null;
        if (markerState.is(Blocks.RESPAWN_ANCHOR)) {
            // A completed gate is immutable. Entrance grading changes apply
            // only while creating a new world and must never rewrite an
            // existing world's authored or player-modified surroundings.
            if (forestGate) {
                cacheForestEntryMarker(level, world, gate);
            }
            return;
        }
        int halfOpening = gate.openingWidth() / 2;
        boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
        int halfLength = gateBoundaryHalfLength(world, gate, halfOpening);
        int halfThickness = gate.wallThickness() / 2
            + (gate.buildingEnabled() ? 4 : 0);
        int centerY = groundY(level, center.x(), center.z());
        Map<Long, Integer> wallGroundHeights = new HashMap<>();
        boolean shouldPlaceStructure = gate.buildingEnabled();
        StructureFootprint plannedFootprint = shouldPlaceStructure && !forestGate
            ? plannedStructureFootprint(level, gate, center)
            : null;
        if (plannedFootprint != null) {
            centerY = roadAlignedGateOriginY(
                level, world, gate, plannedFootprint, centerY
            );
        }
        List<GateEntrancePlacement> plannedEntrances = plannedFootprint == null
            ? List.of()
            : plannedGateRoadAnchors(
                level, gate, plannedFootprint, centerY
            );
        if (shouldPlaceStructure && !forestGate
            && plannedEntrances.size() != 2) {
            LOGGER.error(
                "World gate generation stopped because two road_anchor jigsaws are required: gate={}, structure={}",
                gate.id(), gate.structure()
            );
            return;
        }
        if (forestGate) {
            cacheForestEntryMarker(level, world, gate);
        }
        // The road column preparation deliberately removes trees and vegetation.
        // Lay these short connectors before authored gate surroundings so it can
        // never cut down the natural barrier that belongs to the gate itself.
        if (!forestGate && (!shouldPlaceStructure || plannedFootprint != null)) {
            layGateApproachRoads(
                level, world, gate, center, plannedFootprint,
                plannedEntrances, halfThickness
            );
        }
        if (!forestGate && gate.surroundingType().equals("wall")) {
            placeWallSurroundings(
                level, gate, center, horizontal,
                halfLength, halfThickness, halfOpening, wallGroundHeights
            );
        } else if (!forestGate && gate.surroundingType().equals("natural")) {
            placeNaturalSurroundings(
                level, world, gate, center,
                halfLength, halfThickness, halfOpening
            );
        }
        GateStructurePlacement gatePlacement = null;
        boolean structurePlaced = true;
        if (shouldPlaceStructure) {
            if (forestGate) {
                structurePlaced = placeForestStructure(level, world, gate);
            } else {
                gatePlacement = placeStructure(level, gate, center, centerY);
                structurePlaced = gatePlacement != null;
            }
        }
        if (!structurePlaced) {
            LOGGER.error(
                "World gate remains incomplete because its NBT was not placed: gate={}",
                gate.id()
            );
            return;
        }
        if (!forestGate && gatePlacement != null) {
            finishGateRoadAnchorApproaches(
                level, world, plannedEntrances
            );
        }
        if (!forestGate && gate.surroundingType().equals("wall")) {
            sealWallSurroundingAfterStructure(
                level, gate, center, horizontal,
                halfLength, halfThickness, halfOpening, wallGroundHeights,
                gatePlacement == null ? null : gatePlacement.footprint()
            );
        }
        if (!forestGate && gatePlacement != null
            && gate.surroundingType().equals("natural")) {
            // The first pass precedes the NBT so its trees survive road work.
            // Once the facade exists, scan it and close only the exterior seam.
            finishNaturalGateSurroundings(level, world, gate, center);
        }
        if (gate.npc() != null) {
            spawnNpc(level, gate, center, centerY);
        }
        level.setBlock(marker, Blocks.RESPAWN_ANCHOR.defaultBlockState(), 2);
        LOGGER.info(
            "World gate generated: id={}, anchor={}, facing={}, building={}, surroundings={}",
            gate.id(), gate.anchor(), gate.facing(), gate.buildingEnabled(), gate.surroundingType()
        );
    }

    /** Finishes natural gate shoulders within the current world-generation run. */
    private static void finishNaturalGateSurroundings(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center
    ) {
        int halfOpening = gate.openingWidth() / 2;
        int halfLength = gateBoundaryHalfLength(world, gate, halfOpening);
        int halfThickness = gate.wallThickness() / 2
            + (gate.buildingEnabled() ? 4 : 0);
        placeNaturalSurroundings(
            level, world, gate, center,
            halfLength, halfThickness, halfOpening
        );
        LOGGER.info("Natural gate surroundings finished: gate={}", gate.id());
    }

    static void finishNaturalSurroundingsAfterTownGeneration(
        ServerLevel level, HexWorldPlan world
    ) {
        for (Gate gate : world.gates()) {
            if (gate.destinationForest() != null
                || !gate.surroundingType().equals("natural")) {
                continue;
            }
            finishNaturalGateSurroundings(
                level, world, gate, alignedGateCenter(world, gate)
            );
        }
    }

    private static void placeWallSurroundings(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, int halfLength, int halfThickness, int halfOpening,
        Map<Long, Integer> groundHeights
    ) {
        BlockState wall = blockState(gate.wallBlock());
        for (int along = -halfLength; along <= halfLength; along++) {
            for (int across = -halfThickness; across <= halfThickness; across++) {
                int x = center.x() + (horizontal ? along : across);
                int z = center.z() + (horizontal ? across : along);
                int groundY = groundY(level, x, z);
                groundHeights.put(new BlockPos(x, 0, z).asLong(), groundY);
                boolean opening = Math.abs(along) <= halfOpening;
                for (int height = 1; height <= gate.wallHeight(); height++) {
                    level.setBlock(new BlockPos(x, groundY + height, z),
                        opening ? Blocks.AIR.defaultBlockState() : wall, 2);
                }
                placeOverheadBarrier(level, x, z, groundY, gate.wallHeight(), gate.barrierHeight());
            }
        }
    }

    /**
     * Resolves an ordinary gate's actual placement on the selected edge of its
     * anchor hex. East and west each use their single face center. North and
     * south use the vertex shared by two playable diagonal faces, but move to
     * the only playable face when the other side is inaccessible. Forest entrances
     * retain their tile-center ray origin because their
     * separate cave-style geometry already finds the outer collision boundary.
     * Every gate consumer uses this point, keeping the structure, NPC,
     * collision threshold, radar marker, and approach road together.
     */
    private static CobbleventureBootstrap.Point alignedGateCenter(
        HexWorldPlan world, Gate gate
    ) {
        if (gate.destinationForest() != null) {
            return world.grid().worldCenter(gate.anchor());
        }
        CobbleventureBootstrap.Point edge = gateEdgeCenter(
            world.grid(), gate.anchor(), gate.facing(),
            offset -> gateFaceIsOpen(world, gate.anchor(), offset)
        );
        CobbleventureBootstrap.Point tile = world.grid().worldCenter(gate.anchor());
        double towardCenterX = tile.x() - edge.x();
        double towardCenterZ = tile.z() - edge.z();
        double distance = Math.hypot(towardCenterX, towardCenterZ);
        boolean northSouth = gate.facing().equals("north")
            || gate.facing().equals("south");
        long openFaces = gateFaceOffsets(gate.facing()).stream()
            .filter(offset -> gateFaceIsOpen(world, gate.anchor(), offset))
            .count();
        int inset = northSouth && openFaces == 2L
            ? (gate.buildingEnabled() ? 16 : 10)
            : 0;
        CobbleventureBootstrap.Point insetCenter = distance < 1.0D || inset == 0
            ? edge
            : new CobbleventureBootstrap.Point(
                roundGateCoordinate(edge.x() + towardCenterX / distance * inset),
                roundGateCoordinate(edge.z() + towardCenterZ / distance * inset)
            );
        if (northSouth && openFaces != 1L) {
            return insetCenter;
        }
        return snapGateToRouteCenterline(world, gate, insetCenter);
    }

    private static CobbleventureBootstrap.Point snapGateToRouteCenterline(
        HexWorldPlan world, Gate gate, CobbleventureBootstrap.Point candidate
    ) {
        Direction normal = facingDirection(gate.facing());
        double maximumDistance = world.grid().radius() * 0.8D;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestX = candidate.x();
        double bestZ = candidate.z();
        for (WorldPlanModels.ConnectionPath path : world.paths()) {
            if (!path.cells().contains(gate.anchor())) continue;
            List<CobbleventureBootstrap.Point> points = path.centerline();
            for (int index = 1; index < points.size(); index++) {
                CobbleventureBootstrap.Point start = points.get(index - 1);
                CobbleventureBootstrap.Point end = points.get(index);
                double dx = end.x() - start.x();
                double dz = end.z() - start.z();
                double lengthSquared = dx * dx + dz * dz;
                if (lengthSquared < 1.0D) continue;
                double projection = ((candidate.x() - start.x()) * dx
                    + (candidate.z() - start.z()) * dz) / lengthSquared;
                projection = Math.max(0.0D, Math.min(1.0D, projection));
                double projectedX = start.x() + dx * projection;
                double projectedZ = start.z() + dz * projection;
                double projectedDistance = Math.hypot(
                    projectedX - candidate.x(), projectedZ - candidate.z()
                );
                if (projectedDistance <= maximumDistance
                    && projectedDistance < bestDistance) {
                    bestDistance = projectedDistance;
                    bestX = projectedX;
                    bestZ = projectedZ;
                }
            }
        }
        if (!Double.isFinite(bestDistance)) return candidate;
        return normal.getAxis() == Direction.Axis.X
            ? new CobbleventureBootstrap.Point(candidate.x(), roundGateCoordinate(bestZ))
            : new CobbleventureBootstrap.Point(roundGateCoordinate(bestX), candidate.z());
    }

    static CobbleventureBootstrap.Point gateEdgeCenter(
        HexGrid grid, HexCoord anchor, String facing, Predicate<HexCoord> faceIsOpen
    ) {
        List<HexCoord> faces = gateFaceOffsets(facing);
        if (faces.size() == 1) {
            return gateFaceCenter(grid, anchor, faces.getFirst());
        }
        boolean firstOpen = faceIsOpen.test(faces.get(0));
        boolean secondOpen = faceIsOpen.test(faces.get(1));
        if (firstOpen != secondOpen) {
            return gateFaceCenter(
                grid, anchor, firstOpen ? faces.get(0) : faces.get(1)
            );
        }
        CobbleventureBootstrap.Point tile = grid.worldCenter(anchor);
        return new CobbleventureBootstrap.Point(
            tile.x(),
            tile.z() + (facing.equals("north") ? -grid.radius() : grid.radius())
        );
    }

    private static List<HexCoord> gateFaceOffsets(String facing) {
        return switch (facing) {
            case "north" -> List.of(new HexCoord(0, -1), new HexCoord(1, -1));
            case "east" -> List.of(new HexCoord(1, 0));
            case "south" -> List.of(new HexCoord(-1, 1), new HexCoord(0, 1));
            case "west" -> List.of(new HexCoord(-1, 0));
            default -> throw new IllegalStateException(
                "Unsupported gate facing: " + facing
            );
        };
    }

    private static CobbleventureBootstrap.Point gateFaceCenter(
        HexGrid grid, HexCoord anchor, HexCoord offset
    ) {
        CobbleventureBootstrap.Point tile = grid.worldCenter(anchor);
        CobbleventureBootstrap.Point neighbor = grid.worldCenter(anchor.plus(offset));
        return new CobbleventureBootstrap.Point(
            roundGateCoordinate((tile.x() + neighbor.x()) * 0.5D),
            roundGateCoordinate((tile.z() + neighbor.z()) * 0.5D)
        );
    }

    private static int roundGateCoordinate(double value) {
        return value < 0.0D
            ? (int) Math.ceil(value - 0.5D)
            : (int) Math.floor(value + 0.5D);
    }

    private static boolean gateFaceIsOpen(
        HexWorldPlan world, HexCoord anchor, HexCoord offset
    ) {
        return world.cells().containsKey(anchor.plus(offset));
    }

    private static int gateBoundaryHalfLength(
        HexWorldPlan world, Gate gate, int halfOpening
    ) {
        int openFaces = (int) gateFaceOffsets(gate.facing()).stream()
            .filter(offset -> gateFaceIsOpen(world, gate.anchor(), offset))
            .count();
        if (gate.buildingEnabled()
            && (gate.facing().equals("north") || gate.facing().equals("south"))) {
            openFaces = 2;
        }
        return gateBoundaryHalfLength(
            world.grid().radius(), gate.facing(), openFaces, halfOpening
        );
    }

    /** Matches the generated strip to the selected pointy-top hex boundary. */
    static int gateBoundaryHalfLength(
        int radius, String facing, int openFaces, int halfOpening
    ) {
        double boundaryScale;
        if (facing.equals("east") || facing.equals("west")) {
            boundaryScale = 0.5D;
        } else if (openFaces == 1) {
            boundaryScale = Math.sqrt(3.0D) * 0.25D;
        } else {
            boundaryScale = Math.sqrt(3.0D) * 0.5D;
        }
        return Math.max(
            halfOpening + 8,
            (int) Math.round(radius * boundaryScale) - 2
        );
    }

    static int naturalGateBoundaryDepth(int radius, int halfThickness) {
        int terrainBand = Math.min(8, Math.max(3, (int) Math.round(radius * 0.1D)));
        return Math.max(halfThickness + 2, terrainBand);
    }

    static List<RadarLocationCatalog.Location> radarLocations(
        ServerLevel level, HexWorldPlan world, boolean generationDimension
    ) {
        List<RadarLocationCatalog.Location> result = new ArrayList<>();
        for (Gate gate : world.gates()) {
            ForestEntryMarker marker;
            RadarLocationCatalog.Kind kind;
            if (generationDimension) {
                marker = gate.destinationForest() == null
                    ? null : FOREST_ENTRY_MARKERS.get(gate.id());
                if (gate.destinationForest() != null && marker == null) continue;
                kind = gate.destinationForest() == null
                    ? RadarLocationCatalog.Kind.GATE
                    : RadarLocationCatalog.Kind.FOREST_ENTRANCE;
            } else {
                if (gate.forestDimension() == null
                    || !gate.forestDimension().equals(level.dimension())) continue;
                marker = FOREST_EXIT_MARKERS.get(gate.id());
                if (marker == null) continue;
                kind = RadarLocationCatalog.Kind.FOREST_ENTRANCE;
            }

            int x;
            int y;
            int z;
            if (marker != null) {
                x = marker.position().getX();
                y = marker.position().getY();
                z = marker.position().getZ();
            } else {
                CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
                BlockPos completion = new BlockPos(
                    center.x(), world.grid().origin().y() - 16, center.z()
                );
                if (!level.getBlockState(completion).is(Blocks.RESPAWN_ANCHOR)) continue;
                x = center.x();
                z = center.z();
                y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
            result.add(new RadarLocationCatalog.Location(
                "gate/" + gate.id(), kind, level.dimension().location(),
                x + 0.5D, y, z + 0.5D, gate.id(), ""
            ));
        }
        return List.copyOf(result);
    }

    private static String inaccessibleTerrainType(
        HexWorldPlan world, CobbleventureBootstrap.Point center,
        Direction outward
    ) {
        int sampleDistance = world.grid().radius() + 12;
        return CobbleventureBootstrap.emptyTerrainAt(
            world,
            center.x() + outward.getStepX() * sampleDistance + 0.5D,
            center.z() + outward.getStepZ() * sampleDistance + 0.5D
        );
    }

    private static void placeNaturalBarrierColumn(
        ServerLevel level, Gate gate, String terrainType, int x, int z
    ) {
        int groundY = naturalBarrierGroundY(level, x, z);
        for (int height = 1; height <= gate.barrierHeight(); height++) {
            BlockPos position = new BlockPos(x, groundY + height, z);
            BlockState existing = level.getBlockState(position);
            // Preserve complete naturally generated trees. Their collision closes
            // the same part of the boundary without shearing trunks or crowns.
            if (existing.is(BlockTags.LOGS)
                || existing.getBlock() instanceof LeavesBlock) {
                continue;
            }
            if (height == 1 && !existing.isAir() && existing.canBeReplaced()) {
                continue;
            }
            if (existing.isAir() || existing.canBeReplaced()) {
                level.setBlock(position, Blocks.BARRIER.defaultBlockState(), 2);
            }
        }
    }

    private static void decorateNaturalShoulderColumn(
        ServerLevel level, HexWorldPlan world, Gate gate, String terrainType,
        CobbleventureBootstrap.Point center,
        int x, int z, int distance, int offset, boolean treePass
    ) {
        int groundY = naturalBarrierGroundY(level, x, z);
        long hash = mixGateSeed(world.seed(), x, z, distance, offset);
        if (terrainType.equals("high_forest")
            || terrainType.equals("dense_forest")) {
            BlockPos ground = new BlockPos(x, groundY, z);
            // Use the same vanilla placed-feature path as the rest of the world.
            // Forest wedges deliberately use a denser candidate grid than the
            // surrounding terrain. Vanilla placement still rejects crowns that
            // would overlap too tightly, avoiding a handmade leaf wall.
            int absoluteOffset = Math.abs(offset);
            boolean treeCandidate = Math.floorMod(
                distance + absoluteOffset * 2, 4
            ) == 0 && Math.floorMod((int) hash, 3) != 0;
            if (treePass && treeCandidate) {
                // The collision wedge can be only one block deep near the
                // passage. Plant those trees just beyond its center columns so
                // trunks and crowns read as a dense forest instead of exposing
                // a thin invisible barrier line.
                if (absoluteOffset <= 1) {
                    int fringeShift = 1 + Math.floorMod((int) (hash >>> 18), 2);
                    int fringeX = x + Integer.signum(x - center.x()) * fringeShift;
                    int fringeZ = z + Integer.signum(z - center.z()) * fringeShift;
                    BlockPos fringeGround = new BlockPos(
                        fringeX, naturalBarrierGroundY(level, fringeX, fringeZ), fringeZ
                    );
                    if (CobbleventureBootstrap.placeNaturalGateTree(
                        level,
                        naturalGateTreeLog(terrainType),
                        naturalGateTreeLeaves(terrainType),
                        fringeGround, hash ^ 0x6A09E667F3BCC909L
                    )) {
                        return;
                    }
                }
                if (CobbleventureBootstrap.placeNaturalGateTree(
                    level,
                    naturalGateTreeLog(terrainType),
                    naturalGateTreeLeaves(terrainType),
                    ground, hash
                )) return;
            }
            if (treePass) return;
            if (hasNaturalGateTreeOverhead(level, ground)) {
                BlockPos position = ground.above();
                BlockState existing = level.getBlockState(position);
                if (!existing.isAir() && existing.canBeReplaced()
                    && existing.getFluidState().isEmpty()) {
                    level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                }
                return;
            }
            if (Math.floorMod((int) (hash >>> 24), 4) != 0) {
                BlockPos position = ground.above();
                BlockState decoration = CobbleventureBootstrap
                    .naturalGateGroundDecoration(level, terrainType, ground, hash);
                if (decoration != null && level.getBlockState(position).isAir()
                    && decoration.canSurvive(level, position)) {
                    level.setBlock(position, decoration, 2);
                }
            }
            return;
        }
        if (treePass) return;
        // Non-forest surroundings already provide their natural terrain. Add a
        // few irregular boulders instead of extruding every column into a wall.
        if (Math.floorMod((int) hash, 11) != 0) {
            return;
        }
        int boulderHeight = 1 + Math.floorMod((int) (hash >>> 12), 3);
        for (int height = 1; height <= boulderHeight; height++) {
            BlockPos position = new BlockPos(x, groundY + height, z);
            if (!level.getBlockState(position).canBeReplaced()) {
                break;
            }
            BlockState state = switch (terrainType) {
                case "desert" -> Blocks.SANDSTONE.defaultBlockState();
                case "red_rock_mountain" -> Blocks.RED_SANDSTONE.defaultBlockState();
                case "snow_mountain" -> height == boulderHeight
                    ? Blocks.SNOW_BLOCK.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
                default -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            };
            level.setBlock(position, state, 2);
        }
    }

    private static boolean hasNaturalGateTreeOverhead(
        ServerLevel level, BlockPos ground
    ) {
        int top = Math.min(level.getMaxBuildHeight() - 1, ground.getY() + 18);
        for (int y = ground.getY() + 1; y <= top; y++) {
            BlockState state = level.getBlockState(
                new BlockPos(ground.getX(), y, ground.getZ())
            );
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    private static String naturalGateTreeLog(String terrainType) {
        return terrainType.equals("dense_forest")
            ? "minecraft:spruce_log" : "minecraft:dark_oak_log";
    }

    private static String naturalGateTreeLeaves(String terrainType) {
        return terrainType.equals("dense_forest")
            ? "minecraft:spruce_leaves" : "minecraft:dark_oak_leaves";
    }

    private static long mixGateSeed(long seed, int x, int z, int depth, int band) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L
            ^ (long) z * 0xC2B2AE3D27D4EB4FL
            ^ (long) depth * 0x165667B19E3779F9L
            ^ (long) band * 0x85EBCA77C2B2AE63L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    /** Builds shallow, sealed natural shoulders along the selected hex boundary. */
    private static void placeNaturalSurroundings(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center,
        int halfLength, int halfThickness, int halfOpening
    ) {
        Direction normal = facingDirection(gate.facing());
        Direction sideways = normal.getClockWise();
        placeNaturalGateWedges(
            level, world, gate, center, normal, sideways,
            halfLength, halfThickness, halfOpening
        );
    }

    private static void placeNaturalGateWedges(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center, Direction normal, Direction sideways,
        int halfLength, int halfThickness, int halfOpening
    ) {
        if (!gate.buildingEnabled()
            && (gate.facing().equals("north") || gate.facing().equals("south"))) {
            placeNorthSouthNaturalGateEdges(
                level, world, gate, center, halfThickness, halfOpening
            );
            return;
        }
        int maximumDepth = Math.min(
            MAX_NATURAL_GATE_FUNNEL_DEPTH,
            naturalGateBoundaryDepth(world.grid().radius(), halfThickness)
        );
        List<NaturalGateColumn> columns = new ArrayList<>();
        for (int shoulderSign : new int[] {-1, 1}) {
            Direction sampleDirection = shoulderSign < 0
                ? sideways.getOpposite() : sideways;
            String terrainType = inaccessibleTerrainType(
                world, center, sampleDirection
            );
            int shoulderLength = gateHasOpenFace(world, gate)
                ? naturalGateLengthToInaccessible(
                    world, center,
                    sideways.getStepX() * shoulderSign,
                    sideways.getStepZ() * shoulderSign,
                    halfOpening, halfLength
                )
                : halfLength;
            int availableLength = Math.max(1, shoulderLength - halfOpening);
            for (int distance = halfOpening + 1;
                distance <= shoulderLength; distance++) {
                double progress = (distance - halfOpening) / (double) availableLength;
                double curvedProgress = progress * progress * (3.0D - 2.0D * progress);
                long edgeHash = mixGateSeed(
                    world.seed(), center.x(), center.z(), distance, shoulderSign
                );
                int edgeVariation = progress < 0.15D
                    ? 0 : Math.floorMod((int) edgeHash, 5) - 2;
                int shoulderDepth = Math.max(
                    1,
                    Math.min(
                        maximumDepth,
                        1 + (int) Math.round((maximumDepth - 1) * curvedProgress)
                            + edgeVariation
                    )
                );
                int lateral = shoulderSign * distance;
                for (int offset = -shoulderDepth;
                    offset <= shoulderDepth; offset++) {
                    int x = center.x() + sideways.getStepX() * lateral
                        + normal.getStepX() * offset;
                    int z = center.z() + sideways.getStepZ() * lateral
                        + normal.getStepZ() * offset;
                    columns.add(new NaturalGateColumn(
                        x, z, terrainType, distance, offset
                    ));
                }
            }
        }
        finishNaturalGateColumns(
            level, world, gate, center, columns, maximumDepth, halfOpening
        );
    }

    private static void placeNorthSouthNaturalGateEdges(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center, int halfThickness, int halfOpening
    ) {
        List<HexCoord> faces = gateFaceOffsets(gate.facing());
        boolean firstOpen = gateFaceIsOpen(world, gate.anchor(), faces.get(0));
        boolean secondOpen = gateFaceIsOpen(world, gate.anchor(), faces.get(1));
        int maximumDepth = Math.min(
            MAX_NATURAL_GATE_FUNNEL_DEPTH,
            naturalGateBoundaryDepth(world.grid().radius(), halfThickness)
        );
        Map<Long, NaturalGateColumn> uniqueColumns = new LinkedHashMap<>();
        if (firstOpen != secondOpen) {
            HexCoord openFace = firstOpen ? faces.get(0) : faces.get(1);
            GateEdgeVector tangent = gateFaceTangent(gate.facing(), openFace);
            int halfFaceLength = Math.max(
                halfOpening + 8, world.grid().radius() / 2 - 2
            );
            for (int sign : new int[] {-1, 1}) {
                int edgeLength = naturalGateLengthToInaccessible(
                    world, center,
                    tangent.x() * sign, tangent.z() * sign,
                    halfOpening, halfFaceLength
                );
                addNaturalGateEdgeBand(
                    world, center, tangent.x() * sign, tangent.z() * sign,
                    halfOpening, edgeLength, maximumDepth, sign, uniqueColumns
                );
            }
        } else {
            double vertical = gate.facing().equals("north") ? 0.5D : -0.5D;
            int fallbackLength = Math.max(
                halfOpening + 8, world.grid().radius() - 2
            );
            for (int sign : new int[] {-1, 1}) {
                int faceLength = firstOpen && secondOpen
                    ? naturalGateLengthToInaccessible(
                        world, center,
                        sign * Math.sqrt(3.0D) * 0.5D,
                        vertical,
                        halfOpening, fallbackLength
                    )
                    : fallbackLength;
                addNaturalGateEdgeBand(
                    world, center, sign * Math.sqrt(3.0D) * 0.5D, vertical,
                    halfOpening, faceLength, maximumDepth, sign, uniqueColumns
                );
            }
        }
        List<NaturalGateColumn> columns = List.copyOf(uniqueColumns.values());
        finishNaturalGateColumns(
            level, world, gate, center, columns, maximumDepth, halfOpening
        );
    }

    private static boolean gateHasOpenFace(HexWorldPlan world, Gate gate) {
        return gateHasOpenFace(
            gate.facing(),
            offset -> gateFaceIsOpen(world, gate.anchor(), offset)
        );
    }

    static boolean gateHasOpenFace(
        String facing, Predicate<HexCoord> faceIsOpen
    ) {
        return gateFaceOffsets(facing).stream().anyMatch(faceIsOpen);
    }

    private static int naturalGateLengthToInaccessible(
        HexWorldPlan world, CobbleventureBootstrap.Point center,
        double directionX, double directionZ,
        int halfOpening, int fallbackLength
    ) {
        int maximumSearch = Math.max(fallbackLength, world.grid().radius() * 4);
        for (int distance = halfOpening + 1;
            distance <= maximumSearch; distance++) {
            double x = center.x() + directionX * distance;
            double z = center.z() + directionZ * distance;
            if (CobbleventureBootstrap.isInaccessibleTerrainAt(
                world, x + 0.5D, z + 0.5D
            )) {
                return distance;
            }
        }
        return fallbackLength;
    }

    private static GateEdgeVector gateFaceTangent(String facing, HexCoord face) {
        double diagonalX = Math.sqrt(3.0D) * 0.5D;
        return switch (facing) {
            case "north" -> face.equals(new HexCoord(0, -1))
                ? new GateEdgeVector(diagonalX, -0.5D)
                : new GateEdgeVector(diagonalX, 0.5D);
            case "south" -> face.equals(new HexCoord(-1, 1))
                ? new GateEdgeVector(diagonalX, 0.5D)
                : new GateEdgeVector(diagonalX, -0.5D);
            default -> throw new IllegalStateException(
                "Diagonal gate edge requested for facing: " + facing
            );
        };
    }

    private static void addNaturalGateEdgeBand(
        HexWorldPlan world, CobbleventureBootstrap.Point center,
        double tangentX, double tangentZ, int halfOpening, int maximumLength,
        int maximumDepth, int band, Map<Long, NaturalGateColumn> columns
    ) {
        int availableLength = Math.max(1, maximumLength - halfOpening);
        String terrainType = CobbleventureBootstrap.emptyTerrainAt(
            world,
            center.x() + tangentX * (maximumLength + 12) + 0.5D,
            center.z() + tangentZ * (maximumLength + 12) + 0.5D
        );
        double normalX = -tangentZ;
        double normalZ = tangentX;
        for (int distance = halfOpening + 1; distance <= maximumLength; distance++) {
            double progress = (distance - halfOpening) / (double) availableLength;
            double curvedProgress = progress * progress * (3.0D - 2.0D * progress);
            long edgeHash = mixGateSeed(
                world.seed(), center.x(), center.z(), distance, band
            );
            int edgeVariation = progress < 0.15D
                ? 0 : Math.floorMod((int) edgeHash, 3) - 1;
            int shoulderDepth = Math.max(
                1,
                Math.min(
                    maximumDepth,
                    1 + (int) Math.round((maximumDepth - 1) * curvedProgress)
                        + edgeVariation
                )
            );
            for (int offset = -shoulderDepth; offset <= shoulderDepth; offset++) {
                int x = roundGateCoordinate(
                    center.x() + tangentX * distance + normalX * offset
                );
                int z = roundGateCoordinate(
                    center.z() + tangentZ * distance + normalZ * offset
                );
                NaturalGateColumn column = new NaturalGateColumn(
                    x, z, terrainType, distance, offset
                );
                columns.putIfAbsent(new BlockPos(x, 0, z).asLong(), column);
            }
        }
    }

    private static void finishNaturalGateColumns(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center,
        List<NaturalGateColumn> columns, int maximumDepth, int halfOpening
    ) {
        StructureFootprint structureFootprint = gate.buildingEnabled()
            ? plannedStructureFootprint(level, gate, center)
            : null;
        StructureFootprint treeProtection = structureFootprint;
        if (treeProtection != null) {
            treeProtection = treeProtection.expanded(
                GATE_STRUCTURE_NATURAL_CLEARANCE
            );
        }
        final StructureFootprint decorationBoundary = treeProtection;
        // The post-structure pass recalculates collision against the now-visible
        // facade, so discard only barriers from the earlier pass.
        for (NaturalGateColumn column : columns) {
            clearNaturalWedgeBarriers(
                level, gate, column.x(), column.z()
            );
        }
        List<NaturalGateColumn> decorationColumns = decorationBoundary == null
            ? columns
            : columns.stream()
                .filter(column -> !decorationBoundary.contains(column.x(), column.z()))
                .toList();
        List<NaturalGateColumn> barrierColumns = structureFootprint == null
            ? columns
            : columns.stream()
                .filter(column -> !structureFootprint.contains(column.x(), column.z())
                    || isExteriorGateSeamColumn(
                        level, world, gate, structureFootprint,
                        column.x(), column.z()
                    ))
                .toList();
        // Pass 1: place all trees while the entire growth volume is still open.
        for (NaturalGateColumn column : decorationColumns) {
            decorateNaturalShoulderColumn(
                level, world, gate, column.terrainType(),
                center,
                column.x(), column.z(), column.distance(), column.offset(), true
            );
        }
        // Pass 2: add ground cover only after every crown is known. Columns
        // below logs or leaves are cleared instead of becoming overgrown.
        for (NaturalGateColumn column : decorationColumns) {
            decorateNaturalShoulderColumn(
                level, world, gate, column.terrainType(),
                center,
                column.x(), column.z(), column.distance(), column.offset(), false
            );
        }
        // Pass 3: fill every remaining replaceable space in the wedge with an
        // invisible barrier. Natural blocks stay visible and no gap remains.
        for (NaturalGateColumn column : barrierColumns) {
            placeNaturalBarrierColumn(
                level, gate, column.terrainType(), column.x(), column.z()
            );
        }
        LOGGER.info(
            "Natural gate boundary placed: gate={}, trees={}, barriers={}, protected={}, maxDepth={}, opening={}",
            gate.id(), decorationColumns.size(), barrierColumns.size(),
            columns.size() - barrierColumns.size(),
            maximumDepth, halfOpening * 2 + 1
        );
    }

    /**
     * Extends collision through empty NBT padding only until the first actual
     * facade column. Each cross-section is scanned independently so recessed
     * walls are sealed without continuing through them into an interior.
     */
    private static boolean isExteriorGateSeamColumn(
        ServerLevel level, HexWorldPlan world, Gate gate,
        StructureFootprint footprint, int x, int z
    ) {
        boolean northSouth = gate.facing().equals("north")
            || gate.facing().equals("south");
        int lateral = northSouth ? x : z;
        int minimum = northSouth ? footprint.minX() : footprint.minZ();
        int maximum = northSouth ? footprint.maxX() : footprint.maxZ();
        int midpoint = (minimum + maximum) / 2;
        int step = lateral <= midpoint ? 1 : -1;
        int cursor = step > 0 ? minimum : maximum;
        while (cursor >= minimum && cursor <= maximum) {
            int scanX = northSouth ? cursor : x;
            int scanZ = northSouth ? z : cursor;
            if (gateStructureColumnOccupied(level, world, gate, scanX, scanZ)) {
                return step > 0 ? lateral < cursor : lateral > cursor;
            }
            cursor += step;
        }
        // Before placement no facade is available to stop the scan. The
        // post-placement refresh performs the exact seam fill.
        return false;
    }

    private static boolean gateStructureColumnOccupied(
        ServerLevel level, HexWorldPlan world, Gate gate, int x, int z
    ) {
        int nativeY = CobbleventureBootstrap.nativeTerrainColumn(
            world, x, z
        ).groundY();
        for (int y = nativeY + 1; y <= nativeY + gate.barrierHeight(); y++) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.canBeReplaced()
                && !state.is(Blocks.BARRIER)) {
                return true;
            }
        }
        return false;
    }

    private static void clearNaturalWedgeBarriers(
        ServerLevel level, Gate gate, int x, int z
    ) {
        int groundY = naturalBarrierGroundY(level, x, z);
        for (int height = 1; height <= gate.barrierHeight(); height++) {
            BlockPos position = new BlockPos(x, groundY + height, z);
            if (level.getBlockState(position).is(Blocks.BARRIER)) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static int naturalBarrierGroundY(ServerLevel level, int x, int z) {
        int top = groundY(level, x, z);
        BlockState topState = level.getBlockState(new BlockPos(x, top, z));
        if (!topState.is(Blocks.BARRIER) && !topState.is(BlockTags.LOGS)
            && !(topState.getBlock() instanceof LeavesBlock)) {
            return top;
        }
        for (int y = top; y > level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.is(Blocks.BARRIER) || state.is(BlockTags.LOGS)
                || state.getBlock() instanceof LeavesBlock || state.isAir()
                || state.canBeReplaced()) {
                continue;
            }
            return y;
        }
        return top;
    }

    private static int forestFunnelGroundY(ServerLevel level, int x, int z) {
        int heightmapGround = groundY(level, x, z);
        if (!level.getBlockState(new BlockPos(x, heightmapGround, z)).is(Blocks.BARRIER)) {
            return heightmapGround;
        }
        for (int y = heightmapGround; y > level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.PODZOL)) {
                return y;
            }
        }
        return heightmapGround;
    }
    /** Seals the thin wall padding after the authored gatehouse is placed. */
    private static void sealWallSurroundingAfterStructure(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, int halfLength, int halfThickness, int halfOpening,
        Map<Long, Integer> groundHeights, StructureFootprint structureFootprint
    ) {
        BlockState wall = blockState(gate.wallBlock());
        for (int along = -halfLength; along <= halfLength; along++) {
            if (Math.abs(along) <= halfOpening) {
                continue;
            }
            for (int across = -halfThickness; across <= halfThickness; across++) {
                int x = center.x() + (horizontal ? along : across);
                int z = center.z() + (horizontal ? across : along);
                // Only thin outer padding may be sealed. Air farther inside
                // belongs to authored rooms and the walk-through passage.
                if (structureFootprint != null
                    && structureFootprint.containsInterior(x, z, 2)) {
                    continue;
                }
                int groundY = groundHeights.getOrDefault(
                    new BlockPos(x, 0, z).asLong(), groundY(level, x, z)
                );
                for (int height = 1; height <= gate.wallHeight(); height++) {
                    BlockPos position = new BlockPos(x, groundY + height, z);
                    if (level.getBlockState(position).isAir()) {
                        level.setBlock(position, wall, 2);
                    }
                }
                for (int height = gate.wallHeight() + 1;
                    height <= gate.barrierHeight(); height++) {
                    BlockPos position = new BlockPos(x, groundY + height, z);
                    if (level.getBlockState(position).isAir()) {
                        level.setBlock(position, Blocks.BARRIER.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void layGateApproachRoads(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center, StructureFootprint footprint,
        List<GateEntrancePlacement> entrances, int halfThickness
    ) {
        if (!entrances.isEmpty()) {
            for (GateEntrancePlacement entrance : entrances) {
                layAnchoredGateApproach(level, world, entrance, false);
            }
            return;
        }
        Direction normal = facingDirection(gate.facing());
        Direction sideways = normal.getClockWise();
        for (int sign : new int[] {-1, 1}) {
            int outwardX = normal.getStepX() * sign;
            int outwardZ = normal.getStepZ() * sign;
            int edgeX = footprint == null
                ? center.x() + outwardX * halfThickness
                : normal.getAxis() == Direction.Axis.X
                    ? (outwardX < 0 ? footprint.minX() : footprint.maxX())
                    : center.x();
            int edgeZ = footprint == null
                ? center.z() + outwardZ * halfThickness
                : normal.getAxis() == Direction.Axis.Z
                    ? (outwardZ < 0 ? footprint.minZ() : footprint.maxZ())
                    : center.z();
            for (int depth = 1; depth <= 8; depth++) {
                int roadX = edgeX + outwardX * depth;
                int roadZ = edgeZ + outwardZ * depth;
                for (int lateral = -1; lateral <= 1; lateral++) {
                    int x = roadX + sideways.getStepX() * lateral;
                    int z = roadZ + sideways.getStepZ() * lateral;
                    int groundY = CobbleventureBootstrap.prepareWorldRoadColumn(
                        level, world, x, z
                    );
                    level.setBlock(
                        new BlockPos(x, groundY, z),
                        CobbleventureBootstrap.worldRoadSurfaceBlock(world, x, z), 2
                    );
                }
            }
        }
    }

    private static void layAnchoredGateApproach(
        ServerLevel level, HexWorldPlan world, GateEntrancePlacement entrance,
        boolean finishAfterStructure
    ) {
        Direction outward = entrance.outward();
        Direction sideways = outward.getClockWise();
        int previousY = entrance.surfaceY();
        List<Integer> heights = new ArrayList<>();
        for (int depth = 1; depth <= MAX_GATE_APPROACH_DEPTH; depth++) {
            int centerX = entrance.x() + outward.getStepX() * depth;
            int centerZ = entrance.z() + outward.getStepZ() * depth;
            int nativeY = CobbleventureBootstrap.nativeTerrainColumn(
                world, centerX, centerZ
            ).groundY();
            int targetY = finishAfterStructure
                && entrance.footprint().contains(centerX, centerZ)
                    ? entrance.surfaceY() : nativeY;
            int roadY = nextGateApproachY(previousY, targetY);
            heights.add(roadY);
            previousY = roadY;
            if (depth >= MIN_GATE_APPROACH_DEPTH
                && !entrance.footprint().contains(centerX, centerZ)
                && roadY == nativeY) {
                break;
            }
        }
        for (int index = 0; index < heights.size(); index++) {
            int depth = index + 1;
            int centerX = entrance.x() + outward.getStepX() * depth;
            int centerZ = entrance.z() + outward.getStepZ() * depth;
            int roadY = heights.get(index);
            int priorY = index == 0 ? entrance.surfaceY() : heights.get(index - 1);
            Integer nextY = index + 1 < heights.size()
                ? heights.get(index + 1) : null;
            Direction ascent = gateApproachAscent(
                priorY, roadY, nextY, outward
            );
            for (int lateral = entrance.minimumLateral();
                lateral <= entrance.maximumLateral(); lateral++) {
                int x = centerX + sideways.getStepX() * lateral;
                int z = centerZ + sideways.getStepZ() * lateral;
                if (!finishAfterStructure && entrance.footprint().contains(x, z)) {
                    continue;
                }
                CobbleventureBootstrap.prepareWorldRoadColumnAtY(
                    level, world, x, z, roadY
                );
                level.setBlock(
                    new BlockPos(x, roadY, z),
                    CobbleventureBootstrap.worldRoadSurfaceBlock(
                        world, x, z, ascent
                    ), 2
                );
            }
        }
    }

    private static void finishGateRoadAnchorApproaches(
        ServerLevel level, HexWorldPlan world,
        List<GateEntrancePlacement> entrances
    ) {
        for (GateEntrancePlacement entrance : entrances) {
            layAnchoredGateApproach(level, world, entrance, true);
        }
    }

    static int nextGateApproachY(int previousY, int nativeY) {
        return Math.max(previousY - 1, Math.min(previousY + 1, nativeY));
    }

    static Direction gateApproachAscent(
        int priorY, int roadY, Integer nextY, Direction outward
    ) {
        if (nextY != null && nextY > roadY) {
            return outward;
        }
        return priorY > roadY ? outward.getOpposite() : null;
    }

    private static void placeOverheadBarrier(
        ServerLevel level, int x, int z, int groundY, int visibleHeight, int barrierHeight
    ) {
        for (int height = visibleHeight + 1; height <= barrierHeight; height++) {
            BlockPos position = new BlockPos(x, groundY + height, z);
            if (level.getBlockState(position).isAir()) {
                level.setBlock(position, Blocks.BARRIER.defaultBlockState(), 2);
            }
        }
    }

    private static GateStructurePlacement placeStructure(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center, int groundY
    ) {
        if (gate.structure() == null) {
            LOGGER.error("Gate building is enabled but structure is missing: gate={}", gate.id());
            return null;
        }
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        var template = structureId == null
            ? java.util.Optional.<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            LOGGER.error("Gate structure is missing: gate={}, structure={}", gate.id(), gate.structure());
            return null;
        }
        Rotation rotation = rotation(gate.rotation());
        StructureTemplate structure = template.orElseThrow();
        Vec3i size = structure.getSize(rotation);
        int minX = center.x() - size.getX() / 2;
        int minZ = center.z() - size.getZ() / 2;
        BlockPos origin = rotatedTemplateOrigin(
            minX, groundY, minZ,
            structure.getSize().getX(), structure.getSize().getZ(), rotation
        );
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
            .addProcessor(GroundFloorAirPreservationProcessor.INSTANCE);
        ExplicitAirPlacementProcessor.configure(structure, settings);
        BlockPos protectionGround = new BlockPos(center.x(), groundY, center.z());
        int protectionRadius = Math.max(size.getX(), size.getZ()) / 2 + 3;
        int protectionHeight = size.getY() + 8;
        Set<Long> existingTreeBlocks = CobbleventureBootstrap.treeBlocksInVolume(
            level, protectionGround, protectionRadius, protectionHeight
        );
        if (!structure.placeInWorld(
            level, origin, origin, settings,
            RandomSource.create(level.getSeed() ^ origin.asLong()), 2
        )) {
            LOGGER.error("Gate structure placement failed: gate={}, origin={}", gate.id(), origin);
            return null;
        }
        replacePlacedGateRoadAnchors(level, structure, origin, settings);
        CobbleventureBootstrap.markNewTreeLeavesPersistent(
            level, protectionGround, existingTreeBlocks,
            protectionRadius, protectionHeight
        );
        CobbleventureBootstrap.scheduleGenerationDebrisCleanup(
            level, gate.structure(), origin, structure, rotation
        );
        StructureFootprint footprint = new StructureFootprint(
            minX, minZ,
            minX + size.getX() - 1,
            minZ + size.getZ() - 1
        );
        return new GateStructurePlacement(footprint);
    }

    private static StructureFootprint plannedStructureFootprint(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center
    ) {
        if (gate.structure() == null) return null;
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        if (structureId == null) return null;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return null;
        Vec3i size = template.orElseThrow().getSize(rotation(gate.rotation()));
        int minX = center.x() - size.getX() / 2;
        int minZ = center.z() - size.getZ() / 2;
        return new StructureFootprint(
            minX, minZ,
            minX + size.getX() - 1,
            minZ + size.getZ() - 1
        );
    }

    private static int roadAlignedGateOriginY(
        ServerLevel level, HexWorldPlan world, Gate gate,
        StructureFootprint footprint, int fallbackY
    ) {
        if (gate.structure() == null) return fallbackY;
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        if (structureId == null) return fallbackY;
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return fallbackY;
        StructureTemplate structure = template.orElseThrow();
        Rotation rotation = rotation(gate.rotation());
        int width = structure.getSize().getX();
        int depth = structure.getSize().getZ();
        List<Integer> alignedOriginHeights = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo marker : structure.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW
        )) {
            if (marker.nbt() == null
                || !ROAD_ANCHOR_MARKER.equals(marker.nbt().getString("name"))) {
                continue;
            }
            Direction outward = rotateDirection(
                JigsawBlock.getFrontFacing(marker.state()), rotation
            );
            if (!outward.getAxis().isHorizontal()) continue;
            BlockPos rotated = rotatedTemplateOffset(
                marker.pos(), width, depth, rotation
            );
            int roadX = footprint.minX() + rotated.getX();
            int roadZ = footprint.minZ() + rotated.getZ();
            int guard = Math.max(width, depth) + 2;
            while (guard-- > 0 && footprint.contains(roadX, roadZ)) {
                roadX += outward.getStepX();
                roadZ += outward.getStepZ();
            }
            int roadY = CobbleventureBootstrap.nativeTerrainColumn(
                world, roadX, roadZ
            ).groundY();
            // A road_anchor now occupies the authored road surface itself.
            // Align the whole pass-through structure to the lower entrance so
            // uneven terrain never leaves its lower side hanging in the air.
            alignedOriginHeights.add(roadY - marker.pos().getY());
        }
        return lowestRoadAlignedGateOriginY(
            fallbackY, alignedOriginHeights
        );
    }

    static int lowestRoadAlignedGateOriginY(
        int fallbackY, List<Integer> alignedOriginHeights
    ) {
        return alignedOriginHeights.stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElse(fallbackY);
    }

    private static List<GateEntrancePlacement> plannedGateRoadAnchors(
        ServerLevel level, Gate gate, StructureFootprint footprint, int originY
    ) {
        if (gate.structure() == null) return List.of();
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        if (structureId == null) return List.of();
        var template = level.getStructureManager().get(structureId);
        if (template.isEmpty()) return List.of();
        StructureTemplate structure = template.orElseThrow();
        Rotation rotation = rotation(gate.rotation());
        int width = structure.getSize().getX();
        int depth = structure.getSize().getZ();
        List<StructureTemplate.StructureBlockInfo> markers = structure.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW
        ).stream().filter(info -> info.nbt() != null
            && ROAD_ANCHOR_MARKER.equals(info.nbt().getString("name"))).toList();
        if (markers.isEmpty()) {
            LOGGER.error(
                "Gate NBT has no required {} jigsaws: gate={}, structure={}",
                ROAD_ANCHOR_MARKER, gate.id(), gate.structure()
            );
            return List.of();
        }
        if (markers.size() != 2) {
            LOGGER.error(
                "Pass-through gate NBT requires exactly two {} jigsaws: gate={}, structure={}, found={}",
                ROAD_ANCHOR_MARKER, gate.id(), gate.structure(), markers.size()
            );
            return List.of();
        }
        List<GateEntrancePlacement> entrances = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo marker : markers) {
            Direction outward = rotateDirection(
                JigsawBlock.getFrontFacing(marker.state()), rotation
            );
            if (!outward.getAxis().isHorizontal()) {
                LOGGER.error(
                    "Gate road anchor jigsaw must face horizontally: gate={}, position={}, facing={}",
                    gate.id(), marker.pos(), outward
                );
                return List.of();
            }
            BlockPos rotated = rotatedTemplateOffset(
                marker.pos(), width, depth, rotation
            );
            GateApproachWidth approachWidth = plannedGateApproachWidth(
                level, structure, marker, rotated, rotation,
                width, depth, outward
            );
            entrances.add(new GateEntrancePlacement(
                footprint.minX() + rotated.getX(),
                footprint.minZ() + rotated.getZ(),
                originY + marker.pos().getY(), outward, footprint,
                approachWidth.minimumLateral(), approachWidth.maximumLateral()
            ));
        }
        return List.copyOf(entrances);
    }

    private static GateApproachWidth plannedGateApproachWidth(
        ServerLevel level, StructureTemplate structure,
        StructureTemplate.StructureBlockInfo marker, BlockPos rotatedMarker,
        Rotation rotation, int width, int depth, Direction outward
    ) {
        if (marker.nbt() == null) {
            return GateApproachWidth.DEFAULT;
        }
        try {
            BlockState finalState = BlockStateParser.parseForBlock(
                level.holderLookup(Registries.BLOCK),
                marker.nbt().getString("final_state"), false
            ).blockState();
            Set<BlockPos> authoredSurface = new java.util.HashSet<>();
            for (StructureTemplate.StructureBlockInfo info : structure.filterBlocks(
                BlockPos.ZERO, new StructurePlaceSettings(), finalState.getBlock()
            )) {
                if (info.pos().getY() != marker.pos().getY()) {
                    continue;
                }
                authoredSurface.add(rotatedTemplateOffset(
                    info.pos(), width, depth, rotation
                ));
            }
            return contiguousGateApproachWidth(
                rotatedMarker, outward.getClockWise(), authoredSurface
            );
        } catch (CommandSyntaxException | RuntimeException error) {
            LOGGER.warn(
                "Invalid gate road anchor final_state while resolving approach width: position={}, value={}",
                marker.pos(), marker.nbt().getString("final_state")
            );
            return GateApproachWidth.DEFAULT;
        }
    }

    static GateApproachWidth contiguousGateApproachWidth(
        BlockPos anchor, Direction sideways, Set<BlockPos> authoredSurface
    ) {
        int minimum = 0;
        while (authoredSurface.contains(
            anchor.relative(sideways, minimum - 1)
        )) {
            minimum--;
        }
        int maximum = 0;
        while (authoredSurface.contains(
            anchor.relative(sideways, maximum + 1)
        )) {
            maximum++;
        }
        return minimum == 0 && maximum == 0
            ? GateApproachWidth.DEFAULT
            : new GateApproachWidth(minimum, maximum);
    }

    private static void replacePlacedGateRoadAnchors(
        ServerLevel level, StructureTemplate structure, BlockPos origin,
        StructurePlaceSettings settings
    ) {
        for (StructureTemplate.StructureBlockInfo info : structure.filterBlocks(
            origin, settings, Blocks.JIGSAW
        )) {
            if (info.nbt() == null
                || !ROAD_ANCHOR_MARKER.equals(info.nbt().getString("name"))) {
                continue;
            }
            BlockState replacement = Blocks.AIR.defaultBlockState();
            try {
                replacement = BlockStateParser.parseForBlock(
                    level.holderLookup(Registries.BLOCK),
                    info.nbt().getString("final_state"), false
                ).blockState();
                if (replacement.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    replacement = replacement.setValue(
                        BlockStateProperties.HORIZONTAL_FACING,
                        JigsawBlock.getFrontFacing(info.state()).getOpposite()
                    );
                }
            } catch (CommandSyntaxException | RuntimeException error) {
                LOGGER.warn(
                    "Invalid gate road anchor final_state; replacing with air: position={}, value={}",
                    info.pos(), info.nbt().getString("final_state")
                );
            }
            level.setBlock(info.pos(), replacement, 2);
        }
    }

    private static Direction rotateDirection(
        Direction direction, Rotation rotation
    ) {
        if (direction == null) return null;
        return switch (rotation) {
            case CLOCKWISE_90 -> direction.getClockWise();
            case CLOCKWISE_180 -> direction.getOpposite();
            case COUNTERCLOCKWISE_90 -> direction.getCounterClockWise();
            default -> direction;
        };
    }

    private static boolean placeForestStructure(
        ServerLevel level, HexWorldPlan world, Gate gate
    ) {
        ForestGateGeometry geometry = forestGateGeometry(world, gate);
        int groundY = forestGateApproachFloorY(level, world, gate, geometry);
        ForestTemplatePlacement placement = forestTemplatePlacement(
            level, gate, geometry, groundY
        );
        if (placement == null) {
            return false;
        }
        ForestEntryMarker entry = placedForestEntryMarker(gate, placement);
        if (entry == null) {
            return false;
        }
        List<GateEntrancePlacement> roadAnchors = placedForestRoadAnchors(
            gate, placement
        );
        if (roadAnchors.isEmpty()) {
            return false;
        }
        if (!placement.template().placeInWorld(
            level, placement.origin(), placement.origin(), placement.settings(),
            RandomSource.create(level.getSeed() ^ placement.origin().asLong()), 2
        )) {
            LOGGER.error(
                "Forest gate NBT placement failed: gate={}, structure={}, origin={}",
                gate.id(), gate.structure(), placement.origin()
            );
            return false;
        }
        CobbleventureBootstrap.scheduleGenerationDebrisCleanup(
            level, gate.structure(), placement.origin(), placement.template(),
            placement.rotation()
        );
        layForestGateApproach(level, world, gate, placement, roadAnchors);
        FOREST_ENTRY_MARKERS.put(gate.id(), entry);
        level.setBlock(entry.position(), Blocks.AIR.defaultBlockState(), 2);
        LOGGER.info(
            "Forest gate NBT placed on boundary: gate={}, entry={}, inward={}, origin={}",
            gate.id(), entry.position(), entry.inward(), placement.origin()
        );
        return true;
    }

    private static void layForestGateApproach(
        ServerLevel level, HexWorldPlan world, Gate gate,
        ForestTemplatePlacement placement,
        List<GateEntrancePlacement> roadAnchors
    ) {
        replacePlacedGateRoadAnchors(
            level, placement.template(), placement.origin(), placement.settings()
        );
        CobbleventureBootstrap.Point roadEndpoint = alignedGateCenter(world, gate);
        GateEntrancePlacement roadAnchor = nearestForestRoadAnchor(
            roadAnchors, roadEndpoint
        );
        layWorldForestEntranceRoad(
            level, world, roadAnchor, roadEndpoint, placement.footprint()
        );
    }

    static void placeForestDimensionGates(
        ServerLevel level, List<Gate> gates
    ) {
        FOREST_EXIT_MARKERS.clear();
        for (Gate gate : gates) {
            if (gate.forestDimension() == null
                || gate.forestDestination() == null
                || gate.forestPortalAnchor() == null
                || level.dimension() != gate.forestDimension()) {
                continue;
            }
            CobbleventureBootstrap.BlockPoint portal = gate.forestPortalAnchor();
            CobbleventureBootstrap.BlockPoint destination = gate.forestDestination();
            Direction inward = horizontalDirection(
                destination.x() - portal.x(), destination.z() - portal.z()
            );
            int gateY = safeForestGateStandY(
                level, portal.x(), portal.z(), portal.y()
            );
            ForestTemplatePlacement placement = forestTemplatePlacement(
                level, gate,
                new ForestGateGeometry(portal.x(), portal.z(), inward),
                gateY - 1
            );
            if (placement == null || !placement.template().placeInWorld(
                level, placement.origin(), placement.origin(), placement.settings(),
                RandomSource.create(level.getSeed() ^ placement.origin().asLong()), 2
            )) {
                throw new IllegalStateException(
                    "Forest dimension gate placement failed: " + gate.id()
                );
            }
            CobbleventureBootstrap.scheduleGenerationDebrisCleanup(
                level, gate.structure(), placement.origin(), placement.template(),
                placement.rotation()
            );
            replacePlacedGateRoadAnchors(
                level, placement.template(), placement.origin(), placement.settings()
            );
            ForestEntryMarker exit = placedForestEntryMarker(gate, placement);
            if (exit == null) {
                throw new IllegalStateException(
                    "Forest dimension gate marker is invalid: " + gate.id()
                );
            }
            FOREST_EXIT_MARKERS.put(gate.id(), exit);
            level.setBlock(exit.position(), Blocks.AIR.defaultBlockState(), 2);
            LOGGER.info(
                "Forest dimension gate placed on terrain: gate={}, entry={}, groundY={}, inward={}, origin={}",
                gate.id(), exit.position(), gateY - 1, exit.inward(), placement.origin()
            );
        }
    }

    private static void layWorldForestEntranceRoad(
        ServerLevel level, HexWorldPlan world, GateEntrancePlacement roadAnchor,
        CobbleventureBootstrap.Point roadEndpoint, StructureFootprint footprint
    ) {
        double deltaX = roadEndpoint.x() - roadAnchor.x();
        double deltaZ = roadEndpoint.z() - roadAnchor.z();
        int length = Math.max(1, (int) Math.ceil(Math.hypot(deltaX, deltaZ)));
        Direction sideways = roadAnchor.outward().getClockWise();
        int previousY = roadAnchor.surfaceY();
        for (int depth = 0; depth <= length; depth++) {
            double progress = depth / (double) length;
            int centerX = roadAnchor.x() + (int) Math.round(deltaX * progress);
            int centerZ = roadAnchor.z() + (int) Math.round(deltaZ * progress);
            if (footprint.contains(centerX, centerZ)) {
                continue;
            }
            int nativeY = CobbleventureBootstrap.nativeTerrainColumn(
                world, centerX, centerZ
            ).groundY();
            int roadY = nextGateApproachY(previousY, nativeY);
            for (int lateral = -1; lateral <= 1; lateral++) {
                int x = centerX + sideways.getStepX() * lateral;
                int z = centerZ + sideways.getStepZ() * lateral;
                if (footprint.contains(x, z)) {
                    continue;
                }
                CobbleventureBootstrap.prepareWorldRoadColumnAtY(
                    level, world, x, z, roadY
                );
                level.setBlock(
                    new BlockPos(x, roadY, z),
                    CobbleventureBootstrap.worldRoadSurfaceBlock(world, x, z), 2
                );
            }
            previousY = roadY;
        }
    }

    private static List<GateEntrancePlacement> placedForestRoadAnchors(
        Gate gate, ForestTemplatePlacement placement
    ) {
        List<GateEntrancePlacement> anchors = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo marker
            : placement.template().filterBlocks(
                placement.origin(), placement.settings(), Blocks.JIGSAW
            )) {
            if (marker.nbt() == null
                || !ROAD_ANCHOR_MARKER.equals(marker.nbt().getString("name"))) {
                continue;
            }
            Direction outward = JigsawBlock.getFrontFacing(marker.state());
            if (!outward.getAxis().isHorizontal()) {
                LOGGER.error(
                    "Forest gate road anchor must face horizontally: gate={}, position={}, facing={}",
                    gate.id(), marker.pos(), outward
                );
                continue;
            }
            anchors.add(new GateEntrancePlacement(
                marker.pos().getX(), marker.pos().getZ(),
                marker.pos().getY(), outward, placement.footprint(),
                -1, 1
            ));
        }
        if (anchors.isEmpty()) {
            LOGGER.error(
                "Forest gate NBT requires at least one {} jigsaw: gate={}, structure={}",
                ROAD_ANCHOR_MARKER, gate.id(), gate.structure()
            );
        }
        return List.copyOf(anchors);
    }

    private static GateEntrancePlacement nearestForestRoadAnchor(
        List<GateEntrancePlacement> anchors,
        CobbleventureBootstrap.Point roadEndpoint
    ) {
        return anchors.stream().min(Comparator.comparingLong(anchor -> {
            long dx = (long) anchor.x() - roadEndpoint.x();
            long dz = (long) anchor.z() - roadEndpoint.z();
            return dx * dx + dz * dz;
        })).orElseThrow();
    }

    private static void cacheForestEntryMarker(
        ServerLevel level, HexWorldPlan world, Gate gate
    ) {
        FOREST_ENTRY_MARKERS.remove(gate.id());
        ForestGateGeometry geometry = forestGateGeometry(world, gate);
        int floorY = forestGateApproachFloorY(level, world, gate, geometry);
        ForestTemplatePlacement placement = forestTemplatePlacement(
            level, gate, geometry, floorY
        );
        ForestEntryMarker entry = placement == null
            ? null : placedForestEntryMarker(gate, placement);
        if (entry != null) {
            FOREST_ENTRY_MARKERS.put(gate.id(), entry);
        }
    }

    /**
     * Anchors the gatehouse to the first road cross-section on its playable
     * side. The terrain behind the boundary is intentionally much higher and
     * must not lift the entrance above the path players actually walk on.
     */
    private static int forestGateApproachFloorY(
        ServerLevel level, HexWorldPlan world, Gate gate,
        ForestGateGeometry geometry
    ) {
        ForestTemplatePlacement provisional = forestTemplatePlacement(
            level, gate, geometry, 0
        );
        if (provisional == null) {
            return CobbleventureBootstrap.plannedCaveMouthFloorY(
                level, geometry.x(), geometry.z()
            );
        }
        CobbleventureBootstrap.Point endpoint = alignedGateCenter(world, gate);
        double deltaX = endpoint.x() - geometry.x();
        double deltaZ = endpoint.z() - geometry.z();
        int length = Math.max(1, (int) Math.ceil(Math.hypot(deltaX, deltaZ)));
        Direction sideways = geometry.inward().getClockWise();
        for (int depth = 0; depth <= length; depth++) {
            double progress = depth / (double) length;
            int centerX = geometry.x() + (int) Math.round(deltaX * progress);
            int centerZ = geometry.z() + (int) Math.round(deltaZ * progress);
            if (provisional.footprint().contains(centerX, centerZ)) {
                continue;
            }
            List<Integer> heights = new ArrayList<>(3);
            for (int lateral = -1; lateral <= 1; lateral++) {
                int x = centerX + sideways.getStepX() * lateral;
                int z = centerZ + sideways.getStepZ() * lateral;
                heights.add(CobbleventureBootstrap.nativeTerrainColumn(
                    world, x, z
                ).groundY());
            }
            heights.sort(Integer::compareTo);
            return heights.get(1);
        }
        return CobbleventureBootstrap.nativeTerrainColumn(
            world, endpoint.x(), endpoint.z()
        ).groundY();
    }

    private static ForestTemplatePlacement forestTemplatePlacement(
        ServerLevel level, Gate gate, ForestGateGeometry geometry, int floorY
    ) {
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        var optionalTemplate = structureId == null
            ? java.util.Optional.<StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (optionalTemplate.isEmpty()) {
            LOGGER.error(
                "Forest gate NBT is missing: gate={}, structure={}",
                gate.id(), gate.structure()
            );
            return null;
        }
        StructureTemplate template = optionalTemplate.orElseThrow();
        List<StructureTemplate.StructureBlockInfo> localEntries = template.filterBlocks(
            BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW
        ).stream().filter(info -> info.nbt() != null
            && FOREST_ENTRY_MARKER.equals(info.nbt().getString("name"))).toList();
        if (localEntries.size() != 1) {
            LOGGER.error(
                "Forest gate NBT requires exactly one {} jigsaw: gate={}, structure={}, found={}",
                FOREST_ENTRY_MARKER, gate.id(), gate.structure(), localEntries.size()
            );
            return null;
        }
        StructureTemplate.StructureBlockInfo localEntry = localEntries.getFirst();
        Direction authoredInward = JigsawBlock.getFrontFacing(localEntry.state());
        if (!authoredInward.getAxis().isHorizontal()) {
            LOGGER.error(
                "Forest entry jigsaw must face horizontally: gate={}, position={}, facing={}",
                gate.id(), localEntry.pos(), authoredInward
            );
            return null;
        }
        Rotation rotation = rotationBetween(authoredInward, geometry.inward());
        Vec3i size = template.getSize();
        BlockPos rotatedAnchor = rotatedTemplateOffset(
            localEntry.pos(), size.getX(), size.getZ(), rotation
        );
        int minX = geometry.x() - rotatedAnchor.getX();
        int minZ = geometry.z() - rotatedAnchor.getZ();
        Vec3i rotatedSize = template.getSize(rotation);
        BlockPos origin = rotatedTemplateOrigin(
            minX, floorY, minZ,
            size.getX(), size.getZ(), rotation
        );
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .addProcessor(PlayingCardsTableOwnerProcessor.INSTANCE)
            .addProcessor(GroundFloorAirPreservationProcessor.INSTANCE);
        ExplicitAirPlacementProcessor.configure(template, settings);
        int outsideOffset = (geometry.inward().getAxis() == Direction.Axis.X
            ? template.getSize(rotation).getX()
            : template.getSize(rotation).getZ()) + 1;
        return new ForestTemplatePlacement(
            template, settings, rotation, origin,
            new BlockPos(
                geometry.x(), floorY + rotatedAnchor.getY(), geometry.z()
            ),
            geometry.inward(), outsideOffset,
            new StructureFootprint(
                minX, minZ,
                minX + rotatedSize.getX() - 1,
                minZ + rotatedSize.getZ() - 1
            )
        );
    }

    private static ForestEntryMarker placedForestEntryMarker(
        Gate gate, ForestTemplatePlacement placement
    ) {
        List<StructureTemplate.StructureBlockInfo> entries = placement.template().filterBlocks(
            placement.origin(), placement.settings(), Blocks.JIGSAW
        ).stream().filter(info -> info.nbt() != null
            && FOREST_ENTRY_MARKER.equals(info.nbt().getString("name"))).toList();
        if (entries.size() != 1) {
            LOGGER.error(
                "Rotated forest entry marker is invalid: gate={}, found={}",
                gate.id(), entries.size()
            );
            return null;
        }
        StructureTemplate.StructureBlockInfo entry = entries.getFirst();
        Direction actualInward = JigsawBlock.getFrontFacing(entry.state());
        if (!entry.pos().equals(placement.expectedEntry())
            || actualInward != placement.inward()) {
            LOGGER.error(
                "Forest entry marker transform mismatch: gate={}, expected={} {}, actual={} {}",
                gate.id(), placement.expectedEntry(), placement.inward(),
                entry.pos(), actualInward
            );
            return null;
        }
        return new ForestEntryMarker(
            entry.pos(), actualInward, placement.outsideOffset()
        );
    }

    private static ForestGateGeometry forestGateGeometry(
        HexWorldPlan world, Gate gate
    ) {
        HexGrid grid = world.grid();
        CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
        Direction inward = facingDirection(gate.facing());
        double forwardX = inward.getStepX();
        double forwardZ = inward.getStepZ();
        double length = grid.radius() * 2.0D;
        double collisionDistance = CobbleventureBootstrap.actualCaveBoundaryDistance(
            world, center, forwardX, forwardZ, length
        );
        // Put the forest-side entry marker just behind the collision shell. The
        // gatehouse then overlaps the inaccessible forest boundary instead of
        // standing in the middle of the playable tile like a freestanding arch.
        double entranceDistance = Math.max(1.0D, collisionDistance + 3.0D);
        int x = center.x() + (int) Math.round(forwardX * entranceDistance);
        int z = center.z() + (int) Math.round(forwardZ * entranceDistance);
        return new ForestGateGeometry(x, z, inward);
    }

    static boolean isForestBarrierOpening(
        HexWorldPlan world, int x, int y, int z
    ) {
        for (Gate gate : world.gates()) {
            if (gate.destinationForest() == null) {
                continue;
            }
            ForestGateGeometry geometry = forestGateGeometry(world, gate);
            double offsetX = x + 0.5D - (geometry.x() + 0.5D);
            double offsetZ = z + 0.5D - (geometry.z() + 0.5D);
            double depth = offsetX * geometry.inward().getStepX()
                + offsetZ * geometry.inward().getStepZ();
            double lateral = Math.abs(
                offsetX * -geometry.inward().getStepZ()
                    + offsetZ * geometry.inward().getStepX()
            );
            if (depth < -3.5D || depth > 3.5D
                || lateral > gate.openingWidth() / 2.0D + 0.5D) {
                continue;
            }
            int floorY = CobbleventureBootstrap.nativeTerrainColumn(
                world, geometry.x(), geometry.z()
            ).groundY();
            if (y > floorY && y <= floorY + 7) {
                return true;
            }
        }
        return false;
    }

    private static Direction facingDirection(String facing) {
        return switch (facing) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            default -> throw new IllegalStateException(
                "Unsupported forest entrance facing: " + facing
            );
        };
    }

    private static Direction horizontalDirection(double x, double z) {
        if (Math.abs(x) > Math.abs(z)) {
            return x >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private static Rotation rotationBetween(Direction from, Direction to) {
        int delta = Math.floorMod(directionIndex(to) - directionIndex(from), 4);
        return switch (delta) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static int directionIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Direction must be horizontal");
        };
    }

    private static BlockPos rotatedTemplateOffset(
        BlockPos offset, int width, int depth, Rotation rotation
    ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(
                depth - 1 - offset.getZ(), offset.getY(), offset.getX()
            );
            case CLOCKWISE_180 -> new BlockPos(
                width - 1 - offset.getX(), offset.getY(), depth - 1 - offset.getZ()
            );
            case COUNTERCLOCKWISE_90 -> new BlockPos(
                offset.getZ(), offset.getY(), width - 1 - offset.getX()
            );
            default -> offset;
        };
    }

    private static BlockPos rotatedTemplateOrigin(
        int x, int y, int z, int width, int depth, Rotation rotation
    ) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(x + depth - 1, y, z);
            case CLOCKWISE_180 -> new BlockPos(x + width - 1, y, z + depth - 1);
            case COUNTERCLOCKWISE_90 -> new BlockPos(x, y, z + width - 1);
            default -> new BlockPos(x, y, z);
        };
    }

    private static void spawnNpc(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center, int groundY
    ) {
        String command = "easy_npc preset import_new data " + gate.npc() + " "
            + center.x() + " " + (groundY + 1) + " " + center.z();
        try {
            int result = level.getServer().getCommands().getDispatcher().execute(
                command,
                level.getServer().createCommandSourceStack()
                    .withLevel(level).withPermission(4).withSuppressedOutput()
            );
            if (result == 0) {
                LOGGER.warn("Gate NPC command returned no result: gate={}, npc={}", gate.id(), gate.npc());
            }
        } catch (CommandSyntaxException error) {
            LOGGER.error("Gate NPC placement failed: gate={}, npc={}", gate.id(), gate.npc(), error);
        }
    }

    static void tick(
        ServerPlayer player, ServerLevel generationLevel, HexWorldPlan world,
        long gameTime
    ) {
        HexGrid grid = world.grid();
        List<Gate> gates = world.gates();
        Vec3 previous = LAST_POSITIONS.put(player.getUUID(), player.position());
        HexCoord currentCell = grid.worldToHex(player.getX(), player.getZ());
        HexCoord previousCell = LAST_HEX_CELLS.put(player.getUUID(), currentCell);
        if (previous == null || previousCell == null || player.isSpectator()) {
            return;
        }
        beginPendingEventDialogueDenial(
            player, generationLevel, world, gameTime
        );
        if (handlePendingDenial(player, grid, gameTime)) {
            return;
        }
        for (Gate gate : gates) {
            if (handleForestPortal(player, generationLevel, grid, gate, previous, gameTime)) {
                return;
            }
            if (player.serverLevel() != generationLevel) {
                continue;
            }
            if (gate.allows(player)) {
                continue;
            }
            CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
            if (isNpcOnlyGate(gate)) {
                if (!crossedNpcGateHexBoundary(
                        gate.anchor(), gate.facing(), previousCell, currentCell
                    )) {
                    continue;
                }
                beginHexGateDenial(
                    player, world, gate, center, grid, previousCell, gameTime
                );
                return;
            }
            boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
            double normal = horizontal ? player.getZ() - center.z() : player.getX() - center.x();
            double previousNormal = horizontal ? previous.z - center.z() : previous.x - center.x();
            double lateral = horizontal ? player.getX() - center.x() : player.getZ() - center.z();
            double previousLateral = horizontal ? previous.x - center.x() : previous.z - center.z();
            double threshold = Math.max(0.45D, gate.wallThickness() / 2.0D - 0.35D);
            boolean crossed = crossedGateOpening(
                    previousNormal, previousLateral, normal, lateral,
                    threshold, gate.openingWidth()
                ) && Math.abs(normal - previousNormal) < 12.0D;
            if (!crossed && !insideGateTriggerZone(
                    normal, lateral, threshold, gate.openingWidth()
                )) {
                continue;
            }
            double side = previousNormal == 0.0D
                ? (gate.facing().equals("north") || gate.facing().equals("west") ? -1.0D : 1.0D)
                : Math.signum(previousNormal);
            beginGateDenial(
                player, world, gate, center, horizontal,
                side, threshold, lateral, gameTime
            );
            return;
        }
    }

    private static boolean isNpcOnlyGate(Gate gate) {
        return gate.npc() != null && gate.centerPlacement().equals("npc");
    }

    /**
     * NPC-only gates guard the authored edge between hex cells. The check is
     * independent of the NPC's radial interaction range and therefore cannot
     * be bypassed by rubbing along one side of a wide road.
     */
    static boolean crossedNpcGateHexBoundary(
        HexCoord anchor, String facing, HexCoord previous, HexCoord current
    ) {
        if (previous.equals(current)) return false;
        for (HexCoord offset : gateFaceOffsets(facing)) {
            HexCoord neighbor = anchor.plus(offset);
            if ((previous.equals(anchor) && current.equals(neighbor))
                || (previous.equals(neighbor) && current.equals(anchor))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests the point where the player's swept movement crosses the gate
     * plane, rather than testing only the end position. This closes diagonal
     * gaps that become noticeable as an authored passage grows wider.
     */
    static boolean crossedGateOpening(
        double previousNormal, double previousLateral,
        double normal, double lateral,
        double threshold, int openingWidth
    ) {
        double side = Math.signum(previousNormal);
        if (side == 0.0D) {
            return false;
        }
        double plane = side * threshold;
        boolean crossed = side > 0.0D
            ? previousNormal > plane && normal <= plane
            : previousNormal < plane && normal >= plane;
        if (!crossed) {
            return false;
        }
        double normalDelta = normal - previousNormal;
        if (Math.abs(normalDelta) < 1.0E-7D) {
            return false;
        }
        double intersection = (plane - previousNormal) / normalDelta;
        double crossingLateral = previousLateral
            + (lateral - previousLateral) * intersection;
        return Math.abs(crossingLateral)
            <= openingWidth / 2.0D + GATE_TRIGGER_EDGE_OVERLAP;
    }

    /**
     * Acts as a fail-safe volume around the trigger plane. A crossing-only
     * test can miss a player whose tracking begins while already standing in
     * the gate band, so every tick also rejects locked players found inside
     * the full opening.
     */
    static boolean insideGateTriggerZone(
        double normal, double lateral, double threshold, int openingWidth
    ) {
        return Math.abs(normal) <= threshold + 0.35D
            && Math.abs(lateral)
                <= openingWidth / 2.0D + GATE_TRIGGER_EDGE_OVERLAP;
    }

    private static void beginGateDenial(
        ServerPlayer player, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center, boolean horizontal,
        double side, double threshold, double lateral, long gameTime
    ) {
        PendingGateDenial pending = createGateDenial(
            player, gate, center, horizontal, side, threshold, lateral, gameTime
        );
        PENDING_DENIALS.put(player.getUUID(), pending);
        holdPlayer(player, pending.lockedPosition);
        LAST_POSITIONS.put(player.getUUID(), pending.lockedPosition);
        if (player.getPersistentData().getLong(DENY_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(DENY_COOLDOWN, gameTime + 20L);
            if (gate.npc() == null || !openGateNpcDialog(player, world, gate)) {
                player.sendSystemMessage(Component.literal(gate.denyMessage()), true);
                pending.finished = true;
            }
        }
    }

    private static void beginHexGateDenial(
        ServerPlayer player, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center, HexGrid grid,
        HexCoord originCell, long gameTime
    ) {
        PendingGateDenial pending = createHexGateDenial(
            player, gate, center, grid, originCell, gameTime
        );
        PENDING_DENIALS.put(player.getUUID(), pending);
        holdPlayer(player, pending.lockedPosition);
        LAST_POSITIONS.put(player.getUUID(), pending.lockedPosition);
        LAST_HEX_CELLS.put(player.getUUID(), originCell);
        if (player.getPersistentData().getLong(DENY_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(DENY_COOLDOWN, gameTime + 20L);
            if (!openGateNpcDialog(player, world, gate)) {
                player.sendSystemMessage(Component.literal(gate.denyMessage()), true);
                pending.finished = true;
            }
        }
    }

    private static PendingGateDenial createHexGateDenial(
        ServerPlayer player, Gate gate, CobbleventureBootstrap.Point center,
        HexGrid grid, HexCoord originCell, long gameTime
    ) {
        CobbleventureBootstrap.Point tileCenter = grid.worldCenter(originCell);
        double directionX = tileCenter.x() - center.x();
        double directionZ = tileCenter.z() - center.z();
        double directionLength = Math.hypot(directionX, directionZ);
        if (directionLength < 1.0D) {
            throw new IllegalStateException(
                "NPC gate origin cell has no direction from its center: " + gate.id()
            );
        }
        directionX /= directionLength;
        directionZ /= directionLength;
        double threshold = Math.max(0.45D, gate.wallThickness() / 2.0D - 0.35D);
        Vec3 locked = gatePoint(
            player.serverLevel(), center, directionX, directionZ, threshold + 0.12D
        );
        Vec3 retreat = gatePoint(
            player.serverLevel(), center, directionX, directionZ, threshold + 6.0D
        );
        return new PendingGateDenial(locked, retreat, originCell, gameTime);
    }

    private static Vec3 gatePoint(
        ServerLevel level, CobbleventureBootstrap.Point center,
        double directionX, double directionZ, double distance
    ) {
        double x = center.x() + directionX * distance;
        double z = center.z() + directionZ * distance;
        double y = groundY(level, (int)Math.floor(x), (int)Math.floor(z)) + 1.0D;
        return new Vec3(x, y, z);
    }

    private static PendingGateDenial createGateDenial(
        ServerPlayer player, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, double side, double threshold, double lateral,
        long gameTime
    ) {
        double clampedLateral = Math.max(
            -gate.openingWidth() / 2.0D + 0.4D,
            Math.min(gate.openingWidth() / 2.0D - 0.4D, lateral)
        );
        double lockedNormal = side * (threshold + 0.12D);
        double retreatNormal = side * (threshold + 6.0D);
        double lockedX = horizontal ? center.x() + clampedLateral : center.x() + lockedNormal;
        double lockedZ = horizontal ? center.z() + lockedNormal : center.z() + clampedLateral;
        double retreatX = horizontal ? center.x() + clampedLateral : center.x() + retreatNormal;
        double retreatZ = horizontal ? center.z() + retreatNormal : center.z() + clampedLateral;
        double lockedY = groundY(
            player.serverLevel(), (int) Math.floor(lockedX), (int) Math.floor(lockedZ)
        ) + 1.0D;
        double retreatY = groundY(
            player.serverLevel(), (int) Math.floor(retreatX), (int) Math.floor(retreatZ)
        ) + 1.0D;
        return new PendingGateDenial(
            new Vec3(lockedX, lockedY, lockedZ),
            new Vec3(retreatX, retreatY, retreatZ), null, gameTime
        );
    }

    private static void beginPendingEventDialogueDenial(
        ServerPlayer player, ServerLevel generationLevel, HexWorldPlan world,
        long gameTime
    ) {
        PendingEventDialogue dialogue = PENDING_EVENT_DIALOGUES.remove(player.getUUID());
        if (dialogue == null || PENDING_DENIALS.containsKey(player.getUUID())
            || player.serverLevel() != generationLevel) {
            return;
        }
        Entity dialogueNpc = generationLevel.getEntity(dialogue.npcId);
        if (dialogueNpc == null) return;
        for (Gate gate : world.gates()) {
            if (gate.npc() == null || gate.allows(player)) continue;
            CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
            if (dialogueNpc.distanceToSqr(center.x() + 0.5D, dialogueNpc.getY(), center.z() + 0.5D)
                    > 10.0D * 10.0D) {
                continue;
            }
            boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
            double normal = horizontal ? player.getZ() - center.z() : player.getX() - center.x();
            double lateral = horizontal ? player.getX() - center.x() : player.getZ() - center.z();
            if (!insideGateDialogueApproach(normal, lateral, gate.openingWidth())) {
                continue;
            }
            double threshold = Math.max(0.45D, gate.wallThickness() / 2.0D - 0.35D);
            double side = normal == 0.0D
                ? (gate.facing().equals("north") || gate.facing().equals("west") ? 1.0D : -1.0D)
                : Math.signum(normal);
            PendingGateDenial pending = isNpcOnlyGate(gate)
                ? createHexGateDenial(
                    player, gate, center, world.grid(),
                    world.grid().worldToHex(player.getX(), player.getZ()), gameTime
                )
                : createGateDenial(
                    player, gate, center, horizontal, side, threshold, lateral, gameTime
                );
            pending.sawDialogue = true;
            pending.dialogueOpen = dialogue.open;
            pending.finished = !dialogue.open;
            PENDING_DENIALS.put(player.getUUID(), pending);
            holdPlayer(player, pending.lockedPosition);
            LAST_POSITIONS.put(player.getUUID(), pending.lockedPosition);
            if (pending.retreatCell != null) {
                LAST_HEX_CELLS.put(player.getUUID(), pending.retreatCell);
            }
            return;
        }
    }

    /**
     * Associates a manually opened gate-NPC dialogue with the road-shaped
     * approach, not a circular radius around the NPC. This keeps corners next
     * to a wide gate from feeling like an invisible round collision bubble.
     */
    static boolean insideGateDialogueApproach(
        double normal, double lateral, int openingWidth
    ) {
        return Math.abs(normal) <= GATE_DIALOGUE_APPROACH_DEPTH
            && Math.abs(lateral)
                <= openingWidth / 2.0D + GATE_TRIGGER_EDGE_OVERLAP;
    }

    private static void updateEventDialogueState(
        ServerPlayer player, EventSessionKey key, boolean open
    ) {
        PendingGateDenial denial = PENDING_DENIALS.get(player.getUUID());
        if (denial != null) {
            updateGateDialogueState(player, open);
            return;
        }
        PENDING_EVENT_DIALOGUES.compute(player.getUUID(), (ignored, current) ->
            current == null || open
                ? new PendingEventDialogue(key.npcId(), open)
                : new PendingEventDialogue(current.npcId, false)
        );
    }

    private static boolean handlePendingDenial(
        ServerPlayer player, HexGrid grid, long gameTime
    ) {
        PendingGateDenial pending = PENDING_DENIALS.get(player.getUUID());
        if (pending == null) {
            return false;
        }
        if (pending.finished
            || (!pending.sawDialogue && gameTime - pending.startedAt > 80L)
            || gameTime - pending.startedAt > 20L * 90L) {
            PENDING_DENIALS.remove(player.getUUID());
            player.teleportTo(
                player.serverLevel(), pending.retreatPosition.x(),
                pending.retreatPosition.y(), pending.retreatPosition.z(),
                player.getYRot(), player.getXRot()
            );
            player.setDeltaMovement(Vec3.ZERO);
            LAST_POSITIONS.put(player.getUUID(), pending.retreatPosition);
            LAST_HEX_CELLS.put(
                player.getUUID(), pending.retreatCell != null
                    ? pending.retreatCell
                    : grid.worldToHex(
                        pending.retreatPosition.x(), pending.retreatPosition.z()
                    )
            );
            return true;
        }
        holdPlayer(player, pending.lockedPosition);
        LAST_POSITIONS.put(player.getUUID(), pending.lockedPosition);
        if (pending.retreatCell != null) {
            LAST_HEX_CELLS.put(player.getUUID(), pending.retreatCell);
        }
        return true;
    }

    private static void holdPlayer(ServerPlayer player, Vec3 position) {
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        if (player.position().distanceToSqr(position) > 0.01D) {
            player.teleportTo(
                player.serverLevel(), position.x(), position.y(), position.z(),
                player.getYRot(), player.getXRot()
            );
        }
    }

    static void updateGateDialogueState(ServerPlayer player, boolean open) {
        PendingGateDenial pending = PENDING_DENIALS.get(player.getUUID());
        if (pending == null) {
            return;
        }
        if (open) {
            pending.sawDialogue = true;
            pending.dialogueOpen = true;
            pending.finished = false;
        } else if (pending.sawDialogue && pending.dialogueOpen) {
            pending.dialogueOpen = false;
            pending.finished = true;
        }
    }

    private static boolean handleForestPortal(
        ServerPlayer player, ServerLevel generationLevel, HexGrid grid,
        Gate gate, Vec3 previous, long gameTime
    ) {
        if (gate.forestDimension() == null || gate.forestDestination() == null
            || gate.forestPortalAnchor() == null
            || player.getPersistentData().getLong(FOREST_PORTAL_COOLDOWN) > gameTime) {
            return false;
        }
        ServerLevel forestLevel = player.getServer().getLevel(gate.forestDimension());
        if (forestLevel == null) {
            return false;
        }
        ForestEntryMarker entry = FOREST_ENTRY_MARKERS.get(gate.id());
        if (entry == null) {
            return false;
        }

        ForestEntryMarker forestExit = FOREST_EXIT_MARKERS.get(gate.id());
        if (player.serverLevel() == forestLevel && forestExit != null
            && crossedForestThreshold(
                player, previous, forestExit, gate.openingWidth(), false
            )) {
            int returnX = entry.position().getX()
                - entry.inward().getStepX() * entry.outsideOffset();
            int returnZ = entry.position().getZ()
                - entry.inward().getStepZ() * entry.outsideOffset();
            int returnY = safeForestStandY(
                generationLevel, returnX, returnZ, entry.position().getY()
            );
            teleportForestPlayer(player, generationLevel, returnX, returnY, returnZ, gameTime);
            return true;
        }
        if (player.serverLevel() != generationLevel) {
            return false;
        }

        if (!crossedForestThreshold(
            player, previous, entry, gate.openingWidth(), true
        )) {
            return false;
        }

        CobbleventureBootstrap.BlockPoint destination = gate.forestDestination();
        int destinationY = safeForestStandY(
            forestLevel, destination.x(), destination.z(), destination.y()
        );
        teleportForestPlayer(
            player, forestLevel, destination.x(), destinationY, destination.z(), gameTime
        );
        return true;
    }

    private static boolean crossedForestThreshold(
        ServerPlayer player, Vec3 previous, ForestEntryMarker marker,
        int openingWidth, boolean entering
    ) {
        double markerX = marker.position().getX() + 0.5D;
        double markerZ = marker.position().getZ() + 0.5D;
        double currentX = player.getX() - markerX;
        double currentZ = player.getZ() - markerZ;
        double previousX = previous.x - markerX;
        double previousZ = previous.z - markerZ;
        double depth = currentX * marker.inward().getStepX()
            + currentZ * marker.inward().getStepZ();
        double previousDepth = previousX * marker.inward().getStepX()
            + previousZ * marker.inward().getStepZ();
        double lateral = Math.abs(
            currentX * -marker.inward().getStepZ()
                + currentZ * marker.inward().getStepX()
        );
        boolean crossed = entering
            ? previousDepth <= 0.0D && depth > 0.0D
            : previousDepth >= 0.0D && depth < 0.0D;
        return crossed && Math.abs(depth - previousDepth) < 12.0D
            && lateral <= openingWidth / 2.0D + 0.5D;
    }

    private static void teleportForestPlayer(
        ServerPlayer player, ServerLevel destinationLevel,
        int x, int y, int z, long gameTime
    ) {
        player.getPersistentData().putLong(FOREST_PORTAL_COOLDOWN, gameTime + 40L);
        player.teleportTo(
            destinationLevel, x + 0.5D, y, z + 0.5D,
            player.getYRot(), player.getXRot()
        );
        LAST_POSITIONS.put(player.getUUID(), player.position());
        LAST_HEX_CELLS.remove(player.getUUID());
    }

    /**
     * Resolves a portal landing position near the authored entrance height.
     * A global heightmap can point at a tree canopy or return an ungenerated
     * column height, so forest portals validate the floor and two-block body
     * clearance directly instead.
     */
    private static int safeForestStandY(
        ServerLevel level, int x, int z, int authoredY
    ) {
        int minimumY = Math.max(level.getMinBuildHeight() + 1, authoredY - 24);
        int maximumY = Math.min(level.getMaxBuildHeight() - 2, authoredY + 32);
        int startY = Math.max(minimumY, Math.min(maximumY, authoredY));
        if (canStandAt(level, x, startY, z)) return startY;
        int maximumDistance = Math.max(maximumY - startY, startY - minimumY);
        for (int distance = 1; distance <= maximumDistance; distance++) {
            int below = startY - distance;
            if (below >= minimumY && canStandAt(level, x, below, z)) return below;
            int above = startY + distance;
            if (above <= maximumY && canStandAt(level, x, above, z)) return above;
        }
        int heightmapY = groundY(level, x, z) + 1;
        if (heightmapY >= level.getMinBuildHeight() + 1
            && heightmapY <= level.getMaxBuildHeight() - 2
            && canStandAt(level, x, heightmapY, z)) {
            return heightmapY;
        }
        LOGGER.warn(
            "Forest portal has no safe floor near its authored height: dimension={}, x={}, z={}, authoredY={}",
            level.dimension().location(), x, z, authoredY
        );
        return startY;
    }

    /** Resolves terrain height before the gate template clears trees and vegetation. */
    private static int safeForestGateStandY(
        ServerLevel level, int x, int z, int authoredY
    ) {
        int minimumY = Math.max(level.getMinBuildHeight() + 1, authoredY - 24);
        int maximumY = Math.min(level.getMaxBuildHeight() - 2, authoredY + 24);
        int startY = Math.max(minimumY, Math.min(maximumY, authoredY));
        if (hasNaturalPortalFloor(level, x, startY, z)) return startY;
        int maximumDistance = Math.max(maximumY - startY, startY - minimumY);
        for (int distance = 1; distance <= maximumDistance; distance++) {
            int below = startY - distance;
            if (below >= minimumY && hasNaturalPortalFloor(level, x, below, z)) return below;
            int above = startY + distance;
            if (above <= maximumY && hasNaturalPortalFloor(level, x, above, z)) return above;
        }
        return safeForestStandY(level, x, z, authoredY);
    }

    private static boolean hasNaturalPortalFloor(
        ServerLevel level, int x, int feetY, int z
    ) {
        BlockPos floor = new BlockPos(x, feetY - 1, z);
        BlockState state = level.getBlockState(floor);
        return !state.is(BlockTags.LOGS)
            && !state.is(BlockTags.LEAVES)
            && !state.is(Blocks.BARRIER)
            && !state.getCollisionShape(level, floor).isEmpty()
            && level.getFluidState(floor).isEmpty();
    }

    private static boolean canStandAt(ServerLevel level, int x, int y, int z) {
        BlockPos floor = new BlockPos(x, y - 1, z);
        BlockPos feet = floor.above();
        BlockPos head = feet.above();
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
            && level.getFluidState(floor).isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getFluidState(feet).isEmpty()
            && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
            && level.getFluidState(head).isEmpty();
    }

    private static boolean openGateNpcDialog(
        ServerPlayer player, HexWorldPlan world, Gate gate
    ) {
        CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
        int centerY = groundY(player.serverLevel(), center.x(), center.z()) + 1;
        double searchRadius = Math.max(
            MIN_GATE_NPC_SEARCH_RADIUS,
            gate.openingWidth() / 2.0D + GATE_TRIGGER_EDGE_OVERLAP + 1.0D
        );
        AABB search = new AABB(
            center.x() - searchRadius, centerY - 4.0D, center.z() - searchRadius,
            center.x() + searchRadius, centerY + 5.0D, center.z() + searchRadius
        );
        List<Entity> nearbyNpcs = player.serverLevel().getEntitiesOfClass(
            Entity.class, search,
            entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                .getNamespace().equals("easy_npc")
        );
        java.util.Comparator<Entity> nearestToGate = java.util.Comparator.comparingDouble(
            entity -> entity.distanceToSqr(center.x() + 0.5D, centerY, center.z() + 0.5D)
        );
        Entity v5Npc = nearbyNpcs.stream()
            .filter(entity -> entity.getTags().stream()
                .anyMatch(tag -> tag.startsWith("cves_binding/")))
            .min(nearestToGate)
            .orElse(null);
        if (v5Npc != null) {
            return EventNpcInteractionHandler.startBoundInteraction(player, v5Npc);
        }
        Entity npc = nearbyNpcs.stream()
            .filter(entity -> entity.getTags().contains("cobbleventure_npc_preset_v4"))
            .min(nearestToGate)
            .orElse(null);
        if (npc == null) {
            LOGGER.warn("Gate denial dialog NPC was not found: gate={}, npc={}", gate.id(), gate.npc());
            return false;
        }
        String command = "easy_npc dialog open " + npc.getStringUUID() + " "
            + player.getGameProfile().getName() + " " + gate.denyDialog();
        try {
            int result = player.getServer().getCommands().getDispatcher().execute(
                command,
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput()
            );
            if (result == 0) {
                LOGGER.warn("Gate denial dialog command returned no result: gate={}, npc={}", gate.id(), npc.getUUID());
            }
            return result != 0;
        } catch (CommandSyntaxException error) {
            LOGGER.error("Gate denial dialog failed: gate={}, npc={}", gate.id(), npc.getUUID(), error);
            return false;
        }
    }

    static void forget(ServerPlayer player) {
        LAST_POSITIONS.remove(player.getUUID());
        LAST_HEX_CELLS.remove(player.getUUID());
        PENDING_DENIALS.remove(player.getUUID());
        PENDING_EVENT_DIALOGUES.remove(player.getUUID());
    }

    static int teleportToGate(
        ServerLevel level,
        Iterable<? extends Entity> targets,
        HexWorldPlan world,
        String gateId,
        String side
    ) {
        HexGrid grid = world.grid();
        List<Gate> gates = world.gates();
        Gate gate = gates.stream().filter(value -> value.id().equals(gateId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown world gate: " + gateId));
        CobbleventureBootstrap.Point center = alignedGateCenter(world, gate);
        double distance = gate.wallThickness() / 2.0D + 3.0D;
        double directionX = switch (gate.facing()) {
            case "east" -> 1.0D;
            case "west" -> -1.0D;
            default -> 0.0D;
        };
        double directionZ = switch (gate.facing()) {
            case "south" -> 1.0D;
            case "north" -> -1.0D;
            default -> 0.0D;
        };
        double sign = side.equals("back") ? -1.0D : side.equals("center") ? 0.0D : 1.0D;
        int x = (int) Math.round(center.x() + directionX * distance * sign);
        int z = (int) Math.round(center.z() + directionZ * distance * sign);
        int y = groundY(level, x, z) + 1;
        int moved = 0;
        for (Entity target : targets) {
            target.teleportTo(x + 0.5D, y, z + 0.5D);
            if (target instanceof ServerPlayer player) {
                LAST_POSITIONS.put(player.getUUID(), player.position());
                LAST_HEX_CELLS.put(
                    player.getUUID(), grid.worldToHex(player.getX(), player.getZ())
                );
            }
            moved++;
        }
        return moved;
    }

    private static int groundY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static BlockState blockState(String id) {
        ResourceLocation resource = ResourceLocation.tryParse(id);
        if (resource == null || !BuiltInRegistries.BLOCK.containsKey(resource)) {
            throw new IllegalStateException("Unknown gate wall block: " + id);
        }
        return BuiltInRegistries.BLOCK.get(resource).defaultBlockState();
    }

    private static Rotation rotation(int value) {
        return switch (Math.floorMod(value, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()
            || value.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("Gate field is required: " + key);
        }
        return value.get(key).getAsString();
    }

    private static String optionalString(JsonObject value, String key, String fallback) {
        return value.has(key) ? value.get(key).getAsString() : fallback;
    }

    private static String nullableString(JsonObject value, String key) {
        return value.has(key) && !value.get(key).getAsString().isBlank()
            ? value.get(key).getAsString() : null;
    }

    private static int optionalInt(JsonObject value, String key, int fallback) {
        return value.has(key) ? value.get(key).getAsInt() : fallback;
    }

    private static boolean optionalBoolean(JsonObject value, String key, boolean fallback) {
        return value.has(key) ? value.get(key).getAsBoolean() : fallback;
    }

    record Gate(
        String id,
        HexCoord anchor,
        String structure,
        int rotation,
        String facing,
        String centerPlacement,
        boolean buildingEnabled,
        String surroundingType,
        String wallBlock,
        String treeLog,
        String treeLeaves,
        int wallThickness,
        int wallHeight,
        int openingWidth,
        int barrierHeight,
        String conditionMode,
        List<PlayerConditions.Condition> conditions,
        String denyMessage,
        String denyDialog,
        String npc,
        String destinationForest,
        String destinationEntrance,
        ResourceKey<Level> forestDimension,
        CobbleventureBootstrap.BlockPoint forestDestination,
        CobbleventureBootstrap.BlockPoint forestPortalAnchor
    ) {
        boolean allows(ServerPlayer player) {
            if (conditions.isEmpty()) {
                return true;
            }
            return PlayerConditions.matches(player, conditionMode, conditions);
        }

        Gate withForestDestination(
            ResourceKey<Level> dimension,
            CobbleventureBootstrap.BlockPoint destination,
            CobbleventureBootstrap.BlockPoint portalAnchor
        ) {
            return new Gate(
                id, anchor, structure, rotation, facing, centerPlacement, buildingEnabled,
                surroundingType, wallBlock, treeLog, treeLeaves, wallThickness,
                wallHeight, openingWidth, barrierHeight, conditionMode, conditions,
                denyMessage, denyDialog, npc, destinationForest, destinationEntrance,
                dimension, destination, portalAnchor
            );
        }
    }

    private record ForestEntryMarker(
        BlockPos position, Direction inward, int outsideOffset
    ) {}

    private record ForestGateGeometry(int x, int z, Direction inward) {}

    private record StructureFootprint(
        int minX, int minZ, int maxX, int maxZ
    ) {
        private boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        private boolean containsInterior(int x, int z, int margin) {
            return x >= minX + margin && x <= maxX - margin
                && z >= minZ + margin && z <= maxZ - margin;
        }

        private StructureFootprint expanded(int margin) {
            return new StructureFootprint(
                minX - margin, minZ - margin,
                maxX + margin, maxZ + margin
            );
        }

    }

    private record GateStructurePlacement(StructureFootprint footprint) {}

    private record GateEntrancePlacement(
        int x, int z, int surfaceY, Direction outward,
        StructureFootprint footprint, int minimumLateral, int maximumLateral
    ) {}

    record GateApproachWidth(int minimumLateral, int maximumLateral) {
        private static final GateApproachWidth DEFAULT =
            new GateApproachWidth(-1, 1);
    }

    private record NaturalGateColumn(
        int x, int z, String terrainType, int distance, int offset
    ) {}

    private record GateEdgeVector(double x, double z) {}

    private static final class PendingGateDenial {
        private final Vec3 lockedPosition;
        private final Vec3 retreatPosition;
        private final HexCoord retreatCell;
        private final long startedAt;
        private boolean sawDialogue;
        private boolean dialogueOpen;
        private boolean finished;

        private PendingGateDenial(
            Vec3 lockedPosition, Vec3 retreatPosition,
            HexCoord retreatCell, long startedAt
        ) {
            this.lockedPosition = lockedPosition;
            this.retreatPosition = retreatPosition;
            this.retreatCell = retreatCell;
            this.startedAt = startedAt;
        }
    }

    private record PendingEventDialogue(UUID npcId, boolean open) {}

    private record ForestTemplatePlacement(
        StructureTemplate template,
        StructurePlaceSettings settings,
        Rotation rotation,
        BlockPos origin,
        BlockPos expectedEntry,
        Direction inward,
        int outsideOffset,
        StructureFootprint footprint
    ) {}
}
