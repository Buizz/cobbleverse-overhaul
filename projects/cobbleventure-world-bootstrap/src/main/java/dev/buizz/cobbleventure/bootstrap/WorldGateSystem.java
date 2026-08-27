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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final Map<UUID, Vec3> LAST_POSITIONS = new HashMap<>();
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
                nullableString(value, "resource"),
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
            if (forestGate) {
                cacheForestEntryMarker(level, world, gate);
            } else if (gate.surroundingType().equals("natural")
                && !gate.buildingEnabled()
                && markerState.getValue(RespawnAnchorBlock.CHARGE) < 3) {
                refreshNpcNaturalGate(level, world, gate, center);
                level.setBlock(
                    marker,
                    markerState.setValue(RespawnAnchorBlock.CHARGE, 3), 2
                );
            }
            return;
        }
        boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
        int halfLength = Math.max(16, grid.radius() - 3);
        int halfThickness = gate.wallThickness() / 2;
        int halfOpening = gate.openingWidth() / 2;
        int centerY = groundY(level, center.x(), center.z());
        Map<Long, Integer> wallGroundHeights = new HashMap<>();
        if (forestGate) {
            cacheForestEntryMarker(level, world, gate);
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
        boolean shouldPlaceStructure = gate.buildingEnabled();
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
        if (!forestGate && gate.surroundingType().equals("wall")) {
            repairWallSurroundingGaps(
                level, gate, center, horizontal,
                halfLength, halfThickness, halfOpening, wallGroundHeights,
                gatePlacement == null ? null : gatePlacement.footprint()
            );
        }
        if (!forestGate) {
            layGateApproachRoads(
                level, world, gate, center,
                gatePlacement == null ? null : gatePlacement.footprint(),
                halfThickness
            );
        }
        if (gate.npc() != null) {
            spawnNpc(level, gate, center, centerY);
        }
        BlockState completedMarker = Blocks.RESPAWN_ANCHOR.defaultBlockState();
        if (!forestGate && gate.surroundingType().equals("natural")
            && !gate.buildingEnabled()) {
            completedMarker = completedMarker.setValue(RespawnAnchorBlock.CHARGE, 3);
        }
        level.setBlock(marker, completedMarker, 2);
        LOGGER.info(
            "World gate generated: id={}, anchor={}, facing={}, building={}, surroundings={}",
            gate.id(), gate.anchor(), gate.facing(), gate.buildingEnabled(), gate.surroundingType()
        );
    }

    /** Refreshes the old solid leaf wall once when an existing NPC-only gate is loaded. */
    private static void refreshNpcNaturalGate(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center
    ) {
        int halfLength = Math.max(16, world.grid().radius() - 3);
        int halfThickness = gate.wallThickness() / 2;
        int halfOpening = gate.openingWidth() / 2;
        placeNaturalSurroundings(
            level, world, gate, center,
            halfLength, halfThickness, halfOpening
        );
        LOGGER.info("NPC natural gate visuals refreshed: gate={}", gate.id());
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
     * anchor hex. East and west each have one face. North and south meet two
     * diagonal faces, so a route through only one of them selects that face's
     * midpoint; when both (or neither) are open the gate stays centered between
     * them. Forest entrances retain their tile-center ray origin because their
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
        return gateEdgeCenter(
            world.grid(), gate.anchor(), gate.facing(),
            offset -> gateFaceIsOpen(world, gate.anchor(), offset)
        );
    }

    static CobbleventureBootstrap.Point gateEdgeCenter(
        HexGrid grid, HexCoord anchor, String facing, Predicate<HexCoord> faceIsOpen
    ) {
        List<HexCoord> faces = switch (facing) {
            case "north" -> List.of(new HexCoord(0, -1), new HexCoord(1, -1));
            case "east" -> List.of(new HexCoord(1, 0));
            case "south" -> List.of(new HexCoord(-1, 1), new HexCoord(0, 1));
            case "west" -> List.of(new HexCoord(-1, 0));
            default -> throw new IllegalStateException(
                "Unsupported gate facing: " + facing
            );
        };
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
        CobbleventureBootstrap.Point firstNeighbor = grid.worldCenter(anchor.plus(faces.get(0)));
        CobbleventureBootstrap.Point secondNeighbor = grid.worldCenter(anchor.plus(faces.get(1)));
        return new CobbleventureBootstrap.Point(
            roundGateCoordinate(tile.x() * 0.5D
                + (firstNeighbor.x() + secondNeighbor.x()) * 0.25D),
            roundGateCoordinate(tile.z() * 0.5D
                + (firstNeighbor.z() + secondNeighbor.z()) * 0.25D)
        );
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
        HexCoord neighbor = anchor.plus(offset);
        return world.paths().stream().anyMatch(path ->
            path.cells().contains(anchor) && path.cells().contains(neighbor)
        );
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
        int x, int z, int distance, int offset
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
            if (Math.abs(offset) >= 2
                && Math.floorMod(distance + Math.abs(offset) * 3, 6) == 0
                && Math.floorMod((int) hash, 2) == 0
            ) {
                if (CobbleventureBootstrap.placeNaturalGateTree(
                    level,
                    naturalGateTreeLog(terrainType),
                    naturalGateTreeLeaves(terrainType),
                    ground, hash
                )) {
                    return;
                }
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

    /**
     * Builds the two filled wedges shown by the gate authoring model: the
     * inaccessible terrain is broad at the left/right hex edges and converges
     * toward two tips beside the central passage. Nothing radiates from the
     * gate toward its front or back.
     */
    private static void placeNaturalSurroundings(
        ServerLevel level, HexWorldPlan world, Gate gate,
        CobbleventureBootstrap.Point center,
        int halfLength, int halfThickness, int halfOpening
    ) {
        Direction normal = facingDirection(gate.facing());
        Direction sideways = normal.getClockWise();
        clearLegacyNaturalBarrierLine(
            level, gate, center, sideways, halfLength, halfOpening
        );
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
        int availableLength = Math.max(1, halfLength - halfOpening);
        int maximumDepth = Math.max(
            halfThickness + 4,
            (int) Math.round(halfLength * 0.52D)
        );
        List<NaturalGateColumn> columns = new ArrayList<>();
        for (int shoulderSign : new int[] {-1, 1}) {
            Direction sampleDirection = shoulderSign < 0
                ? sideways.getOpposite() : sideways;
            String terrainType = inaccessibleTerrainType(
                world, center, sampleDirection
            );
            for (int distance = halfOpening + 1;
                distance <= halfLength; distance++) {
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
        // A previous gate revision may already occupy this footprint. Remove
        // its barriers and obsolete configured tree palette before rebuilding.
        for (NaturalGateColumn column : columns) {
            clearNaturalWedgeColumn(
                level, gate, column.terrainType(), column.x(), column.z()
            );
        }
        // Pass 1: place complete natural features while their growth volume is
        // still open. Crowns and boulders may protrude beyond the wedge.
        for (NaturalGateColumn column : columns) {
            decorateNaturalShoulderColumn(
                level, world, gate, column.terrainType(),
                column.x(), column.z(), column.distance(), column.offset()
            );
        }
        // Pass 2: fill every remaining replaceable space in the wedge with an
        // invisible barrier. Natural blocks stay visible and no gap remains.
        for (NaturalGateColumn column : columns) {
            placeNaturalBarrierColumn(
                level, gate, column.terrainType(), column.x(), column.z()
            );
        }
        LOGGER.info(
            "Natural gate wedges placed: gate={}, halfLength={}, maxDepth={}, opening={}",
            gate.id(), halfLength, maximumDepth, halfOpening * 2 + 1
        );
    }

    private static void clearNaturalWedgeColumn(
        ServerLevel level, Gate gate, String terrainType, int x, int z
    ) {
        int groundY = naturalBarrierGroundY(level, x, z);
        BlockState configuredLog = blockState(gate.treeLog());
        BlockState configuredLeaves = blockState(gate.treeLeaves());
        boolean forestTerrain = terrainType.equals("high_forest")
            || terrainType.equals("dense_forest");
        boolean obsoletePalette = forestTerrain
            && (!gate.treeLog().equals(naturalGateTreeLog(terrainType))
                || !gate.treeLeaves().equals(naturalGateTreeLeaves(terrainType)));
        for (int height = 1; height <= gate.barrierHeight(); height++) {
            BlockPos position = new BlockPos(x, groundY + height, z);
            BlockState state = level.getBlockState(position);
            if (state.is(Blocks.BARRIER)
                || (obsoletePalette && (state.is(configuredLog.getBlock())
                    || state.is(configuredLeaves.getBlock())))) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /** Removes the obsolete center-to-edge collision line before wedge refresh. */
    private static void clearLegacyNaturalBarrierLine(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        Direction sideways, int halfLength, int halfOpening
    ) {
        for (int lateral = -halfLength; lateral <= halfLength; lateral++) {
            if (Math.abs(lateral) <= halfOpening) {
                continue;
            }
            int x = center.x() + sideways.getStepX() * lateral;
            int z = center.z() + sideways.getStepZ() * lateral;
            int groundY = naturalBarrierGroundY(level, x, z);
            for (int height = 1; height <= gate.barrierHeight(); height++) {
                BlockPos position = new BlockPos(x, groundY + height, z);
                if (level.getBlockState(position).is(Blocks.BARRIER)) {
                    level.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                }
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
    /**
     * Restores only air holes that a gate template's padded air volume cut into
     * the already generated wall. Existing NBT blocks are left untouched so the
     * gatehouse can still join and decorate the wall itself.
     */
    private static void repairWallSurroundingGaps(
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
                // Only the thin outer padding of an NBT may be repaired. Air
                // farther inside the rotated footprint belongs to the authored
                // gatehouse (rooms and the walk-through passage), not to a gap
                // in the generated wall.
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
        int halfThickness
    ) {
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
                    if (footprint != null && footprint.contains(x, z)) {
                        continue;
                    }
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
        if (!structure.placeInWorld(
            level, origin, origin, settings,
            RandomSource.create(level.getSeed() ^ origin.asLong()), 2
        )) {
            LOGGER.error("Gate structure placement failed: gate={}, origin={}", gate.id(), origin);
            return null;
        }
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
        CobbleventureBootstrap.Point roadEndpoint = alignedGateCenter(world, gate);
        layWorldForestEntranceRoad(
            level, world, entry, roadEndpoint, placement.footprint()
        );
        FOREST_ENTRY_MARKERS.put(gate.id(), entry);
        level.setBlock(entry.position(), Blocks.AIR.defaultBlockState(), 2);
        LOGGER.info(
            "Forest gate NBT placed on boundary: gate={}, entry={}, inward={}, origin={}",
            gate.id(), entry.position(), entry.inward(), placement.origin()
        );
        return true;
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
            int gateY = safeForestStandY(
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
        ServerLevel level, HexWorldPlan world, ForestEntryMarker entry,
        CobbleventureBootstrap.Point roadEndpoint, StructureFootprint footprint
    ) {
        double deltaX = roadEndpoint.x() - entry.position().getX();
        double deltaZ = roadEndpoint.z() - entry.position().getZ();
        int length = Math.max(1, (int) Math.ceil(Math.hypot(deltaX, deltaZ)));
        Direction towardPath = entry.inward().getOpposite();
        Direction sideways = towardPath.getClockWise();
        for (int depth = 0; depth <= length; depth++) {
            double progress = depth / (double) length;
            int centerX = entry.position().getX() + (int) Math.round(deltaX * progress);
            int centerZ = entry.position().getZ() + (int) Math.round(deltaZ * progress);
            for (int lateral = -1; lateral <= 1; lateral++) {
                int x = centerX + sideways.getStepX() * lateral;
                int z = centerZ + sideways.getStepZ() * lateral;
                if (footprint.contains(x, z)) {
                    continue;
                }
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
        if (previous == null || player.isSpectator()) {
            return;
        }
        beginPendingEventDialogueDenial(
            player, generationLevel, world, gameTime
        );
        if (handlePendingDenial(player, gameTime)) {
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
            boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
            double normal = horizontal ? player.getZ() - center.z() : player.getX() - center.x();
            double previousNormal = horizontal ? previous.z - center.z() : previous.x - center.x();
            double lateral = horizontal ? player.getX() - center.x() : player.getZ() - center.z();
            double threshold = Math.max(0.45D, gate.wallThickness() / 2.0D - 0.35D);
            double side = previousNormal == 0.0D
                ? (gate.facing().equals("north") || gate.facing().equals("west") ? -1.0D : 1.0D)
                : Math.signum(previousNormal);
            boolean crossed = side > 0.0D
                ? previousNormal > threshold && normal <= threshold
                : previousNormal < -threshold && normal >= -threshold;
            if (Math.abs(lateral) > gate.openingWidth() / 2.0D + 0.5D
                || !crossed || Math.abs(normal - previousNormal) >= 12.0D) {
                continue;
            }
            beginGateDenial(
                player, world, gate, center, horizontal,
                side, threshold, lateral, gameTime
            );
            return;
        }
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
            new Vec3(retreatX, retreatY, retreatZ), gameTime
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
                    > 10.0D * 10.0D
                || player.distanceToSqr(dialogueNpc) > 8.0D * 8.0D) {
                continue;
            }
            boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
            double normal = horizontal ? player.getZ() - center.z() : player.getX() - center.x();
            double lateral = horizontal ? player.getX() - center.x() : player.getZ() - center.z();
            double threshold = Math.max(0.45D, gate.wallThickness() / 2.0D - 0.35D);
            double side = normal == 0.0D
                ? (gate.facing().equals("north") || gate.facing().equals("west") ? 1.0D : -1.0D)
                : Math.signum(normal);
            PendingGateDenial pending = createGateDenial(
                player, gate, center, horizontal, side, threshold, lateral, gameTime
            );
            pending.sawDialogue = true;
            pending.dialogueOpen = dialogue.open;
            pending.finished = !dialogue.open;
            PENDING_DENIALS.put(player.getUUID(), pending);
            holdPlayer(player, pending.lockedPosition);
            LAST_POSITIONS.put(player.getUUID(), pending.lockedPosition);
            return;
        }
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

    private static boolean handlePendingDenial(ServerPlayer player, long gameTime) {
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
            return true;
        }
        holdPlayer(player, pending.lockedPosition);
        LAST_POSITIONS.put(player.getUUID(), pending.lockedPosition);
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
        for (int y = startY; y <= maximumY; y++) {
            if (canStandAt(level, x, y, z)) {
                return y;
            }
        }
        for (int y = startY - 1; y >= minimumY; y--) {
            if (canStandAt(level, x, y, z)) {
                return y;
            }
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
        AABB search = new AABB(
            center.x() - 8.0D, centerY - 4.0D, center.z() - 8.0D,
            center.x() + 8.0D, centerY + 5.0D, center.z() + 8.0D
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
    }

    private record GateStructurePlacement(StructureFootprint footprint) {}

    private record NaturalGateColumn(
        int x, int z, String terrainType, int distance, int offset
    ) {}

    private static final class PendingGateDenial {
        private final Vec3 lockedPosition;
        private final Vec3 retreatPosition;
        private final long startedAt;
        private boolean sawDialogue;
        private boolean dialogueOpen;
        private boolean finished;

        private PendingGateDenial(
            Vec3 lockedPosition, Vec3 retreatPosition, long startedAt
        ) {
            this.lockedPosition = lockedPosition;
            this.retreatPosition = retreatPosition;
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
