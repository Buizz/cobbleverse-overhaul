package dev.buizz.cobbleventure.battleai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buizz.cobbleventure.ai.core.SharedProjectionDifferentialEvaluator;
import dev.buizz.cobbleventure.ai.core.SharedProjectionDifferentialResult;
import dev.buizz.cobbleventure.ai.core.SharedSearchFieldState;
import dev.buizz.cobbleventure.ai.core.SharedSearchPressure;
import dev.buizz.cobbleventure.ai.core.SharedSearchProjectionState;
import dev.buizz.cobbleventure.ai.core.SharedSearchTimedEffect;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 누적 Showdown/Cobblemon 프로토콜 로그를 체크포인트별 공통 투영과 비교한다. */
class ProjectionLogCorpusTest {
    private static final String ROOT = "/battle-ai/projection-log-corpus/";

    @Test
    void everyLongRunningProtocolCheckpointMatchesTheSharedProjectionContract() throws IOException {
        List<String> files = resourceLines(ROOT + "index.txt").stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertFalse(files.isEmpty(), "projection log corpus index must not be empty");

        int checkpoints = 0;
        for (String file : files) {
            List<String> cumulativeLog = new ArrayList<>();
            String checkpoint = null;
            Map<String, String> expected = new LinkedHashMap<>();
            boolean readingLog = false;
            for (String line : resourceLines(ROOT + file)) {
                if (line.equals("@@log")) {
                    if (checkpoint != null) {
                        assertCheckpoint(file, checkpoint, cumulativeLog, expected);
                        checkpoints++;
                        checkpoint = null;
                        expected = new LinkedHashMap<>();
                    }
                    readingLog = true;
                    continue;
                }
                if (line.startsWith("@@expect ")) {
                    if (checkpoint != null) {
                        assertCheckpoint(file, checkpoint, cumulativeLog, expected);
                        checkpoints++;
                        expected = new LinkedHashMap<>();
                    }
                    checkpoint = line.substring("@@expect ".length()).trim();
                    readingLog = false;
                    continue;
                }
                if (line.isBlank() || line.startsWith("#")) continue;
                if (readingLog) {
                    cumulativeLog.add(line);
                } else if (checkpoint != null) {
                    int separator = line.indexOf('=');
                    if (separator <= 0) throw new IllegalArgumentException(file + ": invalid expectation: " + line);
                    expected.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
                }
            }
            if (checkpoint != null) {
                assertCheckpoint(file, checkpoint, cumulativeLog, expected);
                checkpoints++;
            }
        }
        assertTrue(checkpoints >= 12, "long-running corpus should retain at least twelve checkpoints");
    }

    private static void assertCheckpoint(
            String file,
            String checkpoint,
            List<String> cumulativeLog,
            Map<String, String> values
    ) {
        SharedSearchProjectionState expected = expectedState(values);
        ShowdownBattleLogObservation observed = ShowdownBattleLogObservation.parse(cumulativeLog);
        SharedProjectionDifferentialResult result = SharedProjectionDifferentialEvaluator.INSTANCE.evaluate(
                expected, observed.projectionObservation(List.of("p1", "p2")));
        assertTrue(result.getMatches(), () -> file + "#" + checkpoint + " differences: " + result.getDifferences());
    }

    private static SharedSearchProjectionState expectedState(Map<String, String> values) {
        int turn = integer(values, "turn", 0);
        List<List<Integer>> hp = List.of(
                List.of(integer(values, "p1.hp", 100)),
                List.of(integer(values, "p2.hp", 100)));
        List<List<Integer>> maximumHp = List.of(
                List.of(integer(values, "p1.maxHp", 100)),
                List.of(integer(values, "p2.maxHp", 100)));
        List<List<Integer>> hazards = List.of(
                integers(values.getOrDefault("p1.hazards", "0,0,0,0"), 4),
                integers(values.getOrDefault("p2.hazards", "0,0,0,0"), 4));
        List<List<SharedSearchPressure>> pressures = List.of(
                List.of(pressure(values.get("p1.pressure"))),
                List.of(pressure(values.get("p2.pressure"))));
        List<List<List<Integer>>> ranks = List.of(
                List.of(integers(values.getOrDefault("p1.ranks", "0,0,0,0,0"), 5)),
                List.of(integers(values.getOrDefault("p2.ranks", "0,0,0,0,0"), 5)));
        List<List<Set<String>>> abilityStates = List.of(
                List.of(states(values.get("p1.adapters"))),
                List.of(states(values.get("p2.adapters"))));
        SharedSearchFieldState field = new SharedSearchFieldState(
                timed(values.get("field.weather")),
                timed(values.get("field.terrain")),
                timedMap(values.get("field.pseudo")));
        List<Map<String, SharedSearchTimedEffect>> sideConditions = List.of(
                timedMap(values.get("p1.conditions")),
                timedMap(values.get("p2.conditions")));
        return new SharedSearchProjectionState(
                turn, List.of(0, 0), hp, maximumHp,
                List.of(Boolean.parseBoolean(values.getOrDefault("p1.gimmick", "false")),
                        Boolean.parseBoolean(values.getOrDefault("p2.gimmick", "false"))),
                List.of(List.of(), List.of()), hazards, pressures, ranks,
                List.of(List.of(""), List.of("")), abilityStates, field, sideConditions,
                List.of(), List.of(), List.of());
    }

    private static SharedSearchPressure pressure(String value) {
        List<Integer> parts = integers(value == null ? "0,0,0,0,0" : value, 5);
        return new SharedSearchPressure(parts.get(0) != 0, parts.get(1), parts.get(2) != 0,
                parts.get(3), parts.get(4));
    }

    private static List<Integer> integers(String value, int size) {
        String[] parts = value.split(",", -1);
        if (parts.length != size) throw new IllegalArgumentException("expected " + size + " integers: " + value);
        List<Integer> result = new ArrayList<>(size);
        for (String part : parts) result.add(Integer.parseInt(part.trim()));
        return List.copyOf(result);
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback;
    }

    private static Set<String> states(String value) {
        if (value == null || value.isBlank() || value.equals("-")) return Set.of();
        return Set.of(value.split(","));
    }

    private static SharedSearchTimedEffect timed(String value) {
        if (value == null || value.isBlank() || value.equals("-")) return null;
        String[] parts = value.split(":", -1);
        return new SharedSearchTimedEffect(parts[0], Integer.parseInt(parts[1]),
                parts.length > 2 && Boolean.parseBoolean(parts[2]));
    }

    private static Map<String, SharedSearchTimedEffect> timedMap(String value) {
        if (value == null || value.isBlank() || value.equals("-")) return Map.of();
        Map<String, SharedSearchTimedEffect> result = new HashMap<>();
        for (String entry : value.split(",")) {
            SharedSearchTimedEffect effect = timed(entry.trim());
            result.put(effect.getId(), effect);
        }
        return Map.copyOf(result);
    }

    private static List<String> resourceLines(String path) throws IOException {
        InputStream stream = ProjectionLogCorpusTest.class.getResourceAsStream(path);
        if (stream == null) throw new IOException("missing projection log corpus resource: " + path);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }
}
