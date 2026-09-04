package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Builds the walk graph and partition lines inside one reserved chamber.
 * Construction code may render the partitions as tombstones, shelves or walls
 * without changing the route topology.
 */
final class DungeonChamberMazeTopology {
    private DungeonChamberMazeTopology() {}

    static Plan generate(int width, int depth, double loopChance, long seed) {
        if (width < 3 || depth < 3 || width > 64 || depth > 64
            || loopChance < 0.0D || loopChance > 1.0D) {
            throw new IllegalArgumentException("Invalid chamber maze settings");
        }
        Random random = new Random(seed);
        int cellCount = width * depth;
        Set<Edge> passages = new LinkedHashSet<>();
        boolean[] visited = new boolean[cellCount];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        visited[0] = true;
        stack.push(0);
        while (!stack.isEmpty()) {
            int current = stack.peek();
            List<Integer> candidates = neighbors(current, width, depth).stream()
                .filter(candidate -> !visited[candidate]).collect(
                    java.util.stream.Collectors.toCollection(ArrayList::new)
                );
            if (candidates.isEmpty()) {
                stack.pop();
                continue;
            }
            Collections.shuffle(candidates, random);
            int next = candidates.getFirst();
            passages.add(Edge.of(current, next));
            visited[next] = true;
            stack.push(next);
        }
        for (int cell = 0; cell < cellCount; cell++) {
            for (int neighbor : neighbors(cell, width, depth)) {
                Edge edge = Edge.of(cell, neighbor);
                if (cell < neighbor && !passages.contains(edge)
                    && random.nextDouble() < loopChance) {
                    passages.add(edge);
                }
            }
        }

        List<Integer> criticalPath = longestRouteFromEntry(
            cellCount, passages, 0
        );
        if (criticalPath.size() < 3) {
            throw new IllegalStateException("Chamber maze has no boss route");
        }
        int exit = criticalPath.getLast();
        int boss = criticalPath.get(criticalPath.size() - 2);
        List<Cell> cells = new ArrayList<>(cellCount);
        Set<Integer> critical = new HashSet<>(criticalPath);
        for (int index = 0; index < cellCount; index++) {
            String role = index == 0 ? "entry"
                : index == boss ? "boss" : index == exit ? "exit"
                : critical.contains(index) ? "critical" : "branch";
            cells.add(new Cell(index, index % width, index / width, role));
        }
        return new Plan(
            width, depth, List.copyOf(cells), Set.copyOf(passages),
            partitions(width, depth, passages), List.copyOf(criticalPath),
            0, boss, exit
        );
    }

    private static List<Integer> longestRouteFromEntry(
        int cellCount, Set<Edge> passages, int entry
    ) {
        Map<Integer, List<Integer>> graph = graph(cellCount, passages);
        int[] parent = new int[cellCount];
        int[] distance = new int[cellCount];
        java.util.Arrays.fill(parent, -1);
        java.util.Arrays.fill(distance, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        distance[entry] = 0;
        queue.add(entry);
        int farthest = entry;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (distance[current] > distance[farthest]) farthest = current;
            for (int next : graph.get(current)) {
                if (distance[next] >= 0) continue;
                distance[next] = distance[current] + 1;
                parent[next] = current;
                queue.addLast(next);
            }
        }
        List<Integer> reversed = new ArrayList<>();
        for (int current = farthest; current >= 0; current = parent[current]) {
            reversed.add(current);
            if (current == entry) break;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static Map<Integer, List<Integer>> graph(
        int cellCount, Set<Edge> passages
    ) {
        Map<Integer, List<Integer>> graph = new HashMap<>();
        for (int cell = 0; cell < cellCount; cell++) {
            graph.put(cell, new ArrayList<>());
        }
        for (Edge edge : passages) {
            graph.get(edge.first()).add(edge.second());
            graph.get(edge.second()).add(edge.first());
        }
        return graph;
    }

    private static List<Integer> neighbors(int cell, int width, int depth) {
        int x = cell % width;
        int z = cell / width;
        List<Integer> result = new ArrayList<>(4);
        if (x > 0) result.add(cell - 1);
        if (x + 1 < width) result.add(cell + 1);
        if (z > 0) result.add(cell - width);
        if (z + 1 < depth) result.add(cell + width);
        return result;
    }

    private static Set<Partition> partitions(
        int width, int depth, Set<Edge> passages
    ) {
        Set<Partition> result = new LinkedHashSet<>();
        for (int x = 0; x < width; x++) {
            result.add(new Partition(x, 0, x + 1, 0));
            result.add(new Partition(x, depth, x + 1, depth));
        }
        for (int z = 0; z < depth; z++) {
            result.add(new Partition(0, z, 0, z + 1));
            result.add(new Partition(width, z, width, z + 1));
        }
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int cell = z * width + x;
                if (x + 1 < width
                    && !passages.contains(Edge.of(cell, cell + 1))) {
                    result.add(new Partition(x + 1, z, x + 1, z + 1));
                }
                if (z + 1 < depth
                    && !passages.contains(Edge.of(cell, cell + width))) {
                    result.add(new Partition(x, z + 1, x + 1, z + 1));
                }
            }
        }
        return Set.copyOf(result);
    }

    record Plan(
        int width,
        int depth,
        List<Cell> cells,
        Set<Edge> passages,
        Set<Partition> partitions,
        List<Integer> criticalPath,
        int entryCell,
        int bossCell,
        int exitCell
    ) {}

    record Cell(int index, int x, int z, String role) {}

    record Edge(int first, int second) {
        private static Edge of(int first, int second) {
            return first < second ? new Edge(first, second) : new Edge(second, first);
        }
    }

    record Partition(int x1, int z1, int x2, int z2) {}
}
