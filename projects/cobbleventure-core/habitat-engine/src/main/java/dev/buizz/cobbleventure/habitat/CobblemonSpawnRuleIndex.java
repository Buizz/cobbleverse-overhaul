package dev.buizz.cobbleventure.habitat;

import dev.buizz.cobbleventure.habitat.CobblemonSpawnRuleCatalog.CobblemonSpawnRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CobblemonSpawnRuleIndex {
    private final Map<String, List<CobblemonSpawnRule>> rulesBySpecies;

    public CobblemonSpawnRuleIndex(CobblemonSpawnRuleCatalog catalog) {
        Map<String, List<CobblemonSpawnRule>> grouped = new LinkedHashMap<>();
        catalog.rules().stream()
            .filter(CobblemonSpawnRule::enabled)
            .forEach(rule -> grouped.computeIfAbsent(rule.speciesId(), ignored -> new java.util.ArrayList<>())
                .add(rule));
        grouped.replaceAll((ignored, rules) -> List.copyOf(rules));
        rulesBySpecies = Map.copyOf(grouped);
    }

    public List<CobblemonSpawnRule> rulesFor(String pokemonId) {
        return rulesBySpecies.getOrDefault(pokemonId, List.of());
    }
}
