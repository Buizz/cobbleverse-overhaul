package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;

/** Places authored NBT landmarks into safe rooms carved by a procedural cave. */
final class DungeonHybridLandmarkLayout {
    private DungeonHybridLandmarkLayout() {}

    static Result plan(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> allPieces,
        List<BlockPos> mainRooms,
        List<BlockPos> branchRooms,
        long seed
    ) {
        String pool = definition.terrain().piecePool();
        List<DungeonPieceDefinition> pieces = allPieces.stream()
            .filter(piece -> piece.tags().contains(pool))
            .toList();
        if (pieces.isEmpty()) {
            throw new IllegalStateException("Hybrid dungeon piece pool is empty: " + pool);
        }
        List<Feature> features = requiredFeatures(definition);
        List<BlockPos> main = new ArrayList<>(mainRooms);
        List<BlockPos> branch = new ArrayList<>(branchRooms);
        BlockPos lateMain = main.isEmpty() ? null : main.removeLast();
        Collections.shuffle(main, random(seed, "hybrid_main"));
        Collections.shuffle(branch, random(seed, "hybrid_branch"));
        Map<String, Integer> uses = new HashMap<>();
        List<Placement> placements = new ArrayList<>();
        Map<DungeonPieceLayout.MarkerKey, BlockPos> markers = new LinkedHashMap<>();

        for (Feature feature : features) {
            List<DungeonPieceDefinition> candidates = pieces.stream()
                .filter(piece -> piece.markers().stream().anyMatch(
                    marker -> marker.kind().equals(feature.kind())
                ))
                .filter(piece -> uses.getOrDefault(piece.id(), 0) < piece.maximumPerPlan())
                .toList();
            if (candidates.isEmpty()) continue;
            DungeonPieceDefinition piece = weighted(candidates, random(
                seed, feature.kind() + ":" + feature.reference()
            ));
            DungeonPieceDefinition.Marker primary = piece.markers().stream()
                .filter(marker -> marker.kind().equals(feature.kind()))
                .findFirst().orElseThrow();
            BlockPos target;
            if (feature.preferLateMain() && lateMain != null) {
                target = lateMain;
                lateMain = null;
            } else {
                target = takeTarget(feature.preferBranch(), branch, main);
                if (target == null && lateMain != null) {
                    target = lateMain;
                    lateMain = null;
                }
            }
            if (target == null) {
                throw new IllegalStateException(
                    "Hybrid dungeon has no safe room for NBT landmark: "
                        + definition.id() + " -> " + feature.reference()
                );
            }
            BlockPos templateOrigin = target.subtract(primary.position());
            requireInside(definition, piece, templateOrigin);
            int index = placements.size();
            placements.add(new Placement(
                index, piece, templateOrigin, feature.kind(), feature.reference()
            ));
            uses.merge(piece.id(), 1, Integer::sum);
            markers.put(
                new DungeonPieceLayout.MarkerKey(feature.kind(), feature.reference()),
                target
            );
            for (DungeonPieceDefinition.Marker marker : piece.markers()) {
                if (marker == primary) continue;
                if (marker.reference() == null) continue;
                markers.putIfAbsent(
                    new DungeonPieceLayout.MarkerKey(marker.kind(), marker.reference()),
                    templateOrigin.offset(marker.position())
                );
            }
        }
        return new Result(List.copyOf(placements), Map.copyOf(markers));
    }

    private static List<Feature> requiredFeatures(DungeonDefinition definition) {
        List<Feature> result = new ArrayList<>();
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            result.add(new Feature(
                encounter.boss() ? "boss" : "encounter", encounter.id(),
                false, encounter.boss()
            ));
        }
        for (DungeonDefinition.LootContainer container : definition.loot().containers()) {
            result.add(new Feature("loot", container.id(), true, false));
        }
        for (DungeonDefinition.HealingStation station
            : definition.support().healingStations()) {
            result.add(new Feature("healing_station", station.id(), true, false));
        }
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            result.add(new Feature("objective", objective.id(), true, false));
        }
        return List.copyOf(result);
    }

    private static BlockPos takeTarget(
        boolean preferBranch, List<BlockPos> branch, List<BlockPos> main
    ) {
        if (preferBranch && !branch.isEmpty()) return branch.removeLast();
        if (!main.isEmpty()) return main.removeLast();
        if (!branch.isEmpty()) return branch.removeLast();
        return null;
    }

    private static DungeonPieceDefinition weighted(
        List<DungeonPieceDefinition> candidates, Random random
    ) {
        int total = candidates.stream().mapToInt(DungeonPieceDefinition::weight).sum();
        int roll = random.nextInt(total);
        for (DungeonPieceDefinition piece : candidates) {
            roll -= piece.weight();
            if (roll < 0) return piece;
        }
        return candidates.getLast();
    }

    private static void requireInside(
        DungeonDefinition definition,
        DungeonPieceDefinition piece,
        BlockPos origin
    ) {
        BlockPos bounds = definition.terrain().bounds();
        if (origin.getX() < 0 || origin.getY() < 0 || origin.getZ() < 0
            || origin.getX() + piece.size().getX() > bounds.getX()
            || origin.getY() + piece.size().getY() > bounds.getY()
            || origin.getZ() + piece.size().getZ() > bounds.getZ()) {
            throw new IllegalStateException(
                "Hybrid dungeon NBT landmark exceeds slot bounds: "
                    + definition.id() + " -> " + piece.id()
            );
        }
    }

    private static Random random(long seed, String salt) {
        return new Random(seed ^ (long) salt.hashCode() * 0x9E3779B97F4A7C15L);
    }

    private record Feature(
        String kind, String reference, boolean preferBranch, boolean preferLateMain
    ) {}

    record Placement(
        int index,
        DungeonPieceDefinition piece,
        BlockPos templateOrigin,
        String primaryKind,
        String primaryReference
    ) {}

    record Result(
        List<Placement> placements,
        Map<DungeonPieceLayout.MarkerKey, BlockPos> featureMarkers
    ) {}
}
