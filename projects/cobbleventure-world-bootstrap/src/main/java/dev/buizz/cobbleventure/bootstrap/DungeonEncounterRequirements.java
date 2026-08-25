package dev.buizz.cobbleventure.bootstrap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the acyclic prerequisite graph shared by authored dungeon encounters. */
final class DungeonEncounterRequirements {
    private DungeonEncounterRequirements() {}

    static void validate(String dungeonId, Map<String, List<String>> requirements) {
        for (Map.Entry<String, List<String>> encounter : requirements.entrySet()) {
            for (String required : encounter.getValue()) {
                if (required.equals(encounter.getKey())
                    || !requirements.containsKey(required)) {
                    throw new IllegalStateException(
                        "Invalid dungeon encounter dependency: " + dungeonId + " -> "
                            + encounter.getKey() + " -> " + required
                    );
                }
            }
        }
        Set<String> resolved = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, List<String>> encounter : requirements.entrySet()) {
                if (!resolved.contains(encounter.getKey())
                    && resolved.containsAll(encounter.getValue())) {
                    resolved.add(encounter.getKey());
                    changed = true;
                }
            }
        } while (changed);
        if (resolved.size() != requirements.size()) {
            throw new IllegalStateException(
                "Dungeon encounter dependency cycle: " + dungeonId
            );
        }
    }
}
