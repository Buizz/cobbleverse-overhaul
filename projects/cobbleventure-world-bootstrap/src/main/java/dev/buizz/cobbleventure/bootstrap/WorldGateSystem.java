package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexGrid;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
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
    private static final Map<String, ForestEntryMarker> FOREST_ENTRY_MARKERS = new HashMap<>();
    private static final Map<String, ForestEntryMarker> FOREST_EXIT_MARKERS = new HashMap<>();

    private WorldGateSystem() {
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
            List<Condition> conditions = new ArrayList<>();
            if (properties.has("conditions")) {
                for (JsonElement conditionElement : properties.getAsJsonArray("conditions")) {
                    conditions.add(parseCondition(conditionElement.getAsJsonObject()));
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
                optionalString(properties, "gate_mode", "classic"),
                optionalBoolean(properties, "building_enabled", true),
                optionalString(properties, "surrounding_type", "wall"),
                optionalString(properties, "wall_block", "minecraft:stone_bricks"),
                optionalString(properties, "tree_log", "minecraft:oak_log"),
                optionalString(properties, "tree_leaves", "minecraft:oak_leaves"),
                optionalInt(properties, "wall_thickness", 5),
                optionalInt(properties, "wall_height", 7),
                optionalInt(properties, "opening_width", 7),
                optionalInt(properties, "barrier_height", 24),
                optionalString(properties, "condition_mode", "all"),
                List.copyOf(conditions),
                optionalString(properties, "deny_message", "아직 이 관문을 통과할 수 없습니다."),
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
                direction, "classic", true, "none", "minecraft:mossy_stone_bricks",
                optionalString(value, "tree_log", "minecraft:spruce_log"),
                optionalString(value, "tree_leaves", "minecraft:spruce_leaves"),
                optionalInt(value, "wall_thickness", 7),
                optionalInt(value, "wall_height", 14),
                optionalInt(value, "opening_width", 7),
                optionalInt(value, "barrier_height", 32),
                "all", List.of(), "숲 입구입니다.", null,
                requiredString(value, "forest"), requiredString(value, "entrance"),
                null, null, null
            ));
        }
        return List.copyOf(gates);
    }

    private static Condition parseCondition(JsonObject value) {
        String type = requiredString(value, "type");
        return switch (type) {
            case "variable" -> new VariableCondition(
                optionalString(value, "source", "scoreboard"),
                requiredString(value, "key"),
                optionalString(value, "operator", ">="),
                value.get("value").getAsDouble()
            );
            case "item" -> new ItemCondition(
                requiredString(value, "item"), optionalInt(value, "count", 1),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            case "pokemon" -> new PokemonCondition(
                requiredString(value, "species"),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            default -> throw new IllegalStateException("Unsupported gate condition: " + type);
        };
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
        if (gate.gateMode().equals("system_only")) {
            return;
        }
        HexGrid grid = world.grid();
        CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
        BlockPos marker = new BlockPos(
            center.x(), grid.origin().y() - 16, center.z()
        );
        BlockState markerState = level.getBlockState(marker);
        boolean forestGate = gate.destinationForest() != null;
        if (markerState.is(Blocks.RESPAWN_ANCHOR)) {
            if (forestGate) {
                cacheForestEntryMarker(level, world, gate);
            }
            return;
        }
        boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
        int halfLength = Math.max(16, grid.radius() - 3);
        int halfThickness = gate.wallThickness() / 2;
        int halfOpening = gate.openingWidth() / 2;
        int centerY = groundY(level, center.x(), center.z());
        if (forestGate) {
            cacheForestEntryMarker(level, world, gate);
        }
        if (gate.gateMode().equals("classic") && gate.surroundingType().equals("wall")) {
            placeWallSurroundings(level, gate, center, horizontal, halfLength, halfThickness, halfOpening);
        } else if (gate.gateMode().equals("classic") && gate.surroundingType().equals("trees")) {
            placeTreeSurroundings(level, gate, center, horizontal, halfLength, halfThickness, halfOpening);
        }
        boolean shouldPlaceStructure = gate.gateMode().equals("classic")
            && gate.buildingEnabled();
        boolean structurePlaced = !shouldPlaceStructure
            || (forestGate
                ? placeForestStructure(level, world, gate)
                : placeStructure(level, gate, center, centerY));
        if (!structurePlaced) {
            LOGGER.error(
                "World gate remains incomplete because its NBT was not placed: gate={}",
                gate.id()
            );
            return;
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

    private static void placeWallSurroundings(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, int halfLength, int halfThickness, int halfOpening
    ) {
        BlockState wall = blockState(gate.wallBlock());
        for (int along = -halfLength; along <= halfLength; along++) {
            for (int across = -halfThickness; across <= halfThickness; across++) {
                int x = center.x() + (horizontal ? along : across);
                int z = center.z() + (horizontal ? across : along);
                int groundY = groundY(level, x, z);
                boolean opening = Math.abs(along) <= halfOpening;
                for (int height = 1; height <= gate.wallHeight(); height++) {
                    level.setBlock(new BlockPos(x, groundY + height, z),
                        opening ? Blocks.AIR.defaultBlockState() : wall, 2);
                }
                placeOverheadBarrier(level, x, z, groundY, gate.wallHeight(), gate.barrierHeight());
            }
        }
    }

    private static void placeTreeSurroundings(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center,
        boolean horizontal, int halfLength, int halfThickness, int halfOpening
    ) {
        BlockState log = blockState(gate.treeLog());
        BlockState leaves = blockState(gate.treeLeaves());
        for (int along = -halfLength; along <= halfLength; along++) {
            for (int across = -halfThickness; across <= halfThickness; across++) {
                int x = center.x() + (horizontal ? along : across);
                int z = center.z() + (horizontal ? across : along);
                int groundY = groundY(level, x, z);
                boolean opening = Math.abs(along) <= halfOpening;
                for (int height = 1; height <= gate.wallHeight(); height++) {
                    BlockState state = opening ? Blocks.AIR.defaultBlockState() : leaves;
                    if (!opening && across == 0 && Math.floorMod(along, 4) == 0
                        && height < gate.wallHeight()) {
                        state = log;
                    }
                    level.setBlock(new BlockPos(x, groundY + height, z), state, 2);
                }
                placeOverheadBarrier(level, x, z, groundY, gate.wallHeight(), gate.barrierHeight());
            }
        }
    }

    private static void placeOverheadBarrier(
        ServerLevel level, int x, int z, int groundY, int visibleHeight, int barrierHeight
    ) {
        for (int height = visibleHeight + 1; height <= barrierHeight; height++) {
            level.setBlock(new BlockPos(x, groundY + height, z), Blocks.BARRIER.defaultBlockState(), 2);
        }
    }

    private static boolean placeStructure(
        ServerLevel level, Gate gate, CobbleventureBootstrap.Point center, int groundY
    ) {
        if (gate.structure() == null) {
            LOGGER.error("Gate building is enabled but structure is missing: gate={}", gate.id());
            return false;
        }
        ResourceLocation structureId = ResourceLocation.tryParse(gate.structure());
        var template = structureId == null
            ? java.util.Optional.<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>empty()
            : level.getStructureManager().get(structureId);
        if (template.isEmpty()) {
            LOGGER.error("Gate structure is missing: gate={}, structure={}", gate.id(), gate.structure());
            return false;
        }
        Rotation rotation = rotation(gate.rotation());
        Vec3i size = template.orElseThrow().getSize(rotation);
        BlockPos origin = new BlockPos(
            center.x() - size.getX() / 2,
            groundY + 1,
            center.z() - size.getZ() / 2
        );
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        if (!template.orElseThrow().placeInWorld(
            level, origin, origin, settings,
            RandomSource.create(level.getSeed() ^ origin.asLong()), 2
        )) {
            LOGGER.error("Gate structure placement failed: gate={}, origin={}", gate.id(), origin);
            return false;
        }
        return true;
    }

    private static boolean placeForestStructure(
        ServerLevel level, HexWorldPlan world, Gate gate
    ) {
        ForestGateGeometry geometry = forestGateGeometry(world, gate);
        int groundY = CobbleventureBootstrap.plannedCaveMouthFloorY(
            level, geometry.x(), geometry.z()
        );
        ForestTemplatePlacement placement = forestTemplatePlacement(
            level, gate, geometry, groundY + 1
        );
        if (placement == null) {
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
        ForestEntryMarker entry = placedForestEntryMarker(gate, placement);
        if (entry == null) {
            return false;
        }
        FOREST_ENTRY_MARKERS.put(gate.id(), entry);
        level.setBlock(entry.position(), Blocks.AIR.defaultBlockState(), 2);
        CobbleventureBootstrap.Point roadEndpoint = world.grid().worldCenter(gate.anchor());
        layWorldForestEntranceRoad(
            level, world, entry, roadEndpoint
        );
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
                gateY
            );
            if (placement == null || !placement.template().placeInWorld(
                level, placement.origin(), placement.origin(), placement.settings(),
                RandomSource.create(level.getSeed() ^ placement.origin().asLong()), 2
            )) {
                throw new IllegalStateException(
                    "Forest dimension gate placement failed: " + gate.id()
                );
            }
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
        CobbleventureBootstrap.Point roadEndpoint
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
        ForestTemplatePlacement placement = forestTemplatePlacement(
            level, gate, geometry, 0
        );
        ForestEntryMarker entry = placement == null
            ? null : placedForestEntryMarker(gate, placement);
        if (entry != null) {
            FOREST_ENTRY_MARKERS.put(gate.id(), entry);
        }
    }

    private static ForestTemplatePlacement forestTemplatePlacement(
        ServerLevel level, Gate gate, ForestGateGeometry geometry, int targetY
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
        BlockPos origin = rotatedTemplateOrigin(
            minX, targetY - rotatedAnchor.getY(), minZ,
            size.getX(), size.getZ(), rotation
        );
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        int outsideOffset = (geometry.inward().getAxis() == Direction.Axis.X
            ? template.getSize(rotation).getX()
            : template.getSize(rotation).getZ()) + 1;
        return new ForestTemplatePlacement(
            template, settings, origin,
            new BlockPos(geometry.x(), targetY, geometry.z()),
            geometry.inward(), outsideOffset
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
        CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
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
        ServerPlayer player, ServerLevel generationLevel, HexGrid grid,
        List<Gate> gates, long gameTime
    ) {
        Vec3 previous = LAST_POSITIONS.put(player.getUUID(), player.position());
        if (previous == null || player.isSpectator()) {
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
            if (gate.gateMode().equals("npc_only")) {
                continue;
            }
            if (gate.gateMode().equals("system_only")) {
                if (grid.worldToHex(player.getX(), player.getZ()).equals(gate.anchor())) {
                    rejectFromSystemGate(player, grid, gate, previous, gameTime);
                    return;
                }
                continue;
            }
            CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
            boolean horizontal = gate.facing().equals("north") || gate.facing().equals("south");
            double normal = horizontal ? player.getZ() - center.z() : player.getX() - center.x();
            double previousNormal = horizontal ? previous.z - center.z() : previous.x - center.x();
            double lateral = horizontal ? player.getX() - center.x() : player.getZ() - center.z();
            double limit = gate.wallThickness() / 2.0D + 1.25D;
            boolean crossed = normal * previousNormal <= 0.0D
                && Math.abs(normal - previousNormal) < 12.0D;
            if (Math.abs(lateral) > gate.openingWidth() / 2.0D + 1.5D
                || (!crossed && Math.abs(normal) > limit)) {
                continue;
            }
            double side = previousNormal == 0.0D
                ? (gate.facing().equals("north") || gate.facing().equals("west") ? -1.0D : 1.0D)
                : Math.signum(previousNormal);
            double safeNormal = side * (limit + 0.75D);
            double x = horizontal ? player.getX() : center.x() + safeNormal;
            double z = horizontal ? center.z() + safeNormal : player.getZ();
            player.teleportTo(player.serverLevel(), x, player.getY(), z, player.getYRot(), player.getXRot());
            LAST_POSITIONS.put(player.getUUID(), new Vec3(x, player.getY(), z));
            showDenyMessage(player, gate, gameTime);
            return;
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

    private static void rejectFromSystemGate(
        ServerPlayer player, HexGrid grid,
        Gate gate, Vec3 previous, long gameTime
    ) {
        double x = previous.x;
        double y = previous.y;
        double z = previous.z;
        if (grid.worldToHex(previous.x, previous.z).equals(gate.anchor())) {
            CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
            double dx = player.getX() - center.x();
            double dz = player.getZ() - center.z();
            double length = Math.hypot(dx, dz);
            if (length < 0.01D) {
                dx = 0.0D;
                dz = -1.0D;
                length = 1.0D;
            }
            double distance = grid.radius() + 3.0D;
            x = center.x() + dx / length * distance;
            z = center.z() + dz / length * distance;
            y = groundY(player.serverLevel(), (int) Math.floor(x), (int) Math.floor(z)) + 1.0D;
        }
        player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot());
        LAST_POSITIONS.put(player.getUUID(), new Vec3(x, y, z));
        showDenyMessage(player, gate, gameTime);
    }

    private static void showDenyMessage(ServerPlayer player, Gate gate, long gameTime) {
        if (player.getPersistentData().getLong(DENY_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(DENY_COOLDOWN, gameTime + 40L);
            player.sendSystemMessage(Component.literal(gate.denyMessage()), true);
        }
    }

    static void forget(ServerPlayer player) {
        LAST_POSITIONS.remove(player.getUUID());
    }

    static int teleportToGate(
        ServerLevel level,
        Iterable<? extends Entity> targets,
        HexGrid grid,
        List<Gate> gates,
        String gateId,
        String side
    ) {
        Gate gate = gates.stream().filter(value -> value.id().equals(gateId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown world gate: " + gateId));
        CobbleventureBootstrap.Point center = grid.worldCenter(gate.anchor());
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

    sealed interface Condition permits VariableCondition, ItemCondition, PokemonCondition {
        boolean matches(ServerPlayer player);
    }

    record VariableCondition(String source, String key, String operator, double value)
        implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            double actual;
            if (source.equals("persistent_data")) {
                actual = player.getPersistentData().getDouble(key);
            } else {
                Objective objective = player.getScoreboard().getObjective(key);
                actual = objective == null ? 0.0D
                    : player.getScoreboard().getOrCreatePlayerScore(player, objective).get();
            }
            return switch (operator) {
                case "==" -> actual == value;
                case "!=" -> actual != value;
                case ">" -> actual > value;
                case "<" -> actual < value;
                case "<=" -> actual <= value;
                default -> actual >= value;
            };
        }
    }

    record ItemCondition(String item, int count, boolean negate) implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            ResourceLocation id = ResourceLocation.tryParse(item);
            Item required = id == null ? null : BuiltInRegistries.ITEM.get(id);
            boolean present = required != null && player.getInventory().countItem(required) >= count;
            return negate != present;
        }
    }

    record PokemonCondition(String species, boolean negate) implements Condition {
        @Override
        public boolean matches(ServerPlayer player) {
            boolean present = false;
            for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                if (pokemon.getSpecies().getResourceIdentifier().toString().equals(species)) {
                    present = true;
                    break;
                }
            }
            return negate != present;
        }
    }

    record Gate(
        String id,
        HexCoord anchor,
        String structure,
        int rotation,
        String facing,
        String gateMode,
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
        List<Condition> conditions,
        String denyMessage,
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
            return conditionMode.equals("any")
                ? conditions.stream().anyMatch(condition -> condition.matches(player))
                : conditions.stream().allMatch(condition -> condition.matches(player));
        }

        Gate withForestDestination(
            ResourceKey<Level> dimension,
            CobbleventureBootstrap.BlockPoint destination,
            CobbleventureBootstrap.BlockPoint portalAnchor
        ) {
            return new Gate(
                id, anchor, structure, rotation, facing, gateMode, buildingEnabled,
                surroundingType, wallBlock, treeLog, treeLeaves, wallThickness,
                wallHeight, openingWidth, barrierHeight, conditionMode, conditions,
                denyMessage, npc, destinationForest, destinationEntrance,
                dimension, destination, portalAnchor
            );
        }
    }

    private record ForestEntryMarker(
        BlockPos position, Direction inward, int outsideOffset
    ) {}

    private record ForestGateGeometry(int x, int z, Direction inward) {}

    private record ForestTemplatePlacement(
        StructureTemplate template,
        StructurePlaceSettings settings,
        BlockPos origin,
        BlockPos expectedEntry,
        Direction inward,
        int outsideOffset
    ) {}
}
