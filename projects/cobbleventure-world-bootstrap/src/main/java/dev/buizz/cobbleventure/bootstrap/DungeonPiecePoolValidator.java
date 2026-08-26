package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rejects piece pools that cannot possibly supply a dungeon's semantic content. */
final class DungeonPiecePoolValidator {
    private DungeonPiecePoolValidator() {}

    static void validate(
        DungeonDefinition dungeon, List<DungeonPieceDefinition> pieces
    ) {
        Map<String, List<String>> required = requiredMarkers(dungeon);
        for (Map.Entry<String, List<String>> entry : required.entrySet()) {
            String kind = entry.getKey();
            int genericNeeded = 0;
            for (String reference : entry.getValue()) {
                if (!hasExactMarker(pieces, kind, reference)) genericNeeded++;
            }
            int capacity = genericCapacity(dungeon, pieces, kind);
            if (capacity < genericNeeded) {
                throw new IllegalStateException(
                    "Dungeon piece pool cannot supply required markers: "
                        + dungeon.id() + " -> " + kind + " needs " + genericNeeded
                        + " generic slots but pool capacity is " + capacity
                );
            }
        }
        requireStructuralMarker(dungeon, pieces, "entry");
        requireStructuralMarker(dungeon, pieces, "exit");
        validateMinimumUsage(dungeon, pieces);
    }

    private static Map<String, List<String>> requiredMarkers(
        DungeonDefinition dungeon
    ) {
        Map<String, List<String>> required = new HashMap<>();
        for (DungeonDefinition.Encounter encounter : dungeon.encounters()) {
            if (encounter.position() == null) add(
                required, encounter.boss() ? "boss" : "encounter", encounter.id()
            );
        }
        for (DungeonDefinition.LootContainer container : dungeon.loot().containers()) {
            if (container.position() == null) add(required, "loot", container.id());
        }
        for (DungeonDefinition.HealingStation station
            : dungeon.support().healingStations()) {
            if (station.position() == null) add(
                required, "healing_station", station.id()
            );
        }
        for (DungeonDefinition.Checkpoint checkpoint : dungeon.support().checkpoints()) {
            if (checkpoint.position() == null) add(
                required, "checkpoint", checkpoint.id()
            );
        }
        for (DungeonDefinition.Objective objective : dungeon.objectives()) {
            if (objective.position() == null) add(required, "objective", objective.id());
        }
        for (DungeonDefinition.Gate gate : dungeon.gates()) {
            if (gate.placement().equals("marker")) add(required, "gate", gate.id());
        }
        if (dungeon.completion().returnTrigger().equals("clear_exit")
            && dungeon.completion().clearExitPosition() == null) {
            add(required, "objective", "clear_exit");
        }
        return required;
    }

    private static void add(
        Map<String, List<String>> required, String kind, String reference
    ) {
        required.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(reference);
    }

    private static boolean hasExactMarker(
        List<DungeonPieceDefinition> pieces, String kind, String reference
    ) {
        return pieces.stream().flatMap(piece -> piece.markers().stream()).anyMatch(marker ->
            marker.kind().equals(kind) && reference.equals(marker.reference())
        );
    }

    private static int genericCapacity(
        DungeonDefinition dungeon,
        List<DungeonPieceDefinition> pieces,
        String kind
    ) {
        return pieces.stream().mapToInt(piece -> {
            int markers = (int) piece.markers().stream().filter(marker ->
                marker.kind().equals(kind) && marker.reference() == null
            ).count();
            return markers * maximumPlacements(dungeon, piece);
        }).sum();
    }

    private static int maximumPlacements(
        DungeonDefinition dungeon, DungeonPieceDefinition piece
    ) {
        int layoutMaximum = switch (piece.placementScope()) {
            case "critical_path" -> dungeon.layout().criticalPathRooms().maximum();
            case "branch" -> dungeon.layout().branchCount().maximum()
                * dungeon.layout().branchDepth().maximum();
            default -> dungeon.layout().criticalPathRooms().maximum()
                + dungeon.layout().branchCount().maximum()
                    * dungeon.layout().branchDepth().maximum();
        };
        return Math.min(piece.maximumPerPlan(), layoutMaximum);
    }

    private static void requireStructuralMarker(
        DungeonDefinition dungeon,
        List<DungeonPieceDefinition> pieces,
        String kind
    ) {
        boolean present = pieces.stream().flatMap(piece -> piece.markers().stream())
            .anyMatch(marker -> marker.kind().equals(kind) && marker.reference() == null);
        if (!present) {
            throw new IllegalStateException(
                "Dungeon piece pool has no " + kind + " marker: " + dungeon.id()
            );
        }
    }

    private static void validateMinimumUsage(
        DungeonDefinition dungeon, List<DungeonPieceDefinition> pieces
    ) {
        int criticalMinimum = pieces.stream()
            .filter(piece -> piece.placementScope().equals("critical_path"))
            .mapToInt(DungeonPieceDefinition::minimumPerPlan).sum();
        int branchMinimum = pieces.stream()
            .filter(piece -> piece.placementScope().equals("branch"))
            .mapToInt(DungeonPieceDefinition::minimumPerPlan).sum();
        int totalMinimum = pieces.stream()
            .mapToInt(DungeonPieceDefinition::minimumPerPlan).sum();
        int maximumBranches = dungeon.layout().branchCount().maximum()
            * dungeon.layout().branchDepth().maximum();
        int maximumTotal = dungeon.layout().criticalPathRooms().maximum()
            + maximumBranches;
        if (criticalMinimum > dungeon.layout().criticalPathRooms().maximum()
            || branchMinimum > maximumBranches || totalMinimum > maximumTotal) {
            throw new IllegalStateException(
                "Dungeon piece minimum usage cannot fit layout bounds: " + dungeon.id()
            );
        }
    }
}
