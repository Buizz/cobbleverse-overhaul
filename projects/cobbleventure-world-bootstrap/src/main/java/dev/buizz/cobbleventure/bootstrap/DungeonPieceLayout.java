package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Resolves a planned piece graph and its semantic markers into instance coordinates. */
record DungeonPieceLayout(
    DungeonPiecePlan plan,
    List<ResolvedMarker> markers
) {
    private static final Map<String, DungeonPieceLayout> LAST_VALID =
        new ConcurrentHashMap<>();

    static void clearCache() {
        LAST_VALID.clear();
    }

    static DungeonPieceLayout generate(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> allPieces,
        long seed
    ) {
        return generate(definition, allPieces, Map.of(), seed);
    }

    static DungeonPieceLayout generate(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> allPieces,
        Map<String, DungeonAuthoredPlanDefinition> authoredPlans,
        long seed
    ) {
        String pool = definition.terrain().piecePool();
        List<DungeonPieceDefinition> pieces = allPieces.stream()
            .filter(piece -> piece.tags().contains(pool))
            .toList();
        if (pieces.isEmpty()) {
            throw new IllegalStateException("Dungeon piece pool is empty: " + pool);
        }
        DungeonPiecePoolValidator.validate(definition, pieces);
        DungeonDefinition.Layout layout = definition.layout();
        if (definition.plan().mode().equals("runtime")
            && layout.mode().equals("fixed")) {
            throw new IllegalStateException(
                "Dungeon piece layout mode is not implemented yet: " + layout.mode()
            );
        }
        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
        if (definition.plan().mode().equals("runtime")) {
            return runtimeLayout(definition, pieces, byId, seed);
        }
        DungeonPiecePlan plan = authoredPlan(definition, authoredPlans, byId, seed);
        DungeonPiecePlanValidator.validate(
            plan, byId, definition.terrain().piecePool(), definition.terrain().bounds()
        );
        return resolveMarkers(definition, plan, byId, seed);
    }

    static void validateAuthoredDefinitions(
        Collection<DungeonDefinition> dungeons,
        Collection<DungeonPieceDefinition> pieces,
        Map<String, DungeonAuthoredPlanDefinition> authoredPlans
    ) {
        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
        for (DungeonDefinition dungeon : dungeons) {
            if (dungeon.plan().mode().equals("runtime")) continue;
            for (String planId : dungeon.plan().planIds()) {
                DungeonAuthoredPlanDefinition authored = authoredPlans.get(planId);
                if (authored == null) {
                    throw new IllegalStateException(
                        "Dungeon references missing authored plan: "
                            + dungeon.id() + " -> " + planId
                    );
                }
                DungeonPiecePlan plan = authored.toPlan(byId);
                DungeonPiecePlanValidator.validate(
                    plan, byId, dungeon.terrain().piecePool(), dungeon.terrain().bounds()
                );
                resolveMarkers(dungeon, plan, byId, plan.seed());
            }
        }
    }

    private static DungeonPieceLayout runtimeLayout(
        DungeonDefinition definition,
        List<DungeonPieceDefinition> pieces,
        Map<String, DungeonPieceDefinition> byId,
        long seed
    ) {
        IllegalStateException lastFailure = null;
        DungeonPiecePlanner.Settings settings = singleAttempt(
            plannerSettings(definition, false)
        );
        for (int attempt = 0; attempt < definition.plan().maxAttempts(); attempt++) {
            try {
                long attemptSeed = attempt == 0 ? seed
                    : markerSeed(seed + attempt, "layout_attempt");
                DungeonPiecePlan plan = DungeonPiecePlanner.generate(
                    pieces, settings, attemptSeed
                );
                DungeonPiecePlanValidator.validate(
                    plan, byId, definition.terrain().piecePool(),
                    definition.terrain().bounds()
                );
                DungeonPiecePlanValidator.validateNoOpenConnectors(plan, byId);
                return resolveMarkers(definition, plan, byId, seed);
            } catch (IllegalStateException failure) {
                lastFailure = failure;
            }
        }
        if (definition.plan().fallback().equals("use_last_valid")) {
            DungeonPieceLayout cached = LAST_VALID.get(definition.id());
            if (cached != null) return cached;
        }
        if (definition.plan().fallback().equals("use_fallback_plan")) {
            IllegalStateException fallbackFailure = null;
            DungeonPiecePlanner.Settings fallbackSettings = singleAttempt(
                plannerSettings(definition, true)
            );
            for (int attempt = 0; attempt < definition.plan().maxAttempts(); attempt++) {
                try {
                    long fallbackSeed = attempt == 0 ? seed
                        : markerSeed(seed + attempt, "fallback_layout_attempt");
                    DungeonPiecePlan fallback = DungeonPiecePlanner.generate(
                        pieces, fallbackSettings, fallbackSeed
                    );
                    DungeonPiecePlanValidator.validate(
                        fallback, byId, definition.terrain().piecePool(),
                        definition.terrain().bounds()
                    );
                    DungeonPiecePlanValidator.validateNoOpenConnectors(
                        fallback, byId
                    );
                    return resolveMarkers(definition, fallback, byId, seed);
                } catch (IllegalStateException failure) {
                    fallbackFailure = failure;
                }
            }
            if (fallbackFailure != null && lastFailure != null) {
                fallbackFailure.addSuppressed(lastFailure);
            }
            if (fallbackFailure != null) throw fallbackFailure;
        }
        if (lastFailure != null) throw lastFailure;
        throw new IllegalStateException("Dungeon runtime planning produced no attempts");
    }

    private static DungeonPiecePlanner.Settings singleAttempt(
        DungeonPiecePlanner.Settings settings
    ) {
        return new DungeonPiecePlanner.Settings(
            settings.bounds(), settings.criticalPathMin(), settings.criticalPathMax(),
            settings.branchCountMin(), settings.branchCountMax(),
            settings.branchDepthMin(), settings.branchDepthMax(),
            settings.loopChance(), 1, settings.layoutMode(),
            settings.verticalDirection(), settings.floorChangesMin(),
            settings.floorChangesMax()
        );
    }

    private static DungeonPiecePlan authoredPlan(
        DungeonDefinition definition,
        Map<String, DungeonAuthoredPlanDefinition> authoredPlans,
        Map<String, DungeonPieceDefinition> pieces,
        long seed
    ) {
        List<String> ids = definition.plan().planIds();
        int index = definition.plan().mode().equals("authored")
            ? 0 : new java.util.Random(seed).nextInt(ids.size());
        String selectedId = ids.get(index);
        DungeonAuthoredPlanDefinition authored = authoredPlans.get(selectedId);
        if (authored == null) {
            throw new IllegalStateException(
                "Dungeon authored plan is missing: " + selectedId
            );
        }
        return authored.toPlan(pieces);
    }

    private static DungeonPieceLayout resolveMarkers(
        DungeonDefinition definition,
        DungeonPiecePlan plan,
        Map<String, DungeonPieceDefinition> byId,
        long seed
    ) {
        List<ResolvedMarker> markers = new ArrayList<>();
        Map<MarkerKey, BlockPos> uniqueMarkers = new LinkedHashMap<>();
        for (DungeonPiecePlan.Placement placement : plan.placements()) {
            DungeonPieceDefinition piece = byId.get(placement.pieceId());
            if (piece == null) {
                throw new IllegalStateException(
                    "Planned dungeon piece definition disappeared: " + placement.pieceId()
                );
            }
            for (DungeonPieceDefinition.Marker marker : piece.markers()) {
                BlockPos transformed = StructureTemplate.transform(
                    marker.position(), Mirror.NONE, placement.rotation(), BlockPos.ZERO
                );
                BlockPos position = placement.templateOrigin().offset(transformed);
                markers.add(new ResolvedMarker(
                    marker.kind(), marker.reference(), position,
                    placement.index(), marker.connector()
                ));
                if (marker.reference() != null
                    || marker.kind().equals("entry")
                    || marker.kind().equals("exit")) {
                    MarkerKey key = new MarkerKey(marker.kind(), marker.reference());
                    BlockPos previous = uniqueMarkers.putIfAbsent(key, position);
                    if (previous != null) {
                        throw new IllegalStateException(
                            "Duplicate dungeon piece marker: " + key.display()
                        );
                    }
                }
            }
        }
        requireMarker(markers, "entry", null);
        requireMarker(markers, "exit", null);
        DungeonPieceLayout generated = new DungeonPieceLayout(plan, List.copyOf(markers));
        generated.validateGateProgression(definition, seed);
        LAST_VALID.put(definition.id(), generated);
        return generated;
    }

    private static DungeonPiecePlanner.Settings plannerSettings(
        DungeonDefinition definition, boolean safeFallback
    ) {
        DungeonDefinition.Layout layout = definition.layout();
        int safeCriticalRooms = Math.min(
            layout.criticalPathRooms().maximum(),
            Math.max(
                Math.max(6, layout.criticalPathRooms().minimum()),
                layout.floorChanges().minimum() + 3
            )
        );
        int safeBranches = layout.branchCount().maximum() > 0
            && safeCriticalRooms >= 4 ? 1 : 0;
        return new DungeonPiecePlanner.Settings(
            definition.terrain().bounds(),
            safeFallback ? safeCriticalRooms : layout.criticalPathRooms().minimum(),
            safeFallback ? safeCriticalRooms : layout.criticalPathRooms().maximum(),
            safeFallback ? safeBranches : layout.branchCount().minimum(),
            safeFallback ? safeBranches : layout.branchCount().maximum(),
            safeFallback ? 1 : layout.branchDepth().minimum(),
            safeFallback ? 1 : layout.branchDepth().maximum(),
            safeFallback ? 0.0D : layout.loopChance(),
            safeFallback ? Math.max(64, definition.plan().maxAttempts())
                : definition.plan().maxAttempts(),
            safeFallback ? "critical_path_branches" : layout.mode(),
            layout.verticalDirection(),
            layout.floorChanges().minimum(),
            layout.floorChanges().maximum()
        );
    }

    BlockPos requiredMarker(String kind, String reference) {
        return requireMarker(markers, kind, reference);
    }

    Map<MarkerKey, BlockPos> featureMarkers(
        DungeonDefinition definition, long seed
    ) {
        return featureAssignments(definition, seed).entrySet().stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().position()
            )
        );
    }

    private Map<MarkerKey, ResolvedMarker> featureAssignments(
        DungeonDefinition definition, long seed
    ) {
        Map<MarkerKey, ResolvedMarker> assigned = new LinkedHashMap<>();
        Map<String, List<ResolvedMarker>> candidates = new HashMap<>();
        for (ResolvedMarker marker : markers) {
            if (marker.reference() == null
                && !marker.kind().equals("entry")
                && !marker.kind().equals("exit")) {
                if (marker.kind().equals("gate")
                    && !isUsableGateMarker(marker)) continue;
                candidates.computeIfAbsent(marker.kind(), ignored -> new ArrayList<>())
                    .add(marker);
            } else {
                assigned.put(new MarkerKey(marker.kind(), marker.reference()), marker);
            }
        }
        for (Map.Entry<String, List<ResolvedMarker>> entry : candidates.entrySet()) {
            Collections.shuffle(entry.getValue(), new java.util.Random(
                markerSeed(seed, entry.getKey())
            ));
        }

        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            assignFeature(
                assigned, candidates, encounter.boss() ? "boss" : "encounter",
                encounter.id(), encounter.position(), definition.id()
            );
        }
        for (DungeonDefinition.LootContainer container : definition.loot().containers()) {
            assignFeature(
                assigned, candidates, "loot", container.id(),
                container.position(), definition.id()
            );
        }
        for (DungeonDefinition.HealingStation station : definition.support().healingStations()) {
            assignFeature(
                assigned, candidates, "healing_station", station.id(),
                station.position(), definition.id()
            );
        }
        for (DungeonDefinition.Checkpoint checkpoint : definition.support().checkpoints()) {
            assignFeature(
                assigned, candidates, "checkpoint", checkpoint.id(),
                checkpoint.position(), definition.id()
            );
        }
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            assignFeature(
                assigned, candidates, "objective", objective.id(),
                objective.position(), definition.id()
            );
        }
        for (DungeonDefinition.Gate gate : definition.gates()) {
            if (!gate.placement().equals("marker")) continue;
            assignFeature(
                assigned, candidates, "gate", gate.id(), null, definition.id()
            );
        }
        return Map.copyOf(assigned);
    }

    private static long markerSeed(long seed, String kind) {
        long mixed = seed ^ ((long) kind.hashCode() * 0x9E3779B97F4A7C15L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static void assignFeature(
        Map<MarkerKey, ResolvedMarker> assigned,
        Map<String, List<ResolvedMarker>> candidates,
        String kind,
        String reference,
        BlockPos fallback,
        String dungeonId
    ) {
        MarkerKey key = new MarkerKey(kind, reference);
        if (fallback != null) {
            assigned.put(key, new ResolvedMarker(kind, reference, fallback, -1, null));
            return;
        }
        if (assigned.containsKey(key)) return;
        List<ResolvedMarker> available = candidates.getOrDefault(kind, List.of());
        if (!available.isEmpty()) {
            assigned.put(key, available.removeLast());
            return;
        }
        throw new IllegalStateException(
            "Dungeon has no available " + kind + " marker: "
                + dungeonId + " -> " + reference
        );
    }

    private void validateGateProgression(DungeonDefinition definition, long seed) {
        Map<MarkerKey, ResolvedMarker> assigned = featureAssignments(definition, seed);
        int start = plan.placements().stream()
            .filter(placement -> placement.role().equals("start"))
            .map(DungeonPiecePlan.Placement::index).findFirst().orElseThrow();
        for (DungeonDefinition.Gate gate : definition.gates()) {
            if (!gate.placement().equals("marker")) continue;
            ResolvedMarker gateMarker = assigned.get(new MarkerKey("gate", gate.id()));
            if (gateMarker == null || gateMarker.placementIndex() < 0) {
                throw invalidGate(gate, "marker is not attached to a planned piece");
            }
            if (gateMarker.connector() == null) {
                throw invalidGate(gate, "marker does not declare its blocked connector");
            }
            DungeonPiecePlan.Link blocked = linkedAt(gateMarker).orElseThrow(() -> invalidGate(
                gate, "marker connector is not used by a plan link"
            ));
            Set<Integer> reachable = reachable(start, graphWithout(blocked));
            boolean fromReachable = reachable.contains(blocked.fromIndex());
            boolean toReachable = reachable.contains(blocked.toIndex());
            if (fromReachable == toReachable) {
                throw invalidGate(gate, "blocked link does not separate locked progression");
            }
            for (DungeonDefinition.GateRequirement requirement : gate.requirements()) {
                if (requirement.type().equals("item")) continue;
                String kind = requirement.type();
                if (kind.equals("encounter")) {
                    DungeonDefinition.Encounter encounter = definition.encounters().stream()
                        .filter(value -> value.id().equals(requirement.reference()))
                        .findFirst().orElseThrow();
                    kind = encounter.boss() ? "boss" : "encounter";
                }
                ResolvedMarker required = assigned.get(new MarkerKey(
                    kind, requirement.reference()
                ));
                if (required == null || required.placementIndex() < 0) continue;
                if (!reachable.contains(required.placementIndex())) {
                    throw invalidGate(
                        gate, "required " + requirement.type()
                            + " is behind the gate: " + requirement.reference()
                    );
                }
            }
        }
    }

    private boolean isUsableGateMarker(ResolvedMarker marker) {
        if (plan == null) return true;
        if (marker.connector() == null || marker.placementIndex() < 0) return false;
        return linkedAt(marker).filter(link -> {
            int start = plan.placements().stream()
                .filter(placement -> placement.role().equals("start"))
                .map(DungeonPiecePlan.Placement::index).findFirst().orElseThrow();
            Set<Integer> reachable = reachable(start, graphWithout(link));
            return reachable.contains(link.fromIndex())
                != reachable.contains(link.toIndex());
        }).isPresent();
    }

    private java.util.Optional<DungeonPiecePlan.Link> linkedAt(ResolvedMarker marker) {
        return plan.links().stream().filter(link ->
            (link.fromIndex() == marker.placementIndex()
                && link.fromConnector().equals(marker.connector()))
            || (link.toIndex() == marker.placementIndex()
                && link.toConnector().equals(marker.connector()))
        ).findFirst();
    }

    private Map<Integer, Set<Integer>> graphWithout(DungeonPiecePlan.Link blocked) {
        Map<Integer, Set<Integer>> graph = new HashMap<>();
        for (DungeonPiecePlan.Link link : plan.links()) {
            if (link.equals(blocked)) continue;
            graph.computeIfAbsent(link.fromIndex(), ignored -> new HashSet<>())
                .add(link.toIndex());
            graph.computeIfAbsent(link.toIndex(), ignored -> new HashSet<>())
                .add(link.fromIndex());
        }
        return graph;
    }

    private static Set<Integer> reachable(int start, Map<Integer, Set<Integer>> graph) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (!visited.add(current)) continue;
            graph.getOrDefault(current, Set.of()).forEach(queue::addLast);
        }
        return visited;
    }

    private static IllegalStateException invalidGate(
        DungeonDefinition.Gate gate, String reason
    ) {
        return new IllegalStateException(
            "Invalid dungeon gate progression: " + gate.id() + " -> " + reason
        );
    }

    private static BlockPos requireMarker(
        List<ResolvedMarker> markers, String kind, String reference
    ) {
        return markers.stream()
            .filter(marker -> marker.kind().equals(kind)
                && java.util.Objects.equals(marker.reference(), reference))
            .map(ResolvedMarker::position)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Dungeon piece marker is missing: "
                    + new MarkerKey(kind, reference).display()
            ));
    }

    record ResolvedMarker(
        String kind,
        String reference,
        BlockPos position,
        int placementIndex,
        String connector
    ) {
        ResolvedMarker(String kind, String reference, BlockPos position) {
            this(kind, reference, position, -1, null);
        }
    }

    record MarkerKey(String kind, String reference) {
        private String display() {
            return reference == null ? kind : kind + ":" + reference;
        }
    }
}
