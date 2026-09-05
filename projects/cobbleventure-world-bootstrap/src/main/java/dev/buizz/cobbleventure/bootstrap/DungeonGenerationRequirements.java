package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * One authoritative sizing decision made before piece placement starts.
 *
 * <p>Actor demand, spare capacity and chamber capacity must not independently
 * resize the topology later in the pipeline. The planner consumes this record
 * and the finished layout only validates the resulting real marker capacity.</p>
 */
record DungeonGenerationRequirements(
    int actorDemand,
    int ordinaryActorDemand,
    int requestedCapacity,
    int reservedCapacity,
    int planningFloorCount,
    List<String> chamberPieceIds,
    int chamberCapacity,
    int chamberCount,
    int additionalPassageCount,
    int criticalPathMinimum,
    int criticalPathMaximum
) {
    static DungeonGenerationRequirements calculate(
        DungeonDefinition definition,
        Collection<DungeonPieceDefinition> pieces
    ) {
        int actorDemand = definition.encounters().stream()
            .filter(encounter -> encounter.kind().equals("trainer"))
            .mapToInt(DungeonDefinition.Encounter::actorCount).sum();
        int ordinaryActorDemand = definition.encounters().stream()
            .filter(encounter -> encounter.kind().equals("trainer")
                && !encounter.boss())
            .mapToInt(DungeonDefinition.Encounter::actorCount).sum();
        boolean generatedPopulationMaterialized = definition.encounters().stream()
            .anyMatch(encounter -> encounter.generatedTrainer() != null);
        if (definition.generatedTrainers().enabled()
            && !generatedPopulationMaterialized) {
            int generated = definition.generatedTrainers().count().maximum();
            actorDemand += generated;
            ordinaryActorDemand += generated;
        }

        int requestedCapacity = definition.npcPlacement().enabled()
            ? definition.npcPlacement().requiredSlots() : 0;
        int reservedCapacity = Math.max(0, requestedCapacity - actorDemand);
        int floorCount = definition.vertical().mode().equals("discrete_floors")
            ? definition.vertical().floorCount().maximum() : 1;

        List<String> selected = definition.spatialLayout().chamberPieces();
        List<DungeonPieceDefinition> selectedChambers = pieces.stream()
            .filter(piece -> selected.contains(piece.id()))
            .toList();
        List<DungeonPieceDefinition> usableChambers = definition.npcPlacement().enabled()
            ? selectedChambers.stream().filter(piece -> safeNpcCapacity(
                piece, definition.npcPlacement().minimumSpacing()
            ) > 0).toList()
            : selectedChambers;
        int chamberCapacity = usableChambers.stream()
            .mapToInt(piece -> safeNpcCapacity(
                piece, definition.npcPlacement().minimumSpacing()
            ))
            .min().orElse(0);
        int chamberCount = 0;
        if (!definition.npcPlacement().enabled() && !usableChambers.isEmpty()) {
            chamberCount = floorCount;
        } else if (ordinaryActorDemand > 0 && chamberCapacity > 0) {
            int floorsWithNpcs = Math.min(floorCount, ordinaryActorDemand);
            chamberCount = Math.max(
                floorsWithNpcs,
                divideRoundUp(ordinaryActorDemand, chamberCapacity)
            );
            chamberCount = Math.min(chamberCount, ordinaryActorDemand);
        }

        DungeonDefinition.Topology topology = definition.topology();
        int floorStructureMinimum = definition.vertical().mode().equals("discrete_floors")
            ? floorCount * 6 + 1 : 3;
        int chamberCadenceMinimum = chamberCount == 0 ? 3
            : 3 + chamberCount + Math.max(0, chamberCount - 1) * 2;
        int baseCriticalMinimum = Math.max(
            topology.criticalPathRooms().minimum(),
            Math.max(floorStructureMinimum, chamberCadenceMinimum)
        );
        int estimatedPassageCapacity = Math.max(
            0, baseCriticalMinimum - 3 - chamberCount
        );
        int estimatedCapacity = chamberCount * chamberCapacity
            + estimatedPassageCapacity;
        int additionalPassages = definition.npcPlacement().enabled()
            ? Math.max(0, requestedCapacity - estimatedCapacity) : 0;
        int criticalMinimum = baseCriticalMinimum + additionalPassages;
        int criticalMaximum = Math.max(
            criticalMinimum,
            topology.criticalPathRooms().maximum() + additionalPassages
        );

        return new DungeonGenerationRequirements(
            actorDemand, ordinaryActorDemand, requestedCapacity, reservedCapacity,
            floorCount, usableChambers.stream().map(
                DungeonPieceDefinition::id
            ).toList(), chamberCapacity, chamberCount, additionalPassages,
            criticalMinimum, criticalMaximum
        );
    }

    static int safeNpcCapacity(
        DungeonPieceDefinition piece, double minimumSpacing
    ) {
        List<BlockPos> positions = piece.markers().stream()
            .filter(marker -> marker.kind().equals("npc_spawn"))
            .map(DungeonPieceDefinition.Marker::position)
            .toList();
        return maximumCompatible(positions, 0, new ArrayList<>(), minimumSpacing);
    }

    private static int maximumCompatible(
        List<BlockPos> positions,
        int index,
        List<BlockPos> selected,
        double minimumSpacing
    ) {
        if (index >= positions.size()) return selected.size();
        int best = maximumCompatible(
            positions, index + 1, selected, minimumSpacing
        );
        BlockPos candidate = positions.get(index);
        double minimumSquared = minimumSpacing * minimumSpacing;
        if (selected.stream().allMatch(position ->
            position.distSqr(candidate) >= minimumSquared)) {
            selected.add(candidate);
            best = Math.max(best, maximumCompatible(
                positions, index + 1, selected, minimumSpacing
            ));
            selected.removeLast();
        }
        return best;
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
