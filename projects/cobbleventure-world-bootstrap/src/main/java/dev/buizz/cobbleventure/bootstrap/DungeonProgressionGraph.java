package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Geometry-independent dungeon progression plan.
 *
 * <p>The graph describes what the player must be able to visit and which facts
 * must be earned before a gated node is entered. A spatial layout generator is
 * responsible for assigning these nodes to authored NBT chambers later.</p>
 */
record DungeonProgressionGraph(
    String pattern,
    List<Node> nodes,
    List<Edge> edges
) {
    static final String START = "start";
    static final String TRAVERSAL = "traversal";
    static final String OBJECTIVE = "objective";
    static final String REWARD = "reward";
    static final String BOSS = "boss";
    static final String EXIT = "exit";

    DungeonProgressionGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        validate(pattern, nodes, edges);
    }

    static DungeonProgressionGraph generate(Settings settings, long seed) {
        settings.validate();
        return switch (settings.pattern()) {
            case "linear" -> linear(settings);
            case "branching" -> branching(settings, seed);
            case "cyclic" -> cyclic(settings, seed);
            case "parallel_gate" -> parallelGate(settings);
            default -> throw new IllegalArgumentException(
                "Unsupported dungeon progression pattern: " + settings.pattern()
            );
        };
    }

    private static DungeonProgressionGraph linear(Settings settings) {
        Builder builder = new Builder("linear");
        addCriticalRoute(builder, settings.criticalPathNodes());
        return builder.build();
    }

    private static DungeonProgressionGraph branching(Settings settings, long seed) {
        Builder builder = new Builder("branching");
        List<Integer> critical = addCriticalRoute(
            builder, settings.criticalPathNodes()
        );
        Random random = new Random(seed);
        for (int branch = 0; branch < settings.branchCount(); branch++) {
            int host = critical.get(random.nextInt(critical.size() - 2));
            int previous = host;
            for (int depth = 0; depth < settings.branchDepth(); depth++) {
                String role = depth == settings.branchDepth() - 1
                    ? REWARD : TRAVERSAL;
                int node = builder.add(role, false, Set.of(), Set.of());
                builder.connect(previous, node, "branch");
                previous = node;
            }
        }
        return builder.build();
    }

    private static DungeonProgressionGraph cyclic(Settings settings, long seed) {
        Builder builder = new Builder("cyclic");
        List<Integer> critical = addCriticalRoute(
            builder, settings.criticalPathNodes()
        );
        Random random = new Random(seed);
        for (int cycle = 0; cycle < settings.branchCount(); cycle++) {
            int firstMaximum = Math.max(1, critical.size() - 3);
            int firstIndex = random.nextInt(firstMaximum);
            int lastIndex = firstIndex + 2 + random.nextInt(
                critical.size() - firstIndex - 2
            );
            int previous = critical.get(firstIndex);
            for (int depth = 0; depth < settings.branchDepth(); depth++) {
                int node = builder.add(TRAVERSAL, false, Set.of(), Set.of());
                builder.connect(previous, node, "detour");
                previous = node;
            }
            builder.connect(previous, critical.get(lastIndex), "rejoin");
        }
        return builder.build();
    }

    private static DungeonProgressionGraph parallelGate(Settings settings) {
        Builder builder = new Builder("parallel_gate");
        int start = builder.add(START, true, Set.of(), Set.of());
        int staging = builder.add(TRAVERSAL, true, Set.of(), Set.of());
        builder.connect(start, staging, "critical");

        Set<String> required = new LinkedHashSet<>();
        for (int target = 0; target < settings.requiredTargets(); target++) {
            String flag = "target_" + (target + 1);
            required.add(flag);
            int previous = staging;
            for (int depth = 0; depth < settings.branchDepth(); depth++) {
                boolean terminal = depth == settings.branchDepth() - 1;
                int node = builder.add(
                    terminal ? OBJECTIVE : TRAVERSAL,
                    false,
                    Set.of(),
                    terminal ? Set.of(flag) : Set.of()
                );
                builder.connect(previous, node, "objective_branch");
                previous = node;
            }
        }
        int boss = builder.add(BOSS, true, Set.copyOf(required), Set.of());
        int exit = builder.add(EXIT, true, Set.of(), Set.of());
        builder.connect(staging, boss, "gated");
        builder.connect(boss, exit, "critical");
        return builder.build();
    }

    private static List<Integer> addCriticalRoute(Builder builder, int count) {
        List<Integer> critical = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String role = index == 0 ? START
                : index == count - 2 ? BOSS
                : index == count - 1 ? EXIT : TRAVERSAL;
            int node = builder.add(role, true, Set.of(), Set.of());
            if (!critical.isEmpty()) {
                builder.connect(critical.getLast(), node, "critical");
            }
            critical.add(node);
        }
        return List.copyOf(critical);
    }

    List<Node> criticalPath() {
        return nodes.stream().filter(Node::criticalPath).toList();
    }

    boolean hasCycle() {
        if (nodes.isEmpty()) return false;
        Set<Integer> visited = new HashSet<>();
        return hasCycle(nodes.getFirst().index(), -1, visited);
    }

    private boolean hasCycle(int current, int parent, Set<Integer> visited) {
        if (!visited.add(current)) return true;
        for (int adjacent : adjacentTo(current)) {
            if (adjacent == parent) continue;
            if (hasCycle(adjacent, current, visited)) return true;
        }
        return false;
    }

    Set<Integer> adjacentTo(int node) {
        Set<Integer> adjacent = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (edge.from() == node) adjacent.add(edge.to());
            if (edge.to() == node) adjacent.add(edge.from());
        }
        return Set.copyOf(adjacent);
    }

    private static void validate(
        String pattern, List<Node> nodes, List<Edge> edges
    ) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Dungeon progression graph is empty");
        }
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).index() != index) {
                throw new IllegalArgumentException(
                    "Dungeon progression node indices must be contiguous"
                );
            }
        }
        requireSingleRole(nodes, START);
        requireSingleRole(nodes, BOSS);
        requireSingleRole(nodes, EXIT);

        Set<String> edgeKeys = new HashSet<>();
        for (Edge edge : edges) {
            if (edge.from() < 0 || edge.from() >= nodes.size()
                || edge.to() < 0 || edge.to() >= nodes.size()
                || edge.from() == edge.to()) {
                throw new IllegalArgumentException(
                    "Invalid dungeon progression edge: " + edge
                );
            }
            int minimum = Math.min(edge.from(), edge.to());
            int maximum = Math.max(edge.from(), edge.to());
            if (!edgeKeys.add(minimum + ":" + maximum)) {
                throw new IllegalArgumentException(
                    "Duplicate dungeon progression edge: " + edge
                );
            }
        }
        ensureConnected(nodes, edges);
        ensureSolvable(pattern, nodes, edges);
    }

    private static void requireSingleRole(List<Node> nodes, String role) {
        long count = nodes.stream().filter(node -> node.role().equals(role)).count();
        if (count != 1) {
            throw new IllegalArgumentException(
                "Dungeon progression requires exactly one " + role + " node"
            );
        }
    }

    private static void ensureConnected(List<Node> nodes, List<Edge> edges) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(nodes.stream().filter(node -> node.role().equals(START))
            .findFirst().orElseThrow().index());
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (Edge edge : edges) {
                if (edge.from() == current) pending.add(edge.to());
                if (edge.to() == current) pending.add(edge.from());
            }
        }
        if (visited.size() != nodes.size()) {
            throw new IllegalArgumentException(
                "Dungeon progression graph contains unreachable nodes"
            );
        }
    }

    private static void ensureSolvable(
        String pattern, List<Node> nodes, List<Edge> edges
    ) {
        Node start = nodes.stream().filter(node -> node.role().equals(START))
            .findFirst().orElseThrow();
        Set<Integer> reachable = new HashSet<>();
        Set<String> flags = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(start.index());
            while (!pending.isEmpty()) {
                int current = pending.removeFirst();
                Node node = nodes.get(current);
                if (!flags.containsAll(node.requires()) || !reachable.add(current)) {
                    continue;
                }
                if (flags.addAll(node.grants())) changed = true;
                for (Edge edge : edges) {
                    if (edge.from() == current) pending.add(edge.to());
                    if (edge.to() == current) pending.add(edge.from());
                }
            }
            if (changed) reachable.clear();
        } while (changed);

        boolean exitReachable = nodes.stream()
            .filter(node -> node.role().equals(EXIT))
            .allMatch(node -> reachable.contains(node.index()));
        if (!exitReachable) {
            throw new IllegalArgumentException(
                "Dungeon progression pattern is not solvable: " + pattern
            );
        }
    }

    record Node(
        int index,
        String role,
        boolean criticalPath,
        Set<String> requires,
        Set<String> grants
    ) {
        Node {
            requires = Set.copyOf(requires);
            grants = Set.copyOf(grants);
        }
    }

    record Edge(int from, int to, String kind) {}

    record Settings(
        String pattern,
        int criticalPathNodes,
        int branchCount,
        int branchDepth,
        int requiredTargets
    ) {
        void validate() {
            if (!Set.of("linear", "branching", "cyclic", "parallel_gate")
                .contains(pattern)) {
                throw new IllegalArgumentException(
                    "Unsupported dungeon progression pattern: " + pattern
                );
            }
            if (criticalPathNodes < 3) {
                throw new IllegalArgumentException(
                    "Dungeon critical path requires at least three nodes"
                );
            }
            if (branchCount < 0 || branchDepth < 1 || requiredTargets < 1) {
                throw new IllegalArgumentException(
                    "Invalid dungeon progression counts"
                );
            }
            if (pattern.equals("branching") && branchCount < 1) {
                throw new IllegalArgumentException(
                    "Branching progression requires at least one branch"
                );
            }
            if (pattern.equals("cyclic") && branchCount < 1) {
                throw new IllegalArgumentException(
                    "Cyclic progression requires at least one cycle"
                );
            }
        }
    }

    private static final class Builder {
        private final String pattern;
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();

        private Builder(String pattern) {
            this.pattern = pattern;
        }

        private int add(
            String role,
            boolean criticalPath,
            Set<String> requires,
            Set<String> grants
        ) {
            int index = nodes.size();
            nodes.add(new Node(index, role, criticalPath, requires, grants));
            return index;
        }

        private void connect(int from, int to, String kind) {
            edges.add(new Edge(from, to, kind));
        }

        private DungeonProgressionGraph build() {
            return new DungeonProgressionGraph(pattern, nodes, edges);
        }
    }
}
