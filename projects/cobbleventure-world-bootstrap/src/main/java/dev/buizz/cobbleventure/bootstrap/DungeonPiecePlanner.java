package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Builds a bounded critical path with optional side branches from authored NBT pieces. */
final class DungeonPiecePlanner {
    private static final int MAX_SEARCH_NODES_PER_ATTEMPT = 20_000;
    private static final List<Rotation> ROTATIONS = List.of(
        Rotation.NONE,
        Rotation.CLOCKWISE_90,
        Rotation.CLOCKWISE_180,
        Rotation.COUNTERCLOCKWISE_90
    );

    private DungeonPiecePlanner() {}

    static DungeonPiecePlan generate(
        Collection<DungeonPieceDefinition> definitions,
        Settings settings,
        long seed
    ) {
        return generate(definitions, settings, seed, Long.MAX_VALUE);
    }

    static DungeonPiecePlan generate(
        Collection<DungeonPieceDefinition> definitions,
        Settings settings,
        long seed,
        long deadlineNanos
    ) {
        settings.validate();
        List<DungeonPieceDefinition> pieces = List.copyOf(definitions);
        requireRoles(pieces);
        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
        validateRequiredChambers(byId, settings.requiredChamberPieces());
        if (settings.verticalMode().equals("discrete_floors")
            && settings.floorChangesMax() > 0) {
            return generateSeparateFloors(
                pieces, settings, seed, deadlineNanos
            );
        }
        return generateContinuous(pieces, byId, settings, seed, deadlineNanos);
    }

    private static DungeonPiecePlan generateContinuous(
        List<DungeonPieceDefinition> pieces,
        Map<String, DungeonPieceDefinition> byId,
        Settings settings,
        long seed,
        long deadlineNanos
    ) {
        List<DungeonPieceDefinition> planningPieces = selectablePieces(
            pieces, settings
        );
        String lastStage = "start";
        for (int attempt = 0; attempt < settings.maxAttempts(); attempt++) {
            if (System.nanoTime() >= deadlineNanos) break;
            SearchBudget budget = new SearchBudget(
                deadlineNanos, MAX_SEARCH_NODES_PER_ATTEMPT
            );
            Random random = new Random(mixSeed(seed, attempt));
            State state = startState(planningPieces, settings, random);
            if (state == null) {
                lastStage = "start";
                continue;
            }
            int targetRooms = randomRange(
                random, settings.criticalPathMin(), settings.criticalPathMax()
            );
            int targetBranches = randomRange(
                random, settings.branchCountMin(), settings.branchCountMax()
            );
            if (!extendCritical(
                state, planningPieces, settings, random, targetRooms, targetBranches, 1,
                budget
            )) {
                lastStage = "critical_path";
                continue;
            }
            DungeonPiecePlan looped = DungeonPieceLoops.add(
                state.toPlan(seed, settings.bounds()), byId,
                settings.loopChance(), mixSeed(seed, attempt + 10_000)
            );
            state.absorbAdditionalLinks(looped.links());
            if (!completeOpenConnectors(
                state, planningPieces, settings, random, budget
            )) {
                lastStage = "open_connectors";
                continue;
            }
            if (!usageSatisfied(state, planningPieces)) {
                lastStage = "piece_usage";
                continue;
            }
            return state.toPlan(seed, settings.bounds());
        }
        throw new IllegalStateException(
            "Dungeon piece planning failed after " + settings.maxAttempts()
                + " attempts at " + lastStage
        );
    }

