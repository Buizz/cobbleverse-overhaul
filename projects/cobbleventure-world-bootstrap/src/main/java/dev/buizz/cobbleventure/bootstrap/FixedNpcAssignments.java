package dev.buizz.cobbleventure.bootstrap;

import java.util.Map;

/** Resolves exact or trailing-wildcard assignments for authored NPC anchor IDs. */
final class FixedNpcAssignments {
    private FixedNpcAssignments() {
    }

    static String match(Map<String, String> assignments, String anchorId) {
        String exact = assignments.get(anchorId);
        if (exact != null) {
            return exact;
        }
        String matched = null;
        int longestPrefix = -1;
        for (Map.Entry<String, String> assignment : assignments.entrySet()) {
            String pattern = assignment.getKey();
            if (!pattern.endsWith("*") || pattern.length() == 1) {
                continue;
            }
            String prefix = pattern.substring(0, pattern.length() - 1);
            if (anchorId.startsWith(prefix) && prefix.length() > longestPrefix) {
                matched = assignment.getValue();
                longestPrefix = prefix.length();
            }
        }
        return matched;
    }
}
