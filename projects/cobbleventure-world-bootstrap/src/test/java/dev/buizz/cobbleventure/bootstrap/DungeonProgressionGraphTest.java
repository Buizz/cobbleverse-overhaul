package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DungeonProgressionGraphTest {
    @Test
    void linearProgressionCreatesOneCriticalRoute() {
        DungeonProgressionGraph graph = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("linear", 7, 0, 1, 1),
            91L
        );

        assertEquals(7, graph.nodes().size());
        assertEquals(6, graph.edges().size());
        assertEquals(List.of("start", "traversal", "traversal", "traversal",
            "traversal", "boss", "exit"),
            graph.criticalPath().stream()
                .map(DungeonProgressionGraph.Node::role).toList());
        assertFalse(graph.hasCycle());
    }

    @Test
    void branchingProgressionAddsAuthoredRewardBranches() {
        DungeonProgressionGraph graph = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("branching", 6, 3, 2, 1),
            8128L
        );

        assertEquals(12, graph.nodes().size());
        assertEquals(3, graph.nodes().stream()
            .filter(node -> node.role().equals("reward")).count());
        assertEquals(6, graph.criticalPath().size());
        assertFalse(graph.hasCycle());
    }

    @Test
    void cyclicProgressionCreatesDetoursThatRejoinTheRoute() {
        DungeonProgressionGraph first = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("cyclic", 8, 2, 2, 1),
            44L
        );
        DungeonProgressionGraph repeated = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("cyclic", 8, 2, 2, 1),
            44L
        );

        assertEquals(first, repeated);
        assertEquals(2, first.edges().stream()
            .filter(edge -> edge.kind().equals("rejoin")).count());
        assertTrue(first.hasCycle());
    }

    @Test
    void parallelGateRequiresEveryObjectiveBeforeBoss() {
        DungeonProgressionGraph graph = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("parallel_gate", 4, 0, 2, 3),
            1L
        );

        DungeonProgressionGraph.Node boss = graph.nodes().stream()
            .filter(node -> node.role().equals("boss"))
            .findFirst().orElseThrow();
        assertEquals(Set.of("target_1", "target_2", "target_3"), boss.requires());
        assertEquals(3, graph.nodes().stream()
            .filter(node -> node.role().equals("objective")).count());
        assertFalse(graph.hasCycle());
        assertEquals(5, graph.adjacentTo(1).size());
    }

    @Test
    void validationRejectsDisconnectedOrUnsolvableGraphs() {
        assertThrows(IllegalArgumentException.class, () ->
            new DungeonProgressionGraph("broken", List.of(
                new DungeonProgressionGraph.Node(0, "start", true, Set.of(), Set.of()),
                new DungeonProgressionGraph.Node(1, "boss", true, Set.of("missing"), Set.of()),
                new DungeonProgressionGraph.Node(2, "exit", true, Set.of(), Set.of())
            ), List.of(
                new DungeonProgressionGraph.Edge(0, 1, "critical"),
                new DungeonProgressionGraph.Edge(1, 2, "critical")
            ))
        );
    }

    @Test
    void keyLockPlacesItsKeyOnAnAccessibleSideBranch() {
        DungeonProgressionGraph graph = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("key_lock", 6, 1, 2, 1),
            10L
        );

        assertTrue(graph.nodes().stream().anyMatch(node ->
            node.grants().contains("key_1") && !node.criticalPath()
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
            node.requires().contains("key_1") && node.criticalPath()
        ));
    }

    @Test
    void shortcutRemainsLockedUntilTheLongRouteWasTraversed() {
        DungeonProgressionGraph graph = DungeonProgressionGraph.generate(
            new DungeonProgressionGraph.Settings("shortcut_loop", 8, 1, 1, 1),
            10L
        );

        assertTrue(graph.hasCycle());
        assertTrue(graph.nodes().stream().anyMatch(node ->
            node.requires().contains("shortcut_1") && !node.criticalPath()
        ));
        assertTrue(graph.criticalPath().stream().anyMatch(node ->
            node.grants().contains("shortcut_1")
        ));
    }
}
