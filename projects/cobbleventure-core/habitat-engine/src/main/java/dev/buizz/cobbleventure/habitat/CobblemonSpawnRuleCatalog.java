package dev.buizz.cobbleventure.habitat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record CobblemonSpawnRuleCatalog(
    int schemaVersion,
    Source source,
    Map<String, Object> summary,
    List<CobblemonSpawnRule> rules
) {
    public CobblemonSpawnRuleCatalog {
        summary = summary == null ? Map.of() : Map.copyOf(summary);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public record Source(String mod, String version, String spawnPool, int resourceFiles) {
    }

    public record CobblemonSpawnRule(
        String sourceResource,
        String ruleId,
        String speciesId,
        String pokemonExpression,
        boolean enabled,
        String type,
        String spawnablePositionType,
        String bucket,
        String level,
        double weight,
        List<String> presets,
        JsonNode condition,
        JsonNode anticondition,
        JsonNode weightMultiplier,
        List<JsonNode> weightMultipliers,
        JsonNode raw
    ) {
        public CobblemonSpawnRule {
            presets = presets == null ? List.of() : List.copyOf(presets);
            weightMultipliers = weightMultipliers == null ? List.of() : List.copyOf(weightMultipliers);
        }
    }
}
