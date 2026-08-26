package dev.buizz.cobbleventure.bootstrap;

import java.util.Collection;
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
    Map<MarkerKey, BlockPos> markers
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
            && !layout.mode().equals("critical_path_branches")) {
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
                resolveMarkers(dungeon.id(), plan, byId);
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
        Map<MarkerKey, BlockPos> markers = new LinkedHashMap<>();
        for (DungeonPiecePlan.Placement placement : plan.placements()) {
            DungeonPieceDefinition piece = byId.get(placement.pieceId());
            if (piece == null) {
                throw new IllegalStateException(
                    "Planned dungeon piece definition disappeared: " + placement.pieceId()
                );
            }
            for (DungeonPieceDefinition.Marker marker : piece.markers()) {
                if (marker.reference() == null
                    && !marker.kind().equals("entry")
                    && !marker.kind().equals("exit")) {
                    continue;
                }
                MarkerKey key = new MarkerKey(marker.kind(), marker.reference());
                BlockPos transformed = StructureTemplate.transform(
                    marker.position(), Mirror.NONE, placement.rotation(), BlockPos.ZERO
                );
                BlockPos previous = markers.putIfAbsent(
                    key, placement.templateOrigin().offset(transformed)
                );
                if (previous != null) {
                    throw new IllegalStateException(
                        "Duplicate dungeon piece marker: " + key.display()
                    );
                }
            }
        }
        requireMarker(markers, "entry", null);
        requireMarker(markers, "exit", null);
        DungeonPieceLayout generated = new DungeonPieceLayout(plan, Map.copyOf(markers));
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
                : definition.plan().maxAttempts()
        );
    }

    BlockPos requiredMarker(String kind, String reference) {
        return requireMarker(markers, kind, reference);
    }

    BlockPos markerOr(String kind, String reference, BlockPos fallback) {
        return markers.getOrDefault(new MarkerKey(kind, reference), fallback);
    }

    private static BlockPos requireMarker(
        Map<MarkerKey, BlockPos> markers, String kind, String reference
    ) {
        MarkerKey key = new MarkerKey(kind, reference);
        BlockPos marker = markers.get(key);
        if (marker == null) {
            throw new IllegalStateException("Dungeon piece marker is missing: " + key.display());
        }
        return marker;
    }

    record MarkerKey(String kind, String reference) {
        private String display() {
            return reference == null ? kind : kind + ":" + reference;
        }
    }
}
