package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Shared structural validator for web-authored and server-generated piece plans. */
final class DungeonPiecePlanValidator {
    private DungeonPiecePlanValidator() {}

    static void validate(
        DungeonPiecePlan plan,
        Map<String, DungeonPieceDefinition> pieces,
        String requiredPool,
        BlockPos requiredBounds
    ) {
        if (!plan.bounds().equals(requiredBounds)) {
            throw invalid("plan bounds differ from dungeon bounds");
        }
        if (plan.placements().size() < 3) {
            throw invalid("fewer than three placements");
        }
        Map<Integer, DungeonPiecePlan.Placement> placements = new HashMap<>();
        int starts = 0;
        int bosses = 0;
        int exits = 0;
        Map<String, Integer> usage = new HashMap<>();
        for (DungeonPiecePlan.Placement placement : plan.placements()) {
            if (placement.index() < 0
                || placements.putIfAbsent(placement.index(), placement) != null) {
                throw invalid("duplicate or negative placement index");
            }
            DungeonPieceDefinition piece = pieces.get(placement.pieceId());
            if (piece == null || !piece.tags().contains(requiredPool)) {
                throw invalid("placement references a piece outside its pool");
            }
            if (!piece.role().equals(placement.role())) {
                throw invalid("placement role differs from piece metadata");
            }
            if (!piece.allowsPlacement(placement.criticalPath())) {
                throw invalid("placement is outside the piece path scope: " + piece.id());
            }
            usage.merge(piece.id(), 1, Integer::sum);
            if (!piece.allowRotation()
                && placement.rotation() != net.minecraft.world.level.block.Rotation.NONE) {
                throw invalid("placement rotates a non-rotatable piece");
            }
            if (!inside(placement, requiredBounds)) {
                throw invalid("placement exceeds plan bounds");
            }
            if (piece.role().equals("start")) starts++;
            if (piece.role().equals("boss")) bosses++;
            if (piece.role().equals("exit")) exits++;
        }
        if (starts != 1 || bosses != 1 || exits != 1) {
            throw invalid("plan requires exactly one start, boss, and exit piece");
        }
        for (DungeonPieceDefinition piece : pieces.values()) {
            if (!piece.tags().contains(requiredPool)) continue;
            int count = usage.getOrDefault(piece.id(), 0);
            if (count < piece.minimumPerPlan() || count > piece.maximumPerPlan()) {
                throw invalid("piece usage is outside its configured limits: " + piece.id());
            }
        }
        for (int first = 0; first < plan.placements().size(); first++) {
            for (int second = first + 1; second < plan.placements().size(); second++) {
                if (overlaps(plan.placements().get(first), plan.placements().get(second))) {
                    throw invalid("placements overlap");
                }
            }
        }

        Set<ConnectorKey> used = new HashSet<>();
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        Map<Integer, Set<Integer>> criticalGraph = new HashMap<>();
        for (DungeonPiecePlan.Link link : plan.links()) {
            DungeonPiecePlan.Placement fromPlacement = placements.get(link.fromIndex());
            DungeonPiecePlan.Placement toPlacement = placements.get(link.toIndex());
            if (fromPlacement == null || toPlacement == null
                || link.fromIndex() == link.toIndex()) {
                throw invalid("link references an invalid placement");
            }
            DungeonPieceDefinition.Connector from = connector(
                pieces.get(fromPlacement.pieceId()), link.fromConnector()
            );
            DungeonPieceDefinition.Connector to = connector(
                pieces.get(toPlacement.pieceId()), link.toConnector()
            );
            if (!pieces.get(fromPlacement.pieceId()).allowsAdjacentTo(
                pieces.get(toPlacement.pieceId()))) {
                throw invalid("linked pieces violate an adjacency restriction");
            }
            if (!used.add(new ConnectorKey(link.fromIndex(), link.fromConnector()))
                || !used.add(new ConnectorKey(link.toIndex(), link.toConnector()))) {
                throw invalid("connector is used by more than one link");
            }
            Direction fromFacing = fromPlacement.rotation().rotate(from.facing());
            Direction toFacing = toPlacement.rotation().rotate(to.facing());
            if (fromFacing.getOpposite() != toFacing || !compatible(from, to)
                || !connectorPosition(fromPlacement, from).relative(fromFacing)
                    .equals(connectorPosition(toPlacement, to))) {
                throw invalid("linked connectors are not physically compatible");
            }
            connect(graph, link.fromIndex(), link.toIndex());
            if (link.criticalPath()) {
                if (!fromPlacement.criticalPath() || !toPlacement.criticalPath()) {
                    throw invalid("critical link references a branch placement");
                }
                connect(criticalGraph, link.fromIndex(), link.toIndex());
            }
        }
        requireConnected("plan", placements.keySet(), graph);
        Set<Integer> critical = plan.placements().stream()
            .filter(DungeonPiecePlan.Placement::criticalPath)
            .map(DungeonPiecePlan.Placement::index)
            .collect(java.util.stream.Collectors.toSet());
        requireConnected("critical path", critical, criticalGraph);
        for (int index : critical) {
            DungeonPiecePlan.Placement placement = placements.get(index);
            int degree = criticalGraph.getOrDefault(index, Set.of()).size();
            boolean endpoint = placement.role().equals("start")
                || placement.role().equals("exit");
            if (degree != (endpoint ? 1 : 2)) {
                throw invalid("critical path is not a single start-to-exit path");
            }
        }
        DungeonPiecePlan.Placement boss = plan.placements().stream()
            .filter(placement -> placement.role().equals("boss"))
            .findFirst().orElseThrow();
        if (!boss.criticalPath()) {
            throw invalid("boss is outside the critical path");
        }
    }

