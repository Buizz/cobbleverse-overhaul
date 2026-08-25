package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DungeonEncounterRequirementsTest {
    @Test
    void acceptsBranchesThatJoinAtTheBoss() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("west", List.of());
        graph.put("east", List.of());
        graph.put("boss", List.of("west", "east"));

        assertDoesNotThrow(() ->
            DungeonEncounterRequirements.validate("cobbleventure:test", graph)
        );
    }

    @Test
    void rejectsMissingPrerequisites() {
        assertThrows(IllegalStateException.class, () ->
            DungeonEncounterRequirements.validate(
                "cobbleventure:test", Map.of("boss", List.of("missing"))
            )
        );
    }

    @Test
    void rejectsDependencyCycles() {
        assertThrows(IllegalStateException.class, () ->
            DungeonEncounterRequirements.validate(
                "cobbleventure:test",
                Map.of("first", List.of("second"), "second", List.of("first"))
            )
        );
    }
}
