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
        settings.validate();
        List<DungeonPieceDefinition> pieces = List.copyOf(definitions);
        requireRoles(pieces);
        for (int attempt = 0; attempt < settings.maxAttempts(); attempt++) {
            Random random = new Random(mixSeed(seed, attempt));
            State state = startState(pieces, settings.bounds(), random);
            if (state == null) continue;
            int targetRooms = randomRange(
                random, settings.criticalPathMin(), settings.criticalPathMax()
            );
            if (!extendCritical(
                state, pieces, settings.bounds(), random, targetRooms, 1
            )) continue;
            int targetBranches = randomRange(
                random, settings.branchCountMin(), settings.branchCountMax()
            );
            if (!attachBranches(
                state, pieces, settings, random, targetBranches
            )) continue;
            DungeonPiecePlan plan = state.toPlan(seed, settings.bounds());
            Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    DungeonPieceDefinition::id, piece -> piece
                )
            );
            return DungeonPieceLoops.add(
                plan, byId, settings.loopChance(), mixSeed(seed, attempt + 10_000)
            );
        }
        throw new IllegalStateException(
            "Dungeon piece planning failed after " + settings.maxAttempts() + " attempts"
        );
    }

    private static State startState(
        List<DungeonPieceDefinition> pieces, BlockPos bounds, Random random
    ) {
        List<DungeonPieceDefinition> starts = weightedOrder(
            pieces.stream().filter(piece -> piece.role().equals("start")).toList(), random
        );
        for (DungeonPieceDefinition piece : starts) {
            for (Rotation rotation : rotationOrder(piece, random)) {
                LocalBounds local = localBounds(piece.size(), rotation);
                BlockPos minimum = new BlockPos(
                    (bounds.getX() - local.size().getX()) / 2,
                    (bounds.getY() - local.size().getY()) / 2,
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
        BlockPos bounds,
        Random random,
        int targetRooms,
        int depth
    ) {
        if (depth >= targetRooms) return true;
        String requiredRole = depth == targetRooms - 2 ? "boss"
            : depth == targetRooms - 1 ? "exit" : null;
        Set<String> flexibleRoles = Set.of("room", "corridor", "junction", "support");
        List<DungeonPieceDefinition> candidates = weightedOrder(
            pieces.stream().filter(piece -> requiredRole == null
                ? flexibleRoles.contains(piece.role())
                : piece.role().equals(requiredRole)).toList(),
            random
        );
        Placed current = state.placements.getLast();
        for (DungeonPieceDefinition.Connector from : connectorOrder(current, state, random)) {
            for (DungeonPieceDefinition piece : candidates) {
                for (Rotation rotation : rotationOrder(piece, random)) {
                    for (DungeonPieceDefinition.Connector to
                        : shuffled(piece.connectors(), random)) {
                        Attachment attachment = attachment(current, from, piece, to, rotation, true);
                        if (attachment == null || !inside(attachment.placed().box(), bounds)
                            || overlapsAny(attachment.placed().box(), state.placements)) {
                            continue;
                        }
                        state.add(attachment);
                        if (extendCritical(
                            state, pieces, bounds, random, targetRooms, depth + 1
                        )) return true;
                        state.removeLast(attachment);
                    }
                }
            }
        }
        return false;
    }

    private static boolean attachBranches(
        State state,
        List<DungeonPieceDefinition> pieces,
        Settings settings,
        Random random,
        int targetBranches
    ) {
        int completed = 0;
        List<Integer> hosts = new ArrayList<>();
        for (int index = 0; index < state.placements.size() - 2; index++) hosts.add(index);
        hosts = shuffled(hosts, random);
        for (int host : hosts) {
            if (completed >= targetBranches) break;
            int depth = randomRange(random, settings.branchDepthMin(), settings.branchDepthMax());
            State snapshot = state.copy();
            if (extendBranch(state, pieces, settings.bounds(), random, host, depth)) {
                completed++;
            } else {
                state.restore(snapshot);
            }
        }
        return completed >= targetBranches;
    }

    private static boolean extendBranch(
        State state,
        List<DungeonPieceDefinition> pieces,
        BlockPos bounds,
        Random random,
        int currentIndex,
        int remaining
    ) {
        if (remaining == 0) return true;
        Set<String> roles = remaining == 1
            ? Set.of("dead_end", "treasure", "support")
            : Set.of("room", "corridor", "junction");
        List<DungeonPieceDefinition> candidates = weightedOrder(
            pieces.stream().filter(piece -> roles.contains(piece.role())).toList(), random
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
                        if (attachment == null || !inside(attachment.placed().box(), bounds)
                            || overlapsAny(attachment.placed().box(), state.placements)) {
                            continue;
                        }
                        state.add(attachment);
                        int nextIndex = state.placements.size() - 1;
                        if (extendBranch(
                            state, pieces, bounds, random, nextIndex, remaining - 1
                        )) return true;
                        state.removeLast(attachment);
                    }
                }
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
        if (fromFacing.getOpposite() != toFacing || !compatible(from, to)) return null;
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

    private static long mixSeed(long seed, int attempt) {
        long value = seed + 0x9E3779B97F4A7C15L * attempt;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
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
        int maxAttempts
    ) {
        private void validate() {
            if (bounds.getX() < 1 || bounds.getY() < 1 || bounds.getZ() < 1
                || criticalPathMin < 3 || criticalPathMin > criticalPathMax
                || branchCountMin < 0 || branchCountMin > branchCountMax
                || branchDepthMin < 1 || branchDepthMin > branchDepthMax
                || loopChance < 0.0D || loopChance > 1.0D
                || maxAttempts < 1 || maxAttempts > 1000) {
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