    private static void requireConnected(
        String name, Set<Integer> expected, Map<Integer, Set<Integer>> graph
    ) {
        if (expected.isEmpty()) throw invalid(name + " is empty");
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(expected.iterator().next());
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (!visited.add(current)) continue;
            graph.getOrDefault(current, Set.of()).stream()
                .filter(expected::contains).forEach(queue::addLast);
        }
        if (!visited.containsAll(expected)) throw invalid(name + " is disconnected");
    }

    private static void connect(Map<Integer, Set<Integer>> graph, int first, int second) {
        graph.computeIfAbsent(first, ignored -> new HashSet<>()).add(second);
        graph.computeIfAbsent(second, ignored -> new HashSet<>()).add(first);
    }

    private static DungeonPieceDefinition.Connector connector(
        DungeonPieceDefinition piece, String id
    ) {
        return piece.connectors().stream().filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(() -> invalid("link references a missing connector"));
    }

    private static BlockPos connectorPosition(
        DungeonPiecePlan.Placement placement,
        DungeonPieceDefinition.Connector connector
    ) {
        return placement.templateOrigin().offset(StructureTemplate.transform(
            connector.position(), Mirror.NONE, placement.rotation(), BlockPos.ZERO
        ));
    }

    private static boolean compatible(
        DungeonPieceDefinition.Connector first,
        DungeonPieceDefinition.Connector second
    ) {
        return first.socket().equals(second.socket())
            && (first.tags().isEmpty() || second.tags().isEmpty()
                || first.tags().stream().anyMatch(second.tags()::contains));
    }

    private static boolean inside(
        DungeonPiecePlan.Placement placement, BlockPos bounds
    ) {
        BlockPos minimum = placement.minimum();
        BlockPos maximum = minimum.offset(placement.size());
        return minimum.getX() >= 0 && minimum.getY() >= 0 && minimum.getZ() >= 0
            && maximum.getX() <= bounds.getX()
            && maximum.getY() <= bounds.getY()
            && maximum.getZ() <= bounds.getZ();
    }

    private static boolean overlaps(
        DungeonPiecePlan.Placement first, DungeonPiecePlan.Placement second
    ) {
        BlockPos firstMax = first.minimum().offset(first.size());
        BlockPos secondMax = second.minimum().offset(second.size());
        return first.minimum().getX() < secondMax.getX()
            && firstMax.getX() > second.minimum().getX()
            && first.minimum().getY() < secondMax.getY()
            && firstMax.getY() > second.minimum().getY()
            && first.minimum().getZ() < secondMax.getZ()
            && firstMax.getZ() > second.minimum().getZ();
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Invalid dungeon piece plan: " + reason);
    }

    private record ConnectorKey(int placementIndex, String connectorId) {}
}
