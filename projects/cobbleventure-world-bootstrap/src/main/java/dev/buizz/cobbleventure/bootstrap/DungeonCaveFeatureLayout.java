package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Assigns coordinate-free dungeon content to safe rooms carved by a cave plan. */
final class DungeonCaveFeatureLayout {
    private static final List<BlockPos> ROOM_OFFSETS = List.of(
        BlockPos.ZERO,
        new BlockPos(-3, 0, 0),
        new BlockPos(3, 0, 0),
        new BlockPos(0, 0, -3),
        new BlockPos(0, 0, 3)
    );

    private DungeonCaveFeatureLayout() {}

    static Map<DungeonPieceLayout.MarkerKey, BlockPos> assign(
        DungeonDefinition definition,
        List<BlockPos> mainRooms,
        List<BlockPos> branchRooms,
        long seed
    ) {
        List<BlockPos> mainSlots = slots(mainRooms);
        List<BlockPos> branchSlots = slots(branchRooms);
        if (mainSlots.isEmpty()) {
            throw new IllegalStateException(
                "Procedural cave has no content rooms: " + definition.id()
            );
        }
        shuffle(mainSlots, seed, "main");
        shuffle(branchSlots, seed, "branch");
        Set<BlockPos> used = new HashSet<>();
        Map<DungeonPieceLayout.MarkerKey, BlockPos> assigned = new LinkedHashMap<>();

        List<BlockPos> bossSlots = new ArrayList<>();
        for (int index = mainRooms.size() - 1; index >= 0; index--) {
            bossSlots.add(mainRooms.get(index));
        }
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            if (encounter.position() != null || !encounter.boss()) continue;
            put(
                assigned, used, "boss", encounter.id(), bossSlots,
                mainSlots, definition.id()
            );
        }
        for (DungeonDefinition.Encounter encounter : definition.encounters()) {
            if (encounter.position() != null || encounter.boss()) continue;
            put(
                assigned, used, "encounter", encounter.id(), mainSlots,
                branchSlots, definition.id()
            );
        }
        for (DungeonDefinition.Objective objective : definition.objectives()) {
            if (objective.position() != null) continue;
            put(
                assigned, used, "objective", objective.id(), branchSlots,
                mainSlots, definition.id()
            );
        }
        for (DungeonDefinition.LootContainer container
            : definition.loot().containers()) {
            if (container.position() != null) continue;
            put(
                assigned, used, "loot", container.id(), branchSlots,
                mainSlots, definition.id()
            );
        }
        for (DungeonDefinition.HealingStation station
            : definition.support().healingStations()) {
            if (station.position() != null) continue;
            put(
                assigned, used, "healing_station", station.id(), branchSlots,
                mainSlots, definition.id()
            );
        }
        if (definition.completion().returnTrigger().equals("clear_exit")
            && definition.completion().clearExitPosition() == null) {
            List<BlockPos> exitSlots = new ArrayList<>();
            for (int index = mainRooms.size() - 1; index >= 0; index--) {
                exitSlots.addAll(slots(List.of(mainRooms.get(index))));
            }
            put(
                assigned, used, "objective", "clear_exit", exitSlots,
                mainSlots, definition.id()
            );
        }
        return Map.copyOf(assigned);
    }

    private static List<BlockPos> slots(List<BlockPos> rooms) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos room : rooms) {
            for (BlockPos offset : ROOM_OFFSETS) result.add(room.offset(offset));
        }
        return result;
    }

    private static void shuffle(List<BlockPos> values, long seed, String salt) {
        Collections.shuffle(values, new Random(seed ^ (long) salt.hashCode() * 31L));
    }

    private static void put(
        Map<DungeonPieceLayout.MarkerKey, BlockPos> assigned,
        Set<BlockPos> used,
        String kind,
        String id,
        List<BlockPos> preferred,
        List<BlockPos> fallback,
        String dungeonId
    ) {
        BlockPos position = firstUnused(preferred, used);
        if (position == null) position = firstUnused(fallback, used);
        if (position == null) {
            throw new IllegalStateException(
                "Procedural cave has no available " + kind + " position: "
                    + dungeonId + " -> " + id
            );
        }
        used.add(position);
        assigned.put(new DungeonPieceLayout.MarkerKey(kind, id), position);
    }

    private static BlockPos firstUnused(List<BlockPos> values, Set<BlockPos> used) {
        return values.stream().filter(value -> !used.contains(value)).findFirst()
            .orElse(null);
    }
}
