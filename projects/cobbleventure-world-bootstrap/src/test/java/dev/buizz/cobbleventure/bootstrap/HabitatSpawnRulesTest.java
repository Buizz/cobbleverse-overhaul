package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.buizz.cobbleventure.adventure.AdventureWorldContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class HabitatSpawnRulesTest {
    private static final ResourceLocation PIDGEY = id("pidgey");
    private static final ResourceLocation RATTATA = id("rattata");
    private static final ResourceLocation EKANS = id("ekans");

    @Test
    void routeWithDisabledBiomeInheritanceUsesOnlyAuthoredAdditions() {
        AdventureWorldContext.WildSpawnRule rule = rule(
            false, Set.of(), List.of(addition(EKANS))
        );

        assertEquals(
            Set.of(EKANS),
            HabitatSpawnRules.applyRouteRule(Set.of(PIDGEY, RATTATA), rule)
        );
    }

    @Test
    void inheritedRouteRemovesExclusionsAndAddsAuthoredSpecies() {
        AdventureWorldContext.WildSpawnRule rule = rule(
            true, Set.of(RATTATA), List.of(addition(EKANS))
        );

        assertEquals(
            Set.of(PIDGEY, EKANS),
            HabitatSpawnRules.applyRouteRule(Set.of(PIDGEY, RATTATA), rule)
        );
    }

    @Test
    void exclusiveRoutePublishesAuthoredWeightsForInGameHabitatLists() {
        AdventureWorldContext.WildSpawnRule rule = rule(
            false, Set.of(), List.of(
                new AdventureWorldContext.WildSpawnAddition(EKANS, false, 3),
                new AdventureWorldContext.WildSpawnAddition(RATTATA, false, 2)
            )
        );

        assertEquals(
            Map.of(EKANS, 3, RATTATA, 2),
            HabitatSpawnRules.exclusiveRouteWeights(rule)
        );
    }

    private static AdventureWorldContext.WildSpawnRule rule(
        boolean inheritBiome, Set<ResourceLocation> excluded,
        List<AdventureWorldContext.WildSpawnAddition> additions
    ) {
        return new AdventureWorldContext.WildSpawnRule(
            inheritBiome, excluded, additions, Map.of(), true, 1.0D
        );
    }

    private static AdventureWorldContext.WildSpawnAddition addition(
        ResourceLocation species
    ) {
        return new AdventureWorldContext.WildSpawnAddition(species, false, 1);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobblemon", path);
    }
}
