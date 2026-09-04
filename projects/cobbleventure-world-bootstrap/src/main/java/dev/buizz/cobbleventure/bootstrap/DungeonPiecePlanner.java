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
        String lastStage = "start";
        for (int attempt = 0; attempt < settings.maxAttempts(); attempt++) {
            if (System.nanoTime() >= deadlineNanos) break;
            SearchBudget budget = new SearchBudget(
                deadlineNanos,
                settings.floorLayoutModes().contains("room_network")
                    ? MAX_SEARCH_NODES_PER_ATTEMPT * 5
                    : MAX_SEARCH_NODES_PER_ATTEMPT
            );
            Random random = new Random(mixSeed(seed, attempt));
            State state = startState(pieces, settings, random);
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
                state, pieces, settings, random, targetRooms, targetBranches, 1,
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
                state, pieces, settings, random, budget
            )) {
                lastStage = "open_connectors";
                continue;
            }
            if (!usageSatisfied(state, pieces)) {
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
                && stackedFootprintSatisfied(state, settings))) {
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
        String layoutMode = settings.layoutModeForFloor(floorChanges);
        if (floorChanges > settings.floorChangesMax()
            || floorChanges + remainingPlacements < settings.floorChangesMin()
            || state.branchHostCount() + remainingPlacements < targetBranches) {
            return false;
        }
        String requiredRole = depth == targetRooms - 2 ? "boss"
            : depth == targetRooms - 1 ? "exit" : null;
        if (requiredRole != null && settings.floorChangesMin() > 0
            && floorDepth < minimumCriticalDepth(layoutMode)) return false;
        Set<String> flexibleRoles = criticalRoles(layoutMode, floorDepth);
        boolean requiresHub = requiredRole == null
            && layoutMode.equals("hub_and_spokes") && floorDepth == 2;
        boolean requiresRouteRoom = requiredRole == null
            && layoutMode.equals("room_network") && floorDepth % 2 == 0;
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
                settings.layoutModeForFloor(floorChanges + future)
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
        List<DungeonPieceDefinition> candidates = weightedOrder(
            pieces.stream().filter(piece -> requiredRole == null
                ? flexibleRoles.contains(piece.role())
                : piece.role().equals(requiredRole))
                .filter(DungeonPiecePlanner::hasConsistentSpatialKind)
                .filter(piece -> requiredRole != null
                    ? !isVerticalTransition(piece)
                    : isVerticalTransition(piece)
                    ? canScheduleVerticalTransition && canAddVerticalTransition
                        && verticalTransitionMatchesDirection(
                            piece, settings.verticalDirection()
                        )
                    : mustAddVerticalTransition ? false
                    : requiresHub
                        ? piece.spatialKind().equals("chamber")
                            && piece.connectors().size() >= 4
                        : requiresRouteRoom
                            ? piece.spatialKind().equals("chamber")
                                && piece.connectors().size() == 2
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
        );
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
                            || overlapsAny(attachment.placed().box(), state.placements)
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
        if (!settings.layoutModeForFloor(state.criticalFloorChanges()).equals("room_network")) return true;
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
                            && !overlapsAny(
                                branch.placed().box(), projected.placements
                            )) {
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
        return (stacked ? -1_000_000 : 0) + (maxX - minX) * (maxZ - minZ);
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
            String hostMode = settings.layoutModeForPlacement(state, placed);
            if (!hostMode.equals("hub_and_spokes")
                || (placed.definition().role().equals("room")
                    && placed.definition().connectors().size() >= 4)) {
                hosts.add(index);
            }
        }
        hosts = shuffled(hosts, random);
        for (int host : hosts) {
            if (completed >= targetBranches) break;
            int attempts = settings.layoutModeForPlacement(
                state, state.placements.get(host)
            ).equals("hub_and_spokes")
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
            settings.layoutModeForPlacement(
                state, state.placements.get(currentIndex)
            ), remaining, branchDepth
        );
        List<DungeonPieceDefinition> candidates = weightedOrder(
            pieces.stream().filter(piece -> roles.contains(piece.role()))
                .filter(DungeonPiecePlanner::hasConsistentSpatialKind)
                .filter(piece -> remaining == 1
                    ? piece.connectors().size() == 1
                    : piece.connectors().size() == 2)
                .filter(piece -> piece.allowsPlacement(false))
                .filter(piece -> canUse(state, piece)).toList(), random
        );
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
                            || overlapsAny(attachment.placed().box(), state.placements)) {
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
                    || overlapsAny(attachment.placed().box(), state.placements)) {
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

    private static Set<String> criticalRoles(String layoutMode, int depth) {
        return switch (layoutMode) {
            case "legacy_maze", "maze" -> Set.of("corridor", "junction");
            case "room_network" -> depth % 2 == 0
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

    private static int minimumCriticalDepth(String layoutMode) {
        return layoutMode.equals("hub_and_spokes") ? 3 : 2;
    }

    private static Set<String> branchRoles(
        String layoutMode, int remaining, int depth
    ) {
        if (remaining == 1) return Set.of("dead_end", "treasure", "support");
        return switch (layoutMode) {
            case "legacy_maze", "maze", "hub_and_spokes" -> Set.of("corridor", "junction");
            case "room_network" -> depth % 2 == 1
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
        List<String> floorLayoutModes
    ) {
        Settings {
            floorLayoutModes = floorLayoutModes == null
                ? List.of() : List.copyOf(floorLayoutModes);
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
                "continuous", 8, repeatedModes("corridor_spine", 257)
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
                "continuous", 8, repeatedModes(layoutMode, 257)
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
                repeatedModes(layoutMode, floorChangesMax + 1)
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
                repeatedModes(layoutMode, floorChangesMax + 1)
            );
        }

        private static List<String> repeatedModes(String mode, int count) {
            return List.copyOf(java.util.Collections.nCopies(count, mode));
        }

        String layoutModeForFloor(int floor) {
            return floorLayoutModes.get(Math.min(
                Math.max(0, floor), floorLayoutModes.size() - 1
            ));
        }

        String layoutModeForPlacement(State state, Placed placement) {
            int startY = state.placements.getFirst().origin().getY();
            int floor = Math.abs(placement.origin().getY() - startY) / floorHeight;
            return layoutModeForFloor(floor);
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
                || floorLayoutModes == null || floorLayoutModes.isEmpty()
                || floorLayoutModes.size() <= floorChangesMax
                || floorLayoutModes.stream().anyMatch(mode -> !Set.of(
                    "corridor_spine", "hub_and_spokes", "room_network",
                    "legacy_maze", "legacy_rooms_and_corridors",
                    "critical_path_branches", "maze", "rooms_and_corridors"
                ).contains(mode))
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
            return copy;
        }

        private void restore(State snapshot) {
            placements.clear(); placements.addAll(snapshot.placements);
            links.clear(); links.addAll(snapshot.links);
            usedConnectors.clear(); usedConnectors.addAll(snapshot.usedConnectors);
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
