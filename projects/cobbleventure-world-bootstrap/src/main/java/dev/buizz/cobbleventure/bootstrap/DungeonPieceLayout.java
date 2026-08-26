package dev.buizz.cobbleventure.bootstrap;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        DungeonPiecePlan plan = definition.plan().mode().equals("runtime")
            ? runtimePlan(definition, pieces, seed)
            : authoredPlan(definition, authoredPlans, byId, seed);
        DungeonPiecePlanValidator.validate(
            plan, byId, definition.terrain().piecePool(), definition.terrain().bounds()
        );
        return resolveMarkers(definition.id(), plan, byId);
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
                resolveMarkers(dungeon.id(), plan, byId)
                    .featureMarkers(dungeon, plan.seed());
            }
        }
    }

    private static DungeonPiecePlan runtimePlan(
        DungeonDefinition definition,
        List<DungeonPieceDefinition> pieces,
        long seed
    ) {
        try {
            return DungeonPiecePlanner.generate(
                pieces, plannerSettings(definition, false), seed
            );
        } catch (IllegalStateException planningFailure) {
            if (definition.plan().fallback().equals("use_last_valid")) {
                DungeonPieceLayout cached = LAST_VALID.get(definition.id());
                if (cached != null) return cached.plan();
            }
            if (!definition.plan().fallback().equals("use_fallback_plan")) {
                throw planningFailure;
            }
            return DungeonPiecePlanner.generate(
                pieces, plannerSettings(definition, true), seed
            );
        }
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
        String dungeonId,
        DungeonPiecePlan plan,
        Map<String, DungeonPieceDefinition> byId
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
                    marker.kind(), marker.reference(), position
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
        LAST_VALID.put(dungeonId, generated);
        return generated;
    }

    private static DungeonPiecePlanner.Settings plannerSettings(
        DungeonDefinition definition, boolean safeFallback
    ) {
        DungeonDefinition.Layout layout = definition.layout();
        return new DungeonPiecePlanner.Settings(
            definition.terrain().bounds(),
            safeFallback ? 3 : layout.criticalPathRooms().minimum(),
            safeFallback ? 3 : layout.criticalPathRooms().maximum(),
            safeFallback ? 0 : layout.branchCount().minimum(),
            safeFallback ? 0 : layout.branchCount().maximum(),
            safeFallback ? 1 : layout.branchDepth().minimum(),
            safeFallback ? 1 : layout.branchDepth().maximum(),
            safeFallback ? 0.0D : layout.loopChance(),
            safeFallback ? Math.max(64, definition.plan().maxAttempts())
                : definition.plan().maxAttempts(),
            safeFallback ? "critical_path_branches" : layout.mode()
        );
    }

    BlockPos requiredMarker(String kind, String reference) {
        return requireMarker(markers, kind, reference);
    }

    Map<MarkerKey, BlockPos> featureMarkers(
        DungeonDefinition definition, long seed
    ) {
        Map<MarkerKey, BlockPos> assigned = new LinkedHashMap<>();
        Map<String, List<BlockPos>> candidates = new HashMap<>();
        for (ResolvedMarker marker : markers) {
            if (marker.reference() == null
                && !marker.kind().equals("entry")
                && !marker.kind().equals("exit")) {
                candidates.computeIfAbsent(marker.kind(), ignored -> new ArrayList<>())
                    .add(marker.position());
            } else {
                assigned.put(new MarkerKey(marker.kind(), marker.reference()), marker.position());
            }
        }
        for (Map.Entry<String, List<BlockPos>> entry : candidates.entrySet()) {
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
        return Map.copyOf(assigned);
    }

    private static long markerSeed(long seed, String kind) {
        long mixed = seed ^ ((long) kind.hashCode() * 0x9E3779B97F4A7C15L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private static void assignFeature(
        Map<MarkerKey, BlockPos> assigned,
        Map<String, List<BlockPos>> candidates,
        String kind,
        String reference,
        BlockPos fallback,
        String dungeonId
    ) {
        MarkerKey key = new MarkerKey(kind, reference);
        if (fallback != null) {
            assigned.put(key, fallback);
            return;
        }
        if (assigned.containsKey(key)) return;
        List<BlockPos> available = candidates.getOrDefault(kind, List.of());
        if (!available.isEmpty()) {
            assigned.put(key, available.removeLast());
            return;
        }
        throw new IllegalStateException(
            "Dungeon has no available " + kind + " marker: "
                + dungeonId + " -> " + reference
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

    record ResolvedMarker(String kind, String reference, BlockPos position) {}

    record MarkerKey(String kind, String reference) {
        private String display() {
            return reference == null ? kind : kind + ":" + reference;
        }
    }
}
