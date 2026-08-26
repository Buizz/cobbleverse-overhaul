package dev.buizz.cobbleventure.bootstrap;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Resolves a planned piece graph and its semantic markers into instance coordinates. */
record DungeonPieceLayout(
    DungeonPiecePlan plan,
    Map<MarkerKey, BlockPos> markers
) {
    static DungeonPieceLayout generate(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> allPieces,
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
        if (!layout.mode().equals("critical_path_branches")) {
            throw new IllegalStateException(
                "Dungeon piece layout mode is not implemented yet: " + layout.mode()
            );
        }
        DungeonPiecePlan plan = DungeonPiecePlanner.generate(
            pieces,
            new DungeonPiecePlanner.Settings(
                definition.terrain().bounds(),
                layout.criticalPathRooms().minimum(),
                layout.criticalPathRooms().maximum(),
                layout.branchCount().minimum(),
                layout.branchCount().maximum(),
                layout.branchDepth().minimum(),
                layout.branchDepth().maximum(),
                layout.loopChance(),
                definition.plan().maxAttempts()
            ),
            seed
        );

        Map<String, DungeonPieceDefinition> byId = pieces.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                DungeonPieceDefinition::id, piece -> piece
            )
        );
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
        return new DungeonPieceLayout(plan, Map.copyOf(markers));
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
