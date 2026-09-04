package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DungeonChamberMazeTopologyTest {
    @Test
    void pokemonTowerFloorBuildsOneReachableSevenByFivePartitionMaze() {
        DungeonChamberMazeTopology.Plan plan =
            DungeonChamberMazeTopology.generate(7, 5, 0.0D, 151L);

        assertEquals(35, plan.cells().size());
        assertEquals(34, plan.passages().size());
        assertEquals(3, Set.of(
            plan.entryCell(), plan.bossCell(), plan.exitCell()
        ).size());
        assertEquals(
            plan.bossCell(), plan.criticalPath().get(plan.criticalPath().size() - 2)
        );
        assertEquals(plan.exitCell(), plan.criticalPath().getLast());
        assertEquals(35, reachableCellCount(plan));
        assertPartitionsMatchClosedCellBoundaries(plan);
    }

    @Test
    void chamberMazeIsDeterministicButSeedSensitive() {
        DungeonChamberMazeTopology.Plan first =
            DungeonChamberMazeTopology.generate(7, 5, 0.1D, 91L);
        DungeonChamberMazeTopology.Plan repeated =
            DungeonChamberMazeTopology.generate(7, 5, 0.1D, 91L);
        DungeonChamberMazeTopology.Plan different =
            DungeonChamberMazeTopology.generate(7, 5, 0.1D, 92L);

        assertEquals(first, repeated);
        assertNotEquals(first.passages(), different.passages());
    }

    @Test
    void rejectsAChamberTooSmallForEntryBossAndExit() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DungeonChamberMazeTopology.generate(2, 5, 0.0D, 1L)
        );
    }

    private static int reachableCellCount(DungeonChamberMazeTopology.Plan plan) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        visited.add(plan.entryCell());
        queue.add(plan.entryCell());
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            plan.passages().stream().filter(edge ->
                edge.first() == current || edge.second() == current
            ).map(edge -> edge.first() == current ? edge.second() : edge.first())
                .filter(visited::add).forEach(queue::addLast);
        }
        return visited.size();
    }

    private static void assertPartitionsMatchClosedCellBoundaries(
        DungeonChamberMazeTopology.Plan plan
    ) {
        for (int z = 0; z < plan.depth(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                int cell = z * plan.width() + x;
                if (x + 1 < plan.width()) {
                    boolean open = plan.passages().contains(
                        new DungeonChamberMazeTopology.Edge(cell, cell + 1)
                    );
                    boolean wall = plan.partitions().contains(
                        new DungeonChamberMazeTopology.Partition(
                            x + 1, z, x + 1, z + 1
                        )
                    );
                    assertTrue(open != wall);
                }
                if (z + 1 < plan.depth()) {
                    boolean open = plan.passages().contains(
                        new DungeonChamberMazeTopology.Edge(
                            cell, cell + plan.width()
                        )
                    );
                    boolean wall = plan.partitions().contains(
                        new DungeonChamberMazeTopology.Partition(
                            x, z + 1, x + 1, z + 1
                        )
                    );
                    assertTrue(open != wall);
                }
            }
        }
    }
}
