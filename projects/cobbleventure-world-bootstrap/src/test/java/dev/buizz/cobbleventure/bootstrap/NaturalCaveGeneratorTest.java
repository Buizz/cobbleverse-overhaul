package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class NaturalCaveGeneratorTest {
    @Test
    void plansDeterministicDungeonInstanceInsideSlotBounds() {
        BlockPos origin = new BlockPos(32768, 80, 0);
        BlockPos bounds = new BlockPos(160, 48, 160);

        NaturalCaveGenerator.InstancePlan first = NaturalCaveGenerator.planInstance(
            "cobbleventure:dungeon/test_cave", 7351L, origin, bounds, 7, 3, 0.35D
        );
        NaturalCaveGenerator.InstancePlan repeated = NaturalCaveGenerator.planInstance(
            "cobbleventure:dungeon/test_cave", 7351L, origin, bounds, 7, 3, 0.35D
        );

        assertEquals(first, repeated);
        assertEquals(2, first.entrances().size());
        assertEquals(8, first.settings().manualLayout().anchors().size());
        assertTrue(first.settings().manualLayout().connections().size() >= 9);
        assertInside(first.entryPosition(), bounds);
        assertInside(first.exitPosition(), bounds);
        first.settings().manualLayout().anchors().forEach(anchor -> assertInside(
            new BlockPos(anchor.x(), anchor.y(), anchor.z()).subtract(origin), bounds
        ));
    }

    @Test
    void rejectsSlotThatCannotContainCurrentCaveShape() {
        assertThrows(IllegalArgumentException.class, () ->
            NaturalCaveGenerator.planInstance(
                "cobbleventure:dungeon/test_cave", 1L,
                new BlockPos(32768, 80, 0), new BlockPos(96, 32, 96),
                5, 1, 0.0D
            )
        );
    }

    @Test
    void mazeAndRoomCorridorModesProduceDistinctCavePlans() {
        BlockPos origin = new BlockPos(32768, 80, 0);
        BlockPos bounds = new BlockPos(160, 48, 160);

        NaturalCaveGenerator.InstancePlan maze = NaturalCaveGenerator.planInstance(
            "cobbleventure:dungeon/test_cave", 7351L, origin, bounds,
            "maze", 7, 3, 0.35D
        );
        NaturalCaveGenerator.InstancePlan rooms = NaturalCaveGenerator.planInstance(
            "cobbleventure:dungeon/test_cave", 7351L, origin, bounds,
            "rooms_and_corridors", 7, 3, 0.35D
        );

        assertNotEquals(
            maze.settings().manualLayout(), rooms.settings().manualLayout()
        );
        assertEquals(3, maze.settings().manualLayout().connections().getFirst().width());
        assertEquals(5, rooms.settings().manualLayout().connections().getFirst().width());
        assertTrue(maze.settings().manualLayout().anchors().getFirst().radiusX()
            < rooms.settings().manualLayout().anchors().getFirst().radiusX());
    }

    @Test
    void branchDepthCreatesConnectedMultiRoomSidePaths() {
        BlockPos origin = new BlockPos(32768, 80, 0);
        BlockPos bounds = new BlockPos(160, 48, 160);

        NaturalCaveGenerator.InstancePlan plan = NaturalCaveGenerator.planInstance(
            "cobbleventure:dungeon/test_cave", 7351L, origin, bounds,
            "critical_path_branches", 7, 2, 3, 0.0D
        );

        assertEquals(6, plan.branchRoomPositions().size());
        assertEquals(11, plan.settings().manualLayout().anchors().size());
        assertEquals(12, plan.settings().manualLayout().connections().size());
    }

    private static void assertInside(BlockPos position, BlockPos bounds) {
        assertTrue(position.getX() >= 0 && position.getX() < bounds.getX());
        assertTrue(position.getY() >= 0 && position.getY() < bounds.getY());
        assertTrue(position.getZ() >= 0 && position.getZ() < bounds.getZ());
    }
}