    /**
     * Builds every horizontal floor as a complete local plan first. Only after all
     * floors succeed are their reserved ports aligned with rotated stair pieces.
     */
    private static DungeonPiecePlan generateSeparateFloors(
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        long seed,
        long deadlineNanos
    ) {
        List<DungeonPieceDefinition> planningPieces = selectablePieces(
            pieces, settings
        );
        IllegalStateException lastFailure = null;
        for (int attempt = 0; attempt < settings.maxAttempts(); attempt++) {
            if (System.nanoTime() >= deadlineNanos) break;
            Random layoutRandom = new Random(mixSeed(seed, attempt));
            try {
                int floorCount = settings.floorChangesMax() + 1;
                FloorAllocation allocation = allocateFloors(
                    planningPieces, settings, floorCount, layoutRandom
                );
                int branches = randomRange(
                    layoutRandom, settings.branchCountMin(), settings.branchCountMax()
                );
                List<FloorResult> floors = new ArrayList<>();
                for (int floor = 0; floor < floorCount; floor++) {
                    // A floor owns its random stream. Equal room counts must not make
                    // later floors replay the same compact attachment choices.
                    Random floorRandom = new Random(mixSeed(
                        mixSeed(seed, attempt), floor + 1
                    ));
                    int floorBranches = branches / floorCount
                        + (floor < branches % floorCount ? 1 : 0);
                    int floorNumber = floor;
                    List<String> floorRequired = allocation.requiredChambers().get(floor);
                    List<DungeonPieceDefinition> floorPieces = planningPieces.stream()
                        .filter(piece -> floorRequired.contains(piece.id())
                            || piece.maximumPerPlan() > floorNumber)
                        .filter(piece -> !piece.role().equals("room")
                            || !floorRequired.isEmpty())
                        .toList();
                    FloorResult result = generateFloor(
                        floorPieces, settings, allocation.placementCounts().get(floor),
                        floorRequired, floor == 0,
                        floor == floorCount - 1, floorBranches, floorRandom,
                        new SearchBudget(deadlineNanos, MAX_SEARCH_NODES_PER_ATTEMPT)
                    );
                    if (result == null) {
                        throw new IllegalStateException(
                            "Dungeon floor planning failed: " + (floor + 1)
                        );
                    }
                    floors.add(result);
                }
                return connectFloors(floors, planningPieces, settings, seed);
            } catch (IllegalStateException failure) {
                lastFailure = failure;
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IllegalStateException("Dungeon separate-floor planning timed out");
    }

    private static FloorAllocation allocateFloors(
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        int floorCount,
        Random random
    ) {
        // Every floor needs enough path depth for its routes. Ordinary chamber
        // pieces are opt-in through requiredChamberPieces; start, boss and exit
        // remain structural endpoints.
        int minimumPlacements = floorCount * 10 + 1;
        int requested = randomRange(
            random, settings.criticalPathMin(), settings.criticalPathMax()
        );
        int total = Math.max(
            requested, minimumPlacements + settings.requiredChamberPieces().size()
        );
        List<Integer> counts = new ArrayList<>();
        List<Long> occupiedCells = new ArrayList<>();
        for (int floor = 0; floor < floorCount; floor++) {
            int base = floor == floorCount - 1 ? 11 : 10;
            counts.add(base);
            occupiedCells.add((long) base);
        }

        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toMap(DungeonPieceDefinition::id, value -> value)
        );
        List<List<String>> assigned = new ArrayList<>();
        for (int floor = 0; floor < floorCount; floor++) {
            assigned.add(new ArrayList<>());
        }
        List<String> largestFirst = settings.requiredChamberPieces().stream()
            .sorted(Comparator.comparingLong((String id) -> {
                BlockPos size = byId.get(id).size();
                return (long) size.getX() * size.getZ();
            }).reversed()).toList();
        for (String id : largestFirst) {
            int target = java.util.stream.IntStream.range(0, floorCount).boxed()
                .min(Comparator.comparingLong(occupiedCells::get)).orElseThrow();
            assigned.get(target).add(id);
            BlockPos size = byId.get(id).size();
            long footprintCells = (long) Math.ceil(size.getX() / 16.0D)
                * (long) Math.ceil(size.getZ() / 16.0D);
            counts.set(target, counts.get(target) + 1);
            occupiedCells.set(target, occupiedCells.get(target) + footprintCells);
        }
        for (int remaining = total - counts.stream().mapToInt(Integer::intValue).sum();
             remaining > 0; remaining--) {
            int floor = java.util.stream.IntStream.range(0, floorCount)
                .boxed().min(Comparator.comparingLong(occupiedCells::get)).orElseThrow();
            counts.set(floor, counts.get(floor) + 1);
            occupiedCells.set(floor, occupiedCells.get(floor) + 1L);
        }
        return new FloorAllocation(
            List.copyOf(counts), assigned.stream().map(List::copyOf).toList()
        );
    }

    private static FloorResult generateFloor(
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        int placementCount,
        List<String> requiredChambers,
        boolean firstFloor,
        boolean lastFloor,
        int branchCount,
        Random random,
        SearchBudget budget
    ) {
        for (int attempt = 0; attempt < 32; attempt++) {
            FloorResult result = generateFloorAttempt(
                pieces, settings, placementCount, requiredChambers,
                firstFloor, lastFloor, branchCount, random, budget
            );
            if (result != null) return result;
        }
        return null;
    }

    private static FloorResult generateFloorAttempt(
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        int placementCount,
        List<String> requiredChambers,
        boolean firstFloor,
        boolean lastFloor,
        int branchCount,
        Random random,
        SearchBudget budget
    ) {
        int largestWidth = pieces.stream()
            .filter(piece -> requiredChambers.contains(piece.id()))
            .mapToInt(piece -> Math.max(piece.size().getX(), piece.size().getZ()))
            .max().orElse(16);
        int wantedSide = Math.max(
            64, (int) Math.ceil(Math.sqrt(placementCount * 2.0D)) * 16
                + largestWidth + 32
        );
        if (largestWidth > 16) {
            wantedSide = Math.max(
                wantedSide,
                Math.min(settings.bounds().getX(), settings.bounds().getZ())
            );
        }
        BlockPos localBounds = new BlockPos(
            Math.min(settings.bounds().getX(), wantedSide),
            settings.floorHeight(),
            Math.min(settings.bounds().getZ(), wantedSide)
        );
        Settings floorSettings = withBounds(settings, localBounds, requiredChambers);
        List<String> roles = new ArrayList<>();
        roles.add(firstFloor ? "start" : "corridor");
        int ending = lastFloor ? 2 : 1;
        while (roles.size() < placementCount - ending) roles.add("flexible");
        if (lastFloor) {
            roles.add("boss"); roles.add("exit");
        } else {
            roles.add("corridor");
        }
        State state = new State();
        StartChoice start = floorStart(
            pieces, floorSettings, roles.getFirst(), requiredChambers,
            !firstFloor, random
        );
        if (start == null) return null;
        state.placements.add(start.placed());
        if (start.reservedIncoming() != null) {
            state.usedConnectors.add(new ConnectorKey(
                start.placed(), start.reservedIncoming().id()
            ));
            Box stairClearance = incomingStairBox(
                start.placed(), start.reservedIncoming(), pieces,
                settings.verticalDirection()
            );
            if (stairClearance == null
                || overlapsAny(stairClearance, state.placements)) return null;
            state.reservedBoxes.add(stairClearance);
        }
        if (!extendFloorPath(
            state, pieces, floorSettings, roles, 1, requiredChambers,
            !lastFloor, branchCount, firstFloor, random, budget
        )) return null;

        Placed last = state.placements.getLast();
        DungeonPieceDefinition.Connector outgoing = null;
        if (!lastFloor) {
            outgoing = openForStair(
                last, state, floorSettings.bounds(), pieces,
                settings.verticalDirection()
            );
            if (outgoing == null) return null;
            state.usedConnectors.add(new ConnectorKey(last, outgoing.id()));
        }
        if (!attachBranches(
            state, pieces, floorSettings,
            random, branchCount, budget
        )) return null;
        if (!completeOpenConnectors(
            state, pieces, floorSettings, random, budget
        )) return null;
        if (!requiredChambersSatisfied(
            state, floorSettings
        )) return null;
        if (!usageSatisfied(state, pieces)) return null;
        return new FloorResult(
            state, start.placed(), last,
            start.reservedIncoming() == null ? null
                : start.reservedIncoming().id(),
            outgoing == null ? null : outgoing.id()
        );
    }

    private static Settings withBounds(
        Settings source, BlockPos bounds, List<String> required
    ) {
        return new Settings(
            bounds, source.criticalPathMin(), source.criticalPathMax(),
            source.branchCountMin(), source.branchCountMax(),
            source.branchDepthMin(), source.branchDepthMax(), source.loopChance(),
            1, source.layoutMode(), "flat", 0, 0, "flat",
            source.floorHeight(), required
        );
    }

    private static StartChoice floorStart(
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        String role,
        List<String> required,
        boolean reserveIncoming,
        Random random
    ) {
        List<DungeonPieceDefinition> candidates = prioritizeIds(
            weightedOrder(pieces.stream()
                .filter(piece -> piece.role().equals(role))
                .filter(piece -> !isVerticalTransition(piece))
                .filter(piece -> piece.allowsPlacement(true))
                .filter(piece -> piece.maximumPerPlan() >= 1).toList(), random),
            required
        );
        for (DungeonPieceDefinition piece : candidates) {
            for (Rotation rotation : rotationOrder(piece, random)) {
                DungeonPieceDefinition.Connector incoming = reserveIncoming
                    ? shuffled(piece.connectors(), random).stream().findFirst().orElse(null)
                    : null;
                if (reserveIncoming && incoming == null) continue;
                if (reserveIncoming && piece.connectors().size() < 2) continue;
                LocalBounds local = localBounds(piece.size(), rotation);
                BlockPos minimum = new BlockPos(
                    (settings.bounds().getX() - local.size().getX()) / 2,
                    0,
                    (settings.bounds().getZ() - local.size().getZ()) / 2
                );
                Placed placed = placed(
                    piece, minimum.subtract(local.minimum()), rotation, true
                );
                if (inside(placed.box(), settings.bounds())) {
                    return new StartChoice(placed, incoming);
                }
            }
        }
        return null;
    }

    private static boolean extendFloorPath(
        State state,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        List<String> roles,
        int index,
        List<String> required,
        boolean reserveOutgoing,
        int branchCount,
        boolean firstFloor,
        Random random,
        SearchBudget budget
    ) {
        if (!budget.tryVisit()) return false;
        if (index >= roles.size()) {
            Set<String> placed = state.placements.stream()
                .map(value -> value.definition().id())
                .collect(java.util.stream.Collectors.toSet());
            return placed.containsAll(required);
        }
        String wanted = roles.get(index);
        Set<String> missing = required.stream().filter(id -> state.placements.stream()
            .noneMatch(value -> value.definition().id().equals(id)))
            .collect(java.util.stream.Collectors.toSet());
        int remainingRoomSlots = (int) roles.subList(index, roles.size()).stream()
            .filter(role -> role.equals("room") || role.equals("flexible")).count();
        Set<String> configuredRoles = criticalRoles(settings.layoutMode(), index);
        Set<String> flexible = new HashSet<>(configuredRoles);
        boolean hasSelectableChambers = pieces.stream()
            .anyMatch(piece -> piece.role().equals("room"));
        if (!hasSelectableChambers
            && configuredRoles.contains("room")) {
            flexible.add("corridor"); flexible.add("junction");
        }
        boolean needsBranchHost = state.branchHostCount() < branchCount;
        List<DungeonPieceDefinition> candidates = pieces.stream()
            .filter(piece -> wanted.equals("flexible")
                ? flexible.contains(piece.role()) || missing.contains(piece.id())
                    || firstFloor && flexible.contains("room")
                        && piece.role().equals("support")
                : piece.role().equals(wanted))
            .filter(piece -> !isVerticalTransition(piece))
            .filter(DungeonPiecePlanner::hasConsistentSpatialKind)
            .filter(piece -> piece.allowsPlacement(true))
            .filter(piece -> canUse(state, piece))
            .filter(piece -> missing.contains(piece.id())
                || wanted.equals("boss") || wanted.equals("exit")
                || finalPathConnectorCount(piece, needsBranchHost))
            .filter(piece -> missing.size() < remainingRoomSlots
                || missing.isEmpty() || missing.contains(piece.id()))
            .toList();
        candidates = prioritizeIds(weightedOrder(candidates, random), missing);
        long expandedChambers = state.placements.stream()
            .filter(placed -> placed.definition().spatialKind().equals("chamber"))
            .filter(placed -> horizontalFootprintCells(placed.definition()) >= 2)
            .count();
        if (expandedChambers < 2 && (wanted.equals("room")
            || wanted.equals("flexible") && flexible.contains("room"))) {
            candidates = prioritizeLargestChamber(candidates, missing);
        }
        boolean needsSupport = firstFloor && state.placements.stream()
            .noneMatch(placed -> placed.definition().role().equals("support"));
        if (needsSupport && (wanted.equals("room")
            || wanted.equals("flexible") && flexible.contains("room"))) {
            candidates = prioritizeSupportChamber(candidates, missing);
        }
        Placed current = state.placements.getLast();
        boolean finalPlacement = index == roles.size() - 1;
        List<Attachment> attachments = new ArrayList<>();
        for (DungeonPieceDefinition.Connector from : connectorOrder(current, state, random)) {
            for (DungeonPieceDefinition piece : candidates) {
                for (Rotation rotation : rotationOrder(piece, random)) {
                    if (finalPlacement && reserveOutgoing
                        && piece.connectors().size() < 2) continue;
                    for (DungeonPieceDefinition.Connector to
                        : shuffled(piece.connectors(), random)) {
                        Attachment attachment = attachment(
                            current, from, piece, to, rotation, true
                        );
                        if (attachment == null
                            || !inside(attachment.placed().box(), settings.bounds())
                            || overlapsState(attachment.placed().box(), state)) continue;
                        attachments.add(attachment);
                    }
                }
            }
        }
        attachments.sort(Comparator.comparingInt(attachment ->
            compactnessScore(state, attachment.placed())
        ));
        int explored = 0;
        for (Attachment attachment : attachments) {
            if (explored++ >= 24) break;
            state.add(attachment);
            if (extendFloorPath(
                state, pieces, settings, roles, index + 1, required,
                reserveOutgoing, branchCount, firstFloor, random, budget
            )) return true;
            state.removeLast(attachment);
        }
        return false;
    }

    private static boolean finalPathConnectorCount(
        DungeonPieceDefinition piece, boolean needsBranchHost
    ) {
        return needsBranchHost ? piece.connectors().size() >= 3
            : piece.connectors().size() <= 2;
    }

    private static List<DungeonPieceDefinition> prioritizeIds(
        List<DungeonPieceDefinition> candidates, Collection<String> preferred
    ) {
        List<DungeonPieceDefinition> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingInt(piece -> preferred.contains(piece.id()) ? 0 : 1));
        return result;
    }

    private static List<DungeonPieceDefinition> prioritizeMinimumUsage(
        State state, List<DungeonPieceDefinition> candidates
    ) {
        List<DungeonPieceDefinition> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingInt(piece ->
            state.placements.stream().filter(placed ->
                placed.definition().id().equals(piece.id())
            ).count() < piece.minimumPerPlan() ? 0 : 1
        ));
        return result;
    }

    private static List<DungeonPieceDefinition> prioritizeLargestChamber(
        List<DungeonPieceDefinition> candidates, Collection<String> required
    ) {
        List<DungeonPieceDefinition> result = new ArrayList<>(candidates);
        result.sort(Comparator
            .comparingInt((DungeonPieceDefinition piece) ->
                required.contains(piece.id()) ? 0 : 1)
            .thenComparingInt(piece -> horizontalFootprintCells(piece) >= 2 ? 0 : 1));
        return result;
    }

    private static List<DungeonPieceDefinition> prioritizeSupportChamber(
        List<DungeonPieceDefinition> candidates, Collection<String> required
    ) {
        List<DungeonPieceDefinition> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingInt(piece ->
            required.contains(piece.id()) ? 0
                : piece.role().equals("support") ? 1 : 2
        ));
        return result;
    }

    private static long horizontalFootprintCells(DungeonPieceDefinition piece) {
        return (long) Math.ceil(piece.size().getX() / 16.0D)
            * (long) Math.ceil(piece.size().getZ() / 16.0D);
    }

    private static DungeonPieceDefinition.Connector connectorFacing(
        DungeonPieceDefinition piece, Rotation rotation, Direction facing
    ) {
        return piece.connectors().stream()
            .filter(connector -> rotation.rotate(connector.facing()) == facing)
            .findFirst().orElse(null);
    }

    private static DungeonPieceDefinition.Connector openForStair(
        Placed placed,
        State state,
        BlockPos bounds,
        List<DungeonPieceDefinition> pieces,
        String verticalDirection
    ) {
        String shape = verticalDirection.equals("descending")
            ? "stairs_down" : "stairs_up";
        DungeonPieceDefinition stair = pieces.stream()
            .filter(DungeonPiecePlanner::isVerticalTransition)
            .filter(piece -> piece.id().endsWith("/" + shape)
                || piece.tags().stream().anyMatch(tag -> tag.endsWith("/" + shape)))
            .findFirst().orElse(null);
        if (stair == null) return null;
        DungeonPieceDefinition.Connector stairFrom = stair.connectors().stream()
            .filter(connector -> connector.facing() == Direction.WEST)
            .findFirst().orElse(null);
        if (stairFrom == null) return null;
        BlockPos center = new BlockPos(bounds.getX() / 2, 0, bounds.getZ() / 2);
        return placed.definition().connectors().stream()
            .filter(connector -> !state.usedConnectors.contains(
                new ConnectorKey(placed, connector.id())
            )).filter(connector -> {
                Direction facing = placed.rotation().rotate(connector.facing());
                Rotation stairRotation = ROTATIONS.stream().filter(rotation ->
                    rotation.rotate(stairFrom.facing()) == facing.getOpposite()
                ).findFirst().orElse(null);
                if (stairRotation == null) return false;
                BlockPos position = connectorPosition(
                    placed, connector, BlockPos.ZERO
                );
                BlockPos origin = position.relative(facing).subtract(
                    transform(stairFrom.position(), stairRotation)
                );
                return !overlapsState(
                    placed(stair, origin, stairRotation, true).box(), state
                );
            }).min(Comparator.comparingInt(connector -> {
                Direction facing = placed.rotation().rotate(connector.facing());
                BlockPos position = connectorPosition(
                    placed, connector, BlockPos.ZERO
                ).relative(facing);
                return Math.abs(position.getX() - center.getX())
                    + Math.abs(position.getZ() - center.getZ());
            })).orElse(null);
    }

    private static Box incomingStairBox(
        Placed incomingPiece,
        DungeonPieceDefinition.Connector incoming,
        List<DungeonPieceDefinition> pieces,
        String verticalDirection
    ) {
        String shape = verticalDirection.equals("descending")
            ? "stairs_down" : "stairs_up";
        DungeonPieceDefinition stair = pieces.stream()
            .filter(DungeonPiecePlanner::isVerticalTransition)
            .filter(piece -> piece.id().endsWith("/" + shape)
                || piece.tags().stream().anyMatch(tag -> tag.endsWith("/" + shape)))
            .findFirst().orElse(null);
        if (stair == null) return null;
        Direction incomingFacing = incomingPiece.rotation().rotate(incoming.facing());
        DungeonPieceDefinition.Connector stairTo = stair.connectors().stream()
            .filter(connector -> connector.facing() == Direction.EAST)
            .findFirst().orElse(null);
        if (stairTo == null) return null;
        Rotation stairRotation = ROTATIONS.stream().filter(rotation ->
            rotation.rotate(stairTo.facing()) == incomingFacing.getOpposite()
        ).findFirst().orElse(null);
        if (stairRotation == null) return null;
        BlockPos incomingPosition = connectorPosition(
            incomingPiece, incoming, BlockPos.ZERO
        );
        BlockPos stairOrigin = incomingPosition.relative(incomingFacing)
            .subtract(transform(stairTo.position(), stairRotation));
        return placed(stair, stairOrigin, stairRotation, true).box();
    }

    private static DungeonPiecePlan connectFloors(
        List<FloorResult> floors,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        long seed
    ) {
        boolean descending = settings.verticalDirection().equals("descending");
        String stairShape = descending ? "stairs_down" : "stairs_up";
        DungeonPieceDefinition stair = pieces.stream()
            .filter(DungeonPiecePlanner::isVerticalTransition)
            .filter(piece -> piece.id().endsWith("/" + stairShape)
                || piece.tags().stream().anyMatch(tag ->
                    tag.endsWith("/" + stairShape)))
            .findFirst().orElseThrow(() -> new IllegalStateException(
                "Dungeon piece pool has no " + stairShape + " piece"
            ));
        DungeonPieceDefinition.Connector stairWest = connectorFacing(
            stair, Rotation.NONE, Direction.WEST
        );
        DungeonPieceDefinition.Connector stairEast = connectorFacing(
            stair, Rotation.NONE, Direction.EAST
        );
        if (stairWest == null || stairEast == null) {
            throw new IllegalStateException(
                "Dungeon stair requires west and east connectors: " + stair.id()
            );
        }

        List<FloorTransform> transforms = new ArrayList<>();
        List<StairJoin> joins = new ArrayList<>();
        int firstY = descending
            ? (floors.size() - 1) * settings.floorHeight() : 0;
        transforms.add(new FloorTransform(
            Rotation.NONE, new BlockPos(0, firstY, 0)
        ));
        for (int floor = 0; floor < floors.size() - 1; floor++) {
            FloorResult current = floors.get(floor);
            FloorResult next = floors.get(floor + 1);
            DungeonPieceDefinition.Connector outgoing = connector(
                current.outgoingPiece().definition(), current.outgoingConnector()
            );
            FloorTransform currentTransform = transforms.get(floor);
            BlockPos outgoingPosition = transformedConnectorPosition(
                current.outgoingPiece(), outgoing, currentTransform
            );
            Direction outgoingFacing = currentTransform.rotation().rotate(
                current.outgoingPiece().rotation().rotate(outgoing.facing())
            );
            Rotation stairRotation = ROTATIONS.stream().filter(rotation ->
                rotation.rotate(stairWest.facing()) == outgoingFacing.getOpposite()
            ).findFirst().orElseThrow();
            BlockPos stairOrigin = outgoingPosition.relative(outgoingFacing)
                .subtract(transform(stairWest.position(), stairRotation));
            DungeonPieceDefinition.Connector incoming = connector(
                next.incomingPiece().definition(), next.incomingConnector()
            );
            Direction stairToFacing = stairRotation.rotate(stairEast.facing());
            BlockPos wantedIncoming = stairOrigin.offset(
                transform(stairEast.position(), stairRotation)
            ).relative(stairToFacing);
            Direction localIncomingFacing = next.incomingPiece().rotation().rotate(
                incoming.facing()
            );
            Rotation nextRotation = ROTATIONS.stream().filter(rotation ->
                rotation.rotate(localIncomingFacing) == stairToFacing.getOpposite()
            ).findFirst().orElseThrow();
            BlockPos localIncoming = next.incomingPiece().origin().offset(
                transform(incoming.position(), next.incomingPiece().rotation())
            );
            BlockPos nextShift = wantedIncoming.subtract(
                transform(localIncoming, nextRotation)
            );
            int expectedY = descending
                ? firstY - (floor + 1) * settings.floorHeight()
                : (floor + 1) * settings.floorHeight();
            if (nextShift.getY() != expectedY) {
                throw new IllegalStateException(
                    "Dungeon stair height differs from configured floor height"
                );
            }
            transforms.add(new FloorTransform(nextRotation, nextShift));
            joins.add(new StairJoin(
                floor, stairOrigin, stair, stairRotation,
                stairWest.id(), stairEast.id()
            ));
        }

        List<DungeonPiecePlan.Placement> placements = new ArrayList<>();
        List<DungeonPiecePlan.Link> links = new ArrayList<>();
        List<Map<Placed, Integer>> indices = new ArrayList<>();
        List<Integer> stairIndices = new ArrayList<>();
        for (int floor = 0; floor < floors.size(); floor++) {
            FloorResult result = floors.get(floor);
            FloorTransform floorTransform = transforms.get(floor);
            Map<Placed, Integer> floorIndices = new java.util.HashMap<>();
            for (Placed placed : result.state().placements) {
                int index = placements.size();
                floorIndices.put(placed, index);
                placements.add(toPlacement(index, placed, floorTransform));
            }
            for (PlanLink link : result.state().links) {
                Placed from = result.state().placements.get(link.fromIndex());
                Placed to = result.state().placements.get(link.toIndex());
                links.add(new DungeonPiecePlan.Link(
                    floorIndices.get(from), link.fromConnector(),
                    floorIndices.get(to), link.toConnector(), link.critical()
                ));
            }
            indices.add(floorIndices);
            if (floor < joins.size()) {
                StairJoin join = joins.get(floor);
                int stairIndex = placements.size();
                Placed stairPlaced = placed(
                    join.piece(), join.origin(), join.rotation(), true
                );
                placements.add(toPlacement(
                    stairIndex, stairPlaced,
                    new FloorTransform(Rotation.NONE, BlockPos.ZERO)
                ));
                stairIndices.add(stairIndex);
            }
        }
        for (StairJoin join : joins) {
            int index = stairIndices.get(join.floor());
            FloorResult current = floors.get(join.floor());
            FloorResult next = floors.get(join.floor() + 1);
            links.add(new DungeonPiecePlan.Link(
                indices.get(join.floor()).get(current.outgoingPiece()),
                current.outgoingConnector(), index, join.fromConnector(), true
            ));
            links.add(new DungeonPiecePlan.Link(
                index, join.toConnector(),
                indices.get(join.floor() + 1).get(next.incomingPiece()),
                next.incomingConnector(), true
            ));
        }

        int minimumX = placements.stream().mapToInt(value -> value.minimum().getX())
            .min().orElse(0);
        int minimumZ = placements.stream().mapToInt(value -> value.minimum().getZ())
            .min().orElse(0);
        int maximumX = placements.stream().mapToInt(value ->
            value.minimum().getX() + value.size().getX()).max().orElse(0);
        int maximumZ = placements.stream().mapToInt(value ->
            value.minimum().getZ() + value.size().getZ()).max().orElse(0);
        BlockPos normalization = new BlockPos(
            minimumX < 0 ? -minimumX
                : maximumX > settings.bounds().getX()
                    ? settings.bounds().getX() - maximumX : 0,
            0,
            minimumZ < 0 ? -minimumZ
                : maximumZ > settings.bounds().getZ()
                    ? settings.bounds().getZ() - maximumZ : 0
        );
        if (!normalization.equals(BlockPos.ZERO)) {
            placements = placements.stream().map(value -> translate(value, normalization))
                .toList();
        }
        for (DungeonPiecePlan.Placement placement : placements) {
            if (!inside(placement, settings.bounds())) {
                throw new IllegalStateException(
                    "Connected dungeon floors exceed dungeon bounds at "
                        + placement.minimum() + " + " + placement.size()
                        + " within " + settings.bounds()
                );
            }
        }
        for (int first = 0; first < placements.size(); first++) {
            for (int second = first + 1; second < placements.size(); second++) {
                if (overlaps(placements.get(first), placements.get(second))) {
                    throw new IllegalStateException(
                        "Connected dungeon floors overlap after stair alignment: "
                            + first + "/" + placements.get(first).pieceId()
                            + " at " + placements.get(first).minimum()
                            + " and " + second + "/"
                            + placements.get(second).pieceId() + " at "
                            + placements.get(second).minimum()
                    );
                }
            }
        }
        Map<String, Long> usage = placements.stream().collect(
            java.util.stream.Collectors.groupingBy(
                DungeonPiecePlan.Placement::pieceId,
                java.util.stream.Collectors.counting()
            )
        );
        List<String> usageViolations = pieces.stream().filter(piece -> {
            long count = usage.getOrDefault(piece.id(), 0L);
            return count < piece.minimumPerPlan() || count > piece.maximumPerPlan();
        }).map(piece -> piece.id() + "=" + usage.getOrDefault(piece.id(), 0L)
            + "/" + piece.minimumPerPlan() + ".." + piece.maximumPerPlan())
            .toList();
        if (!usageViolations.isEmpty()) {
            throw new IllegalStateException(
                "Connected dungeon floors violate piece usage limits: "
                    + String.join(", ", usageViolations)
            );
        }
        return new DungeonPiecePlan(
            seed, settings.bounds(), List.copyOf(placements), List.copyOf(links)
        );
    }

    private static DungeonPiecePlan.Placement toPlacement(
        int index, Placed placed, FloorTransform floorTransform
    ) {
        Rotation rotation = combine(
            floorTransform.rotation(), placed.rotation()
        );
        BlockPos origin = transform(
            placed.origin(), floorTransform.rotation()
        ).offset(floorTransform.translation());
        Placed transformed = placed(
            placed.definition(), origin, rotation, placed.critical()
        );
        BlockPos size = transformed.box().maximumExclusive().subtract(
            transformed.box().minimum()
        );
        return new DungeonPiecePlan.Placement(
            index, placed.definition().id(), placed.definition().role(),
            origin, rotation, transformed.box().minimum(), size, placed.critical()
        );
    }

    private static BlockPos transformedConnectorPosition(
        Placed placed,
        DungeonPieceDefinition.Connector connector,
        FloorTransform floorTransform
    ) {
        BlockPos local = placed.origin().offset(
            transform(connector.position(), placed.rotation())
        );
        return transform(local, floorTransform.rotation())
            .offset(floorTransform.translation());
    }

    private static Rotation combine(Rotation outer, Rotation inner) {
        return rotationByTurns(rotationTurns(outer) + rotationTurns(inner));
    }

    private static int rotationTurns(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    private static Rotation rotationByTurns(int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    private static DungeonPiecePlan.Placement translate(
        DungeonPiecePlan.Placement placement, BlockPos shift
    ) {
        return new DungeonPiecePlan.Placement(
            placement.index(), placement.pieceId(), placement.role(),
            placement.templateOrigin().offset(shift), placement.rotation(),
            placement.minimum().offset(shift), placement.size(),
            placement.criticalPath()
        );
    }

    private static boolean inside(
        DungeonPiecePlan.Placement placement, BlockPos bounds
    ) {
        BlockPos maximum = placement.minimum().offset(placement.size());
        return placement.minimum().getX() >= 0
            && placement.minimum().getY() >= 0
            && placement.minimum().getZ() >= 0
            && maximum.getX() <= bounds.getX()
            && maximum.getY() <= bounds.getY()
            && maximum.getZ() <= bounds.getZ();
    }

    private static boolean overlaps(
        DungeonPiecePlan.Placement first,
        DungeonPiecePlan.Placement second
    ) {
        BlockPos firstMaximum = first.minimum().offset(first.size());
        BlockPos secondMaximum = second.minimum().offset(second.size());
        return first.minimum().getX() < secondMaximum.getX()
            && firstMaximum.getX() > second.minimum().getX()
            && first.minimum().getY() < secondMaximum.getY()
            && firstMaximum.getY() > second.minimum().getY()
            && first.minimum().getZ() < secondMaximum.getZ()
            && firstMaximum.getZ() > second.minimum().getZ();
    }

    private static DungeonPieceDefinition.Connector connector(
        DungeonPieceDefinition piece, String id
    ) {
        return piece.connectors().stream()
            .filter(value -> value.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Dungeon piece connector is missing: " + piece.id() + "/" + id
            ));
    }

    private static BlockPos connectorPosition(
        Placed placed,
        DungeonPieceDefinition.Connector connector,
        BlockPos shift
    ) {
        return placed.origin().offset(shift).offset(
            transform(connector.position(), placed.rotation())
        );
    }

    private static State startState(
        List<DungeonPieceDefinition> pieces, Settings settings, Random random
    ) {
        BlockPos bounds = settings.bounds();
        List<DungeonPieceDefinition> starts = weightedOrder(
            pieces.stream().filter(piece -> piece.role().equals("start")
                && piece.maximumPerPlan() >= 1 && piece.allowsPlacement(true)).toList(), random
        );
        for (DungeonPieceDefinition piece : starts) {
            for (Rotation rotation : rotationOrder(piece, random)) {
                LocalBounds local = localBounds(piece.size(), rotation);
                int minimumY = switch (settings.verticalDirection()) {
                    case "ascending" -> 0;
                    case "descending" -> bounds.getY() - local.size().getY();
                    default -> (bounds.getY() - local.size().getY()) / 2;
                };
                BlockPos minimum = new BlockPos(
                    (bounds.getX() - local.size().getX()) / 2,
                    minimumY,
                    (bounds.getZ() - local.size().getZ()) / 2
                );
                BlockPos origin = minimum.subtract(local.minimum());
                Placed placed = placed(piece, origin, rotation, true);
                if (!inside(placed.box(), bounds)) continue;
                State state = new State();
                state.placements.add(placed);
                return state;
            }
        }
        return null;
    }

    private static boolean extendCritical(
        State state,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        Random random,
        int targetRooms,
        int targetBranches,
        int depth,
        SearchBudget budget
    ) {
        if (!budget.tryVisit()) return false;
        if (depth >= targetRooms) {
            if (!(verticalProfileSatisfied(state, settings)
                && stackedFootprintSatisfied(state, settings)
                && requiredChambersSatisfied(state, settings))) {
                return false;
            }
            State beforeBranches = state.copy();
            if (attachBranches(
                state, pieces, settings, random, targetBranches, budget
            )) return true;
            state.restore(beforeBranches);
            return false;
        }
        int remainingPlacements = targetRooms - depth;
        int floorChanges = state.criticalFloorChanges();
        int floorDepth = state.criticalFloorDepth();
        String layoutMode = settings.layoutMode();
        if (floorChanges > settings.floorChangesMax()
            || floorChanges + remainingPlacements < settings.floorChangesMin()
            || state.branchHostCount() + remainingPlacements < targetBranches) {
            return false;
        }
        String requiredRole = depth == targetRooms - 2 ? "boss"
            : depth == targetRooms - 1 ? "exit" : null;
        if (requiredRole != null && settings.floorChangesMin() > 0
            && floorDepth < minimumCriticalDepth(layoutMode)) return false;
        Set<String> configuredRoles = criticalRoles(layoutMode, floorDepth);
        Set<String> flexibleRoles = new HashSet<>(configuredRoles);
        if (settings.requiredChamberPieces().isEmpty()
            && configuredRoles.contains("room")) {
            flexibleRoles.add("corridor"); flexibleRoles.add("junction");
        }
        boolean hasSelectedChambers = pieces.stream()
            .anyMatch(piece -> piece.role().equals("room"));
        boolean requiresHub = requiredRole == null
            && layoutMode.equals("hub_and_spokes")
            && floorDepth == 2;
        boolean requiresRouteRoom = requiredRole == null
            && hasSelectedChambers && layoutMode.equals("room_network")
            && roomNetworkRoomDepth(floorDepth);
        int branchCapacity = state.branchHostCount();
        boolean needsBranchConnector = requiredRole == null && !requiresHub
            && !layoutMode.equals("hub_and_spokes")
            && branchCapacity < targetBranches;
        int neededFloorChanges = Math.max(
            0, settings.floorChangesMin() - state.criticalFloorChanges()
        );
        int remainingVerticalSlots = 0;
        for (int candidateDepth = floorDepth;
            candidateDepth < floorDepth + targetRooms - depth - 2;
            candidateDepth++) {
            if (criticalRoles(layoutMode, candidateDepth)
                .contains("corridor")) {
                remainingVerticalSlots++;
            }
        }
        int remainingAfterCandidate = targetRooms - depth - 1;
        int minimumFutureFloorPlacements = Math.max(0, neededFloorChanges - 1);
        for (int future = 1; future <= neededFloorChanges; future++) {
            minimumFutureFloorPlacements += minimumCriticalDepth(
                settings.layoutMode()
            );
        }
        boolean floorReadyForTransition = floorDepth
            >= minimumCriticalDepth(layoutMode)
            && remainingAfterCandidate >= minimumFutureFloorPlacements;
        boolean canScheduleVerticalTransition = requiredRole == null
            && neededFloorChanges > 0
            && floorReadyForTransition;
        boolean mustAddVerticalTransition = canScheduleVerticalTransition
            && neededFloorChanges >= remainingVerticalSlots;
        boolean canAddVerticalTransition = requiredRole == null && pieces.stream()
            .anyMatch(piece -> flexibleRoles.contains(piece.role())
                && isVerticalTransition(piece)
                && verticalTransitionMatchesDirection(
                    piece, settings.verticalDirection()
                )
                && floorReadyForTransition
                && piece.allowsPlacement(true)
                && canUse(state, piece));
        boolean canAddBranchConnector = requiredRole == null && pieces.stream()
            .anyMatch(piece -> flexibleRoles.contains(piece.role())
                && piece.connectors().size() >= 3
                && piece.allowsPlacement(true)
                && canUse(state, piece));
        List<DungeonPieceDefinition> candidates = prioritizeRequiredChambers(
            state, settings, weightedOrder(
            pieces.stream().filter(piece -> requiredRole == null
                ? flexibleRoles.contains(piece.role())
                : piece.role().equals(requiredRole))
                .filter(DungeonPiecePlanner::hasConsistentSpatialKind)
                .filter(piece -> requiredRole != null
                    ? !isVerticalTransition(piece)
                    : isRequiredChamber(state, settings, piece)
                    ? true
                    : isVerticalTransition(piece)
                    ? canScheduleVerticalTransition && canAddVerticalTransition
                        && verticalTransitionMatchesDirection(
                            piece, settings.verticalDirection()
                        )
                    : mustAddVerticalTransition ? false
                    : requiresHub
                        ? piece.spatialKind().equals(
                            hasSelectedChambers ? "chamber" : "passage"
                          ) && piece.connectors().size() >= 3
                        : requiresRouteRoom
                            ? piece.spatialKind().equals("chamber")
                                && piece.connectors().size() >= 2
                        : needsBranchConnector && canAddBranchConnector
                            ? piece.spatialKind().equals("passage")
                                && piece.connectors().size() == 3
                            : piece.connectors().size() < 3
                )
                .filter(piece -> floorChanges < settings.floorChangesMax()
                    || !isVerticalTransition(piece))
                .filter(piece -> piece.allowsPlacement(true))
                .filter(piece -> canUse(state, piece)).toList(),
            random
        ));
        if (requiresRouteRoom && state.placements.stream().noneMatch(placed ->
            placed.definition().role().equals("room"))) {
            candidates = prioritizeLargestChamber(
                candidates, settings.requiredChamberPieces()
            );
        }
        Placed current = state.placements.getLast();
        List<Attachment> attachments = new ArrayList<>();
        for (DungeonPieceDefinition.Connector from : connectorOrder(current, state, random)) {
            for (DungeonPieceDefinition piece : candidates) {
                for (Rotation rotation : rotationOrder(piece, random)) {
                    for (DungeonPieceDefinition.Connector to
                        : shuffled(piece.connectors(), random)) {
                        Attachment attachment = attachment(current, from, piece, to, rotation, true);
                        if (attachment == null
                            || !verticalDirectionAllows(
                                current, attachment.placed(), settings.verticalDirection()
                            )
                            || !inside(attachment.placed().box(), settings.bounds())
                            || overlapsState(attachment.placed().box(), state)
                            || !preservesBranchSites(
                                state, attachment, pieces, settings,
                                targetBranches, depth + 1 >= targetRooms
                            )) {
                            continue;
                        }
                        attachments.add(attachment);
                    }
                }
            }
        }
        attachments.sort(Comparator.comparingInt(attachment ->
            compactnessScore(state, attachment.placed())
        ));
        int explorationLimit = switch (layoutMode) {
            case "room_network" -> 24;
            case "hub_and_spokes" -> 16;
            default -> 8;
        };
        int explored = 0;
        for (Attachment attachment : attachments) {
            if (explored++ >= explorationLimit) break;
            state.add(attachment);
            if (extendCritical(
                state, pieces, settings, random, targetRooms,
                targetBranches, depth + 1, budget
            )) return true;
            state.removeLast(attachment);
        }
        return false;
    }

    private static boolean preservesBranchSites(
        State state,
        Attachment criticalAttachment,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        int requiredBranches,
        boolean criticalComplete
    ) {
        if (!settings.layoutMode().equals("room_network")) return true;
        if (requiredBranches == 0) return true;
        State projected = state.copy();
        projected.add(criticalAttachment);
        List<DungeonPieceDefinition> terminals = pieces.stream()
            .filter(piece -> piece.connectors().size() == 1)
            .filter(piece -> !Set.of("start", "boss", "exit").contains(piece.role()))
            .filter(piece -> piece.allowsPlacement(false))
            .filter(piece -> canUse(projected, piece))
            .toList();
        int available = 0;
        int target = requiredBranches + (criticalComplete ? 0 : 1);
        for (int placementIndex = 0;
            placementIndex < projected.placements.size(); placementIndex++) {
            Placed host = projected.placements.get(placementIndex);
            for (DungeonPieceDefinition.Connector from : host.definition().connectors()) {
                if (projected.usedConnectors.contains(new ConnectorKey(host, from.id()))) {
                    continue;
                }
                boolean fits = false;
                for (DungeonPieceDefinition terminal : terminals) {
                    DungeonPieceDefinition.Connector to = terminal.connectors().getFirst();
                    for (Rotation rotation : ROTATIONS) {
                        if (!terminal.allowRotation() && rotation != Rotation.NONE) continue;
                        Attachment branch = attachment(
                            host, from, terminal, to, rotation, false
                        );
                        if (branch != null
                            && inside(branch.placed().box(), settings.bounds())
                            && !overlapsState(branch.placed().box(), projected)) {
                            fits = true;
                            break;
                        }
                    }
                    if (fits) break;
                }
                if (fits && ++available >= target) return true;
            }
        }
        return false;
    }

    private static int compactnessScore(State state, Placed candidate) {
        boolean stacked = state.placements.stream()
            .filter(Placed::critical)
            .filter(placed -> placed.box().minimum().getY()
                != candidate.box().minimum().getY())
            .anyMatch(placed -> overlapsHorizontally(
                placed.box(), candidate.box()
            ));
        int minX = candidate.box().minimum().getX();
        int minZ = candidate.box().minimum().getZ();
        int maxX = candidate.box().maximumExclusive().getX();
        int maxZ = candidate.box().maximumExclusive().getZ();
        for (Placed placed : state.placements) {
            minX = Math.min(minX, placed.box().minimum().getX());
            minZ = Math.min(minZ, placed.box().minimum().getZ());
            maxX = Math.max(maxX, placed.box().maximumExclusive().getX());
            maxZ = Math.max(maxZ, placed.box().maximumExclusive().getZ());
        }
        int width = maxX - minX;
        int depth = maxZ - minZ;
        return (stacked ? -1_000_000 : 0) + width * width + depth * depth;
    }

    private static boolean attachBranches(
        State state,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        Random random,
        int targetBranches,
        SearchBudget budget
    ) {
        int completed = 0;
        List<Integer> hosts = new ArrayList<>();
        for (int index = 0; index < state.placements.size() - 2; index++) {
            Placed placed = state.placements.get(index);
            if (isVerticalTransition(placed.definition())) continue;
            String hostMode = settings.layoutMode();
            if (!hostMode.equals("hub_and_spokes")
                || (pieces.stream().noneMatch(piece -> piece.role().equals("room"))
                    ? placed.definition().connectors().size() >= 3
                    : placed.definition().role().equals("room")
                        && placed.definition().connectors().size() >= 4)) {
                hosts.add(index);
            }
        }
        hosts = shuffled(hosts, random);
        for (int host : hosts) {
            if (completed >= targetBranches) break;
            int attempts = settings.layoutMode().equals("hub_and_spokes")
                ? targetBranches - completed : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                int depth = randomRange(
                    random, settings.branchDepthMin(), settings.branchDepthMax()
                );
                State snapshot = state.copy();
                if (extendBranch(
                    state, pieces, settings, random, host, depth, 0, budget
                )) {
                    completed++;
                } else {
                    state.restore(snapshot);
                    break;
                }
            }
        }
        return completed >= targetBranches;
    }

    private static boolean extendBranch(
        State state,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        Random random,
        int currentIndex,
        int remaining,
        int branchDepth,
        SearchBudget budget
    ) {
        if (!budget.tryVisit()) return false;
        if (remaining == 0) return true;
        Set<String> roles = branchRoles(
            settings.layoutMode(), remaining, branchDepth
        );
        List<DungeonPieceDefinition> candidates = prioritizeMinimumUsage(
            state, weightedOrder(
            pieces.stream().filter(piece -> roles.contains(piece.role()))
                .filter(DungeonPiecePlanner::hasConsistentSpatialKind)
                .filter(piece -> remaining == 1
                    ? piece.connectors().size() == 1
                    : piece.connectors().size() == 2)
                .filter(piece -> piece.allowsPlacement(false))
                .filter(piece -> canUse(state, piece)).toList(), random
        ));
        Placed current = state.placements.get(currentIndex);
        for (DungeonPieceDefinition.Connector from : connectorOrder(current, state, random)) {
            for (DungeonPieceDefinition piece : candidates) {
                for (Rotation rotation : rotationOrder(piece, random)) {
                    for (DungeonPieceDefinition.Connector to
                        : shuffled(piece.connectors(), random)) {
                        Attachment attachment = attachment(
                            current, from, piece, to, rotation, false
                        );
                        if (attachment == null
                            || !inside(attachment.placed().box(), settings.bounds())
                            || overlapsState(attachment.placed().box(), state)) {
                            continue;
                        }
                        state.add(attachment);
                        int nextIndex = state.placements.size() - 1;
                        if (extendBranch(
                            state, pieces, settings, random, nextIndex,
                            remaining - 1, branchDepth + 1, budget
                        )) return true;
                        state.removeLast(attachment);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Every connector describes a real opening in the NBT. Complete all openings
     * with a one-connector terminal piece so a partially used room can never expose
     * a doorway directly to empty instance space.
     */
    private static boolean completeOpenConnectors(
        State state,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        Random random,
        SearchBudget budget
    ) {
        if (!budget.tryVisit()) return false;
        OpenConnector open = state.firstOpenConnector();
        if (open == null) return true;
        List<DungeonPieceDefinition> terminalPool = pieces.stream()
                .filter(piece -> piece.connectors().size() == 1)
                .filter(piece -> piece.spatialKind().equals("terminal"))
                .filter(piece -> !Set.of("start", "boss", "exit").contains(piece.role()))
                .filter(piece -> piece.allowsPlacement(false))
                .filter(piece -> canUse(state, piece))
                .toList();
        List<DungeonPieceDefinition> deadEnds = terminalPool.stream()
            .filter(piece -> piece.role().equals("dead_end")).toList();
        List<DungeonPieceDefinition> terminals = weightedOrder(
            deadEnds.isEmpty() ? terminalPool : deadEnds, random
        );
        for (DungeonPieceDefinition piece : terminals) {
            DungeonPieceDefinition.Connector terminal = piece.connectors().getFirst();
            for (Rotation rotation : rotationOrder(piece, random)) {
                Attachment attachment = attachment(
                    open.piece(), open.connector(), piece, terminal, rotation, false
                );
                if (attachment == null
                    || !inside(attachment.placed().box(), settings.bounds())
                    || overlapsState(attachment.placed().box(), state)) {
                    continue;
                }
                state.add(attachment);
                if (completeOpenConnectors(
                    state, pieces, settings, random, budget
                )) return true;
                state.removeLast(attachment);
            }
        }
        return false;
    }

    private static Attachment attachment(
        Placed fromPiece,
        DungeonPieceDefinition.Connector from,
        DungeonPieceDefinition nextPiece,
        DungeonPieceDefinition.Connector to,
        Rotation rotation,
        boolean critical
    ) {
        Direction fromFacing = fromPiece.rotation().rotate(from.facing());
        Direction toFacing = rotation.rotate(to.facing());
        if (fromFacing.getOpposite() != toFacing || !compatible(from, to)
            || !fromPiece.definition().allowsAdjacentTo(nextPiece)) return null;
        BlockPos fromPosition = fromPiece.origin().offset(transform(from.position(), fromPiece.rotation()));
        BlockPos target = fromPosition.relative(fromFacing);
        BlockPos nextOrigin = target.subtract(transform(to.position(), rotation));
        Placed placed = placed(nextPiece, nextOrigin, rotation, critical);
        return new Attachment(fromPiece, from, placed, to, critical);
    }

    private static boolean compatible(
        DungeonPieceDefinition.Connector first,
        DungeonPieceDefinition.Connector second
    ) {
        if (!first.socket().equals(second.socket())) return false;
        if (first.tags().isEmpty() || second.tags().isEmpty()) return true;
        return first.tags().stream().anyMatch(second.tags()::contains);
    }

    private static Placed placed(
        DungeonPieceDefinition definition,
        BlockPos origin,
        Rotation rotation,
        boolean critical
    ) {
        LocalBounds local = localBounds(definition.size(), rotation);
        BlockPos minimum = origin.offset(local.minimum());
        return new Placed(
            definition, origin, rotation,
            new Box(minimum, minimum.offset(local.size())), critical
        );
    }

    private static LocalBounds localBounds(BlockPos size, Rotation rotation) {
        List<BlockPos> corners = List.of(
            new BlockPos(0, 0, 0),
            new BlockPos(size.getX() - 1, 0, 0),
            new BlockPos(0, size.getY() - 1, 0),
            new BlockPos(0, 0, size.getZ() - 1),
            new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1)
        ).stream().map(position -> transform(position, rotation)).toList();
        int minX = corners.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minY = corners.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minZ = corners.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxX = corners.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxY = corners.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maxZ = corners.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return new LocalBounds(
            new BlockPos(minX, minY, minZ),
            new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1)
        );
    }

    private static BlockPos transform(BlockPos position, Rotation rotation) {
        return StructureTemplate.transform(position, Mirror.NONE, rotation, BlockPos.ZERO);
    }

    private static boolean inside(Box box, BlockPos bounds) {
        return box.minimum().getX() >= 0 && box.minimum().getY() >= 0
            && box.minimum().getZ() >= 0
            && box.maximumExclusive().getX() <= bounds.getX()
            && box.maximumExclusive().getY() <= bounds.getY()
            && box.maximumExclusive().getZ() <= bounds.getZ();
    }

    private static boolean overlapsAny(Box box, List<Placed> placements) {
        return placements.stream().anyMatch(placed -> box.overlaps(placed.box()));
    }

    private static boolean overlapsState(Box box, State state) {
        return overlapsAny(box, state.placements)
            || state.reservedBoxes.stream().anyMatch(box::overlaps);
    }

    private static List<DungeonPieceDefinition.Connector> connectorOrder(
        Placed placed, State state, Random random
    ) {
        return shuffled(placed.definition().connectors().stream()
            .filter(connector -> !state.usedConnectors.contains(
                new ConnectorKey(placed, connector.id())
            )).toList(), random);
    }

    private static List<Rotation> rotationOrder(
        DungeonPieceDefinition piece, Random random
    ) {
        return piece.allowRotation() ? shuffled(ROTATIONS, random) : List.of(Rotation.NONE);
    }

    private static <T> List<T> shuffled(List<T> values, Random random) {
        List<T> result = new ArrayList<>(values);
        java.util.Collections.shuffle(result, random);
        return result;
    }

    private static List<DungeonPieceDefinition> weightedOrder(
        List<DungeonPieceDefinition> pieces, Random random
    ) {
        record Weighted(DungeonPieceDefinition piece, double key) {}
        return pieces.stream().map(piece -> new Weighted(
            piece, -Math.log(Math.max(Double.MIN_VALUE, random.nextDouble())) / piece.weight()
        )).sorted(Comparator.comparingDouble(Weighted::key))
            .map(Weighted::piece).toList();
    }

    private static int randomRange(Random random, int minimum, int maximum) {
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static boolean canUse(State state, DungeonPieceDefinition piece) {
        return state.placements.stream().filter(placed ->
            placed.definition().id().equals(piece.id())
        ).count() < piece.maximumPerPlan();
    }

    private static boolean usageSatisfied(
        State state, List<DungeonPieceDefinition> pieces
    ) {
        Map<String, Long> counts = state.placements.stream().collect(
            java.util.stream.Collectors.groupingBy(
                placed -> placed.definition().id(),
                java.util.stream.Collectors.counting()
            )
        );
        return pieces.stream().allMatch(piece -> {
            long count = counts.getOrDefault(piece.id(), 0L);
            return count >= piece.minimumPerPlan()
                && count <= piece.maximumPerPlan();
        });
    }

    private static void validateRequiredChambers(
        Map<String, DungeonPieceDefinition> pieces, List<String> required
    ) {
        for (String id : required) {
            DungeonPieceDefinition piece = pieces.get(id);
            if (piece == null) {
                throw new IllegalStateException(
                    "Dungeon selected chamber is outside its piece pool: " + id
                );
            }
            if (!piece.spatialKind().equals("chamber")
                || !piece.role().equals("room")
                || piece.maximumPerPlan() < 1) {
                throw new IllegalStateException(
                    "Dungeon selected piece is not a placeable chamber: " + id
                );
            }
        }
    }

    private static boolean requiredChambersSatisfied(State state, Settings settings) {
        Set<String> placed = state.placements.stream()
            .map(value -> value.definition().id())
            .collect(java.util.stream.Collectors.toSet());
        return placed.containsAll(settings.requiredChamberPieces());
    }

    private static boolean isRequiredChamber(
        State state, Settings settings, DungeonPieceDefinition piece
    ) {
        return settings.requiredChamberPieces().contains(piece.id())
            && state.placements.stream().noneMatch(
                placed -> placed.definition().id().equals(piece.id())
            );
    }

    private static List<DungeonPieceDefinition> prioritizeRequiredChambers(
        State state, Settings settings, List<DungeonPieceDefinition> candidates
    ) {
        Set<String> placed = state.placements.stream()
            .map(value -> value.definition().id())
            .collect(java.util.stream.Collectors.toSet());
        List<DungeonPieceDefinition> result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingInt(piece ->
            settings.requiredChamberPieces().contains(piece.id())
                && !placed.contains(piece.id()) ? 0 : 1
        ));
        return result;
    }

    private static Set<String> criticalRoles(String layoutMode, int depth) {
        return switch (layoutMode) {
            case "legacy_maze", "maze" -> Set.of("corridor", "junction");
            // Room anchors recur after two, then three, passage pieces.
            case "room_network" -> roomNetworkRoomDepth(depth)
                ? Set.of("room") : Set.of("corridor", "junction");
            case "corridor_spine", "legacy_rooms_and_corridors", "rooms_and_corridors" -> depth % 3 == 0
                ? Set.of("room", "junction", "support")
                : depth % 3 == 1 ? Set.of("corridor", "junction") : Set.of("corridor");
            case "hub_and_spokes" -> depth == 2
                ? Set.of("room") : Set.of("corridor", "junction");
            case "critical_path_branches" -> depth % 3 == 2
                ? Set.of("room") : Set.of("corridor", "junction");
            default -> Set.of("room", "corridor", "junction", "support");
        };
    }

    private static boolean roomNetworkRoomDepth(int depth) {
        int cycle = Math.floorMod(depth, 7);
        return cycle == 0 || cycle == 3;
    }

    private static int minimumCriticalDepth(String layoutMode) {
        return layoutMode.equals("hub_and_spokes") ? 3 : 2;
    }

    private static Set<String> branchRoles(
        String layoutMode, int remaining, int depth
    ) {
        if (remaining == 1) return Set.of("dead_end", "treasure", "support");
        return switch (layoutMode) {
            case "legacy_maze", "maze", "hub_and_spokes" -> Set.of("corridor", "junction");
            case "room_network" -> roomNetworkRoomDepth(depth)
                ? Set.of("room") : Set.of("corridor", "junction");
            case "legacy_rooms_and_corridors", "rooms_and_corridors" -> depth % 3 == 2
                ? Set.of("room", "junction") : Set.of("corridor", "junction");
            default -> Set.of("room", "corridor", "junction");
        };
    }

    private static long mixSeed(long seed, int attempt) {
        long value = seed + 0x9E3779B97F4A7C15L * attempt;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static boolean verticalDirectionAllows(
        Placed current, Placed next, String direction
    ) {
        int delta = next.origin().getY() - current.origin().getY();
        return switch (direction) {
            case "flat" -> delta == 0;
            case "ascending" -> delta >= 0;
            case "descending" -> delta <= 0;
            default -> true;
        };
    }

    private static boolean verticalProfileSatisfied(State state, Settings settings) {
        int changes = 0;
        Placed previous = null;
        for (Placed placed : state.placements) {
            if (!placed.critical()) continue;
            if (previous != null && previous.origin().getY() != placed.origin().getY()) {
                if (!isVerticalTransition(previous.definition())
                    && !isVerticalTransition(placed.definition())) {
                    return false;
                }
                if (settings.verticalMode().equals("discrete_floors")
                    && Math.abs(placed.origin().getY() - previous.origin().getY())
                        != settings.floorHeight()) {
                    return false;
                }
                changes++;
            }
            previous = placed;
        }
        return changes >= settings.floorChangesMin()
            && changes <= settings.floorChangesMax();
    }

    private static boolean stackedFootprintSatisfied(State state, Settings settings) {
        if (settings.floorChangesMin() == 0
            || settings.verticalDirection().equals("flat")) return true;
        List<Placed> rooms = state.placements.stream()
            .filter(Placed::critical)
            .filter(placed -> !isVerticalTransition(placed.definition()))
            .toList();
        for (int first = 0; first < rooms.size(); first++) {
            Placed a = rooms.get(first);
            for (int second = first + 1; second < rooms.size(); second++) {
                Placed b = rooms.get(second);
                if (a.box().minimum().getY() == b.box().minimum().getY()) continue;
                if (overlapsHorizontally(a.box(), b.box())) return true;
            }
        }
        return false;
    }

    private static boolean isVerticalTransition(DungeonPieceDefinition piece) {
        return piece.connectors().stream().map(connector -> connector.position().getY())
            .distinct().count() > 1;
    }

    private static boolean verticalTransitionMatchesDirection(
        DungeonPieceDefinition piece, String direction
    ) {
        if (!isVerticalTransition(piece) || direction.equals("mixed")) return true;
        String requiredShape = direction.equals("ascending")
            ? "/stairs_up" : direction.equals("descending")
                ? "/stairs_down" : "";
        if (requiredShape.isEmpty()) return false;
        return piece.id().endsWith(requiredShape)
            || piece.tags().stream().anyMatch(tag -> tag.endsWith(requiredShape));
    }

    /**
     * Spatial kind is a construction contract, not a preview label. A malformed
     * piece must never be selected merely because its legacy role happens to fit.
     */
    private static boolean hasConsistentSpatialKind(DungeonPieceDefinition piece) {
        if (isVerticalTransition(piece)) {
            return piece.spatialKind().equals("vertical_transition");
        }
        return switch (piece.role()) {
            case "corridor", "junction" -> piece.spatialKind().equals("passage");
            case "dead_end", "exit" -> piece.spatialKind().equals("terminal");
            case "start", "boss", "room", "support", "treasure" ->
                piece.spatialKind().equals("chamber");
            default -> true;
        };
    }

    private static List<DungeonPieceDefinition> selectablePieces(
        List<DungeonPieceDefinition> pieces, Settings settings
    ) {
        Set<String> selected = Set.copyOf(settings.requiredChamberPieces());
        return pieces.stream().filter(piece -> !piece.role().equals("room")
            || selected.contains(piece.id())).toList();
    }

    private static boolean overlapsHorizontally(Box first, Box second) {
        return first.minimum().getX() < second.maximumExclusive().getX()
            && first.maximumExclusive().getX() > second.minimum().getX()
            && first.minimum().getZ() < second.maximumExclusive().getZ()
            && first.maximumExclusive().getZ() > second.minimum().getZ();
    }

    private static void requireRoles(List<DungeonPieceDefinition> pieces) {
        for (String role : List.of("start", "boss", "exit")) {
            if (pieces.stream().noneMatch(piece -> piece.role().equals(role))) {
                throw new IllegalStateException("Dungeon piece pool has no " + role + " piece");
            }
        }
    }

    record Settings(
        BlockPos bounds,
        int criticalPathMin,
        int criticalPathMax,
        int branchCountMin,
        int branchCountMax,
        int branchDepthMin,
        int branchDepthMax,
        double loopChance,
        int maxAttempts,
        String layoutMode,
        String verticalDirection,
        int floorChangesMin,
        int floorChangesMax,
        String verticalMode,
        int floorHeight,
        List<String> requiredChamberPieces
    ) {
        Settings {
            requiredChamberPieces = requiredChamberPieces == null
                ? List.of() : List.copyOf(requiredChamberPieces);
        }

        Settings(
            BlockPos bounds, int criticalPathMin, int criticalPathMax,
            int branchCountMin, int branchCountMax,
            int branchDepthMin, int branchDepthMax,
            double loopChance, int maxAttempts
        ) {
            this(
                bounds, criticalPathMin, criticalPathMax,
                branchCountMin, branchCountMax, branchDepthMin, branchDepthMax,
                loopChance, maxAttempts, "corridor_spine", "mixed", 0, 256,
                "continuous", 8, List.of()
            );
        }

        Settings(
            BlockPos bounds, int criticalPathMin, int criticalPathMax,
            int branchCountMin, int branchCountMax,
            int branchDepthMin, int branchDepthMax,
            double loopChance, int maxAttempts, String layoutMode
        ) {
            this(
                bounds, criticalPathMin, criticalPathMax,
                branchCountMin, branchCountMax, branchDepthMin, branchDepthMax,
                loopChance, maxAttempts, layoutMode, "mixed", 0, 256,
                "continuous", 8, List.of()
            );
        }

        Settings(
            BlockPos bounds, int criticalPathMin, int criticalPathMax,
            int branchCountMin, int branchCountMax,
            int branchDepthMin, int branchDepthMax,
            double loopChance, int maxAttempts, String layoutMode,
            String verticalDirection, int floorChangesMin, int floorChangesMax
        ) {
            this(
                bounds, criticalPathMin, criticalPathMax,
                branchCountMin, branchCountMax, branchDepthMin, branchDepthMax,
                loopChance, maxAttempts, layoutMode, verticalDirection,
                floorChangesMin, floorChangesMax, "continuous", 8,
                List.of()
            );
        }

        Settings(
            BlockPos bounds, int criticalPathMin, int criticalPathMax,
            int branchCountMin, int branchCountMax,
            int branchDepthMin, int branchDepthMax,
            double loopChance, int maxAttempts, String layoutMode,
            String verticalDirection, int floorChangesMin, int floorChangesMax,
            String verticalMode, int floorHeight
        ) {
            this(
                bounds, criticalPathMin, criticalPathMax,
                branchCountMin, branchCountMax, branchDepthMin, branchDepthMax,
                loopChance, maxAttempts, layoutMode, verticalDirection,
                floorChangesMin, floorChangesMax, verticalMode, floorHeight,
                List.of()
            );
        }

        private void validate() {
            if (bounds.getX() < 1 || bounds.getY() < 1 || bounds.getZ() < 1
                || criticalPathMin < 3 || criticalPathMin > criticalPathMax
                || branchCountMin < 0 || branchCountMin > branchCountMax
                || branchDepthMin < 1 || branchDepthMin > branchDepthMax
                || loopChance < 0.0D || loopChance > 1.0D
                || maxAttempts < 1 || maxAttempts > 1000
                || !Set.of("flat", "ascending", "descending", "mixed")
                    .contains(verticalDirection)
                || floorChangesMin < 0 || floorChangesMin > floorChangesMax
                || floorChangesMax > 256
                || !Set.of("flat", "continuous", "discrete_floors", "authored")
                    .contains(verticalMode)
                || floorHeight < 4 || floorHeight > 64
                || requiredChamberPieces == null
                || requiredChamberPieces.size()
                    != new HashSet<>(requiredChamberPieces).size()
                || !Set.of(
                    "corridor_spine", "hub_and_spokes", "room_network",
                    "legacy_maze", "legacy_rooms_and_corridors",
                    "critical_path_branches", "maze",
                    "rooms_and_corridors"
                ).contains(layoutMode)) {
                throw new IllegalArgumentException("Invalid dungeon piece planner settings");
            }
        }
    }

    private static final class State {
        private final List<Placed> placements = new ArrayList<>();
        private final List<PlanLink> links = new ArrayList<>();
        private final Set<ConnectorKey> usedConnectors = new HashSet<>();
        private final List<Box> reservedBoxes = new ArrayList<>();

        private void add(Attachment attachment) {
            int fromIndex = placements.indexOf(attachment.fromPiece());
            int toIndex = placements.size();
            placements.add(attachment.placed());
            links.add(new PlanLink(
                fromIndex, attachment.from().id(), toIndex, attachment.to().id(),
                attachment.critical()
            ));
            usedConnectors.add(new ConnectorKey(attachment.fromPiece(), attachment.from().id()));
            usedConnectors.add(new ConnectorKey(attachment.placed(), attachment.to().id()));
        }

        private void removeLast(Attachment attachment) {
            placements.removeLast();
            links.removeLast();
            usedConnectors.remove(new ConnectorKey(
                attachment.fromPiece(), attachment.from().id()
            ));
            usedConnectors.remove(new ConnectorKey(
                attachment.placed(), attachment.to().id()
            ));
        }

        private State copy() {
            State copy = new State();
            copy.placements.addAll(placements);
            copy.links.addAll(links);
            copy.usedConnectors.addAll(usedConnectors);
            copy.reservedBoxes.addAll(reservedBoxes);
            return copy;
        }

        private void restore(State snapshot) {
            placements.clear(); placements.addAll(snapshot.placements);
            links.clear(); links.addAll(snapshot.links);
            usedConnectors.clear(); usedConnectors.addAll(snapshot.usedConnectors);
            reservedBoxes.clear(); reservedBoxes.addAll(snapshot.reservedBoxes);
        }

        private void absorbAdditionalLinks(List<DungeonPiecePlan.Link> additional) {
            for (int index = links.size(); index < additional.size(); index++) {
                DungeonPiecePlan.Link link = additional.get(index);
                Placed from = placements.get(link.fromIndex());
                Placed to = placements.get(link.toIndex());
                links.add(new PlanLink(
                    link.fromIndex(), link.fromConnector(), link.toIndex(),
                    link.toConnector(), link.criticalPath()
                ));
                usedConnectors.add(new ConnectorKey(from, link.fromConnector()));
                usedConnectors.add(new ConnectorKey(to, link.toConnector()));
            }
        }

        private OpenConnector firstOpenConnector() {
            for (Placed placed : placements) {
                for (DungeonPieceDefinition.Connector connector
                    : placed.definition().connectors()) {
                    if (!usedConnectors.contains(new ConnectorKey(placed, connector.id()))) {
                        return new OpenConnector(placed, connector);
                    }
                }
            }
            return null;
        }

        private int branchHostCount() {
            int hosts = 0;
            for (int index = 0; index < placements.size(); index++) {
                Placed placed = placements.get(index);
                int open = 0;
                for (DungeonPieceDefinition.Connector connector
                    : placed.definition().connectors()) {
                    if (!usedConnectors.contains(new ConnectorKey(placed, connector.id()))) {
                        open++;
                    }
                }
                int reservedForCriticalPath = index == placements.size() - 1 ? 1 : 0;
                if (open > reservedForCriticalPath) hosts++;
            }
            return hosts;
        }

        private int criticalFloorChanges() {
            return (int) placements.stream()
                .filter(Placed::critical)
                .filter(placed -> isVerticalTransition(placed.definition()))
                .count();
        }

        private int criticalFloorDepth() {
            int depth = 0;
            for (int index = placements.size() - 1; index >= 0; index--) {
                Placed placed = placements.get(index);
                if (!placed.critical()) continue;
                if (isVerticalTransition(placed.definition())) break;
                depth++;
            }
            return depth;
        }

        private DungeonPiecePlan toPlan(long seed, BlockPos bounds) {
            List<DungeonPiecePlan.Placement> planned = new ArrayList<>();
            for (int index = 0; index < placements.size(); index++) {
                Placed placed = placements.get(index);
                BlockPos size = placed.box().maximumExclusive().subtract(
                    placed.box().minimum()
                );
                planned.add(new DungeonPiecePlan.Placement(
                    index, placed.definition().id(), placed.definition().role(),
                    placed.origin(), placed.rotation(), placed.box().minimum(), size,
                    placed.critical()
                ));
            }
            return new DungeonPiecePlan(
                seed, bounds, List.copyOf(planned),
                links.stream().map(link -> new DungeonPiecePlan.Link(
                    link.fromIndex(), link.fromConnector(), link.toIndex(),
                    link.toConnector(), link.critical()
                )).toList()
            );
        }
    }

    private record Placed(
        DungeonPieceDefinition definition,
        BlockPos origin,
        Rotation rotation,
        Box box,
        boolean critical
    ) {}
    private record Attachment(
        Placed fromPiece,
        DungeonPieceDefinition.Connector from,
        Placed placed,
        DungeonPieceDefinition.Connector to,
        boolean critical
    ) {}
    private record PlanLink(
        int fromIndex,
        String fromConnector,
        int toIndex,
        String toConnector,
        boolean critical
    ) {}
    private record ConnectorKey(Placed piece, String connector) {}
    private record OpenConnector(
        Placed piece, DungeonPieceDefinition.Connector connector
    ) {}
    private record FloorAllocation(
        List<Integer> placementCounts,
        List<List<String>> requiredChambers
    ) {}
    private record StartChoice(
        Placed placed,
        DungeonPieceDefinition.Connector reservedIncoming
    ) {}
    private record FloorResult(
        State state,
        Placed incomingPiece,
        Placed outgoingPiece,
        String incomingConnector,
        String outgoingConnector
    ) {}
    private record StairJoin(
        int floor,
        BlockPos origin,
        DungeonPieceDefinition piece,
        Rotation rotation,
        String fromConnector,
        String toConnector
    ) {}
    private record FloorTransform(Rotation rotation, BlockPos translation) {}
    private static final class SearchBudget {
        private final long deadlineNanos;
        private int remainingNodes;

        private SearchBudget(long deadlineNanos, int maximumNodes) {
            this.deadlineNanos = deadlineNanos;
            this.remainingNodes = maximumNodes;
        }

        private boolean tryVisit() {
            return remainingNodes-- > 0 && System.nanoTime() < deadlineNanos;
        }
    }
    private record LocalBounds(BlockPos minimum, BlockPos size) {}
    private record Box(BlockPos minimum, BlockPos maximumExclusive) {
        private boolean overlaps(Box other) {
            return minimum.getX() < other.maximumExclusive.getX()
                && maximumExclusive.getX() > other.minimum.getX()
                && minimum.getY() < other.maximumExclusive.getY()
                && maximumExclusive.getY() > other.minimum.getY()
                && minimum.getZ() < other.maximumExclusive.getZ()
                && maximumExclusive.getZ() > other.minimum.getZ();
        }
    }
}
