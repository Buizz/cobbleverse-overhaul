package dev.buizz.cobbleventure.adventure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class WildSpawnLevelingTest {
    @Test
    void filtersAuthoredAdditionsByDayAndNight() {
        var pidgey = ResourceLocation.parse("cobblemon:pidgey");
        var hoothoot = ResourceLocation.parse("cobblemon:hoothoot");
        var rule = new AdventureWorldContext.WildSpawnRule(
            false, Set.of(), List.of(
                new AdventureWorldContext.WildSpawnAddition(pidgey, false),
                new AdventureWorldContext.WildSpawnAddition(hoothoot, false)
            ), Map.of(), Map.of(pidgey, "day", hoothoot, "night"), true, 1.0D
        );

        assertEquals(List.of(pidgey), WildSpawnLeveling.activeAdditions(rule, true)
            .stream().map(AdventureWorldContext.WildSpawnAddition::species).toList());
        assertEquals(List.of(hoothoot), WildSpawnLeveling.activeAdditions(rule, false)
            .stream().map(AdventureWorldContext.WildSpawnAddition::species).toList());
    }

    @Test
    void routeOneLevelsMatchFireRedSlotsEvenWithHighWorldAverage() {
        var pidgey = ResourceLocation.parse("cobblemon:pidgey");
        var rattata = ResourceLocation.parse("cobblemon:rattata");
        var weights = Map.of(
            pidgey, Map.of(2, 10, 3, 35, 4, 4, 5, 1),
            rattata, Map.of(2, 10, 3, 35, 4, 5)
        );
        var rule = new AdventureWorldContext.WildSpawnRule(false, Set.of(), List.of(), Map.of(
            pidgey, new AdventureWorldContext.WildSpawnLevelRange(2, 5, weights.get(pidgey)),
            rattata, new AdventureWorldContext.WildSpawnLevelRange(2, 4, weights.get(rattata))
        ));
        for (var species : weights.keySet()) {
            Map<Integer, Integer> actual = new TreeMap<>();
            for (int roll = 0; roll < 50; roll++) {
                final int choice = roll;
                int level = WildSpawnLeveling.levelFor(bound -> {
                    assertEquals(50, bound);
                    return choice;
                }, species, rule, 80, 60);
                actual.merge(level, 1, Integer::sum);
            }
            assertEquals(weights.get(species), actual);
        }
    }

    @Test
    void legacyUniformOverrideAlsoWinsOverWorldAverage() {
        var species = ResourceLocation.parse("cobblemon:rattata");
        var rule = new AdventureWorldContext.WildSpawnRule(false, Set.of(), List.of(),
            Map.of(species, new AdventureWorldContext.WildSpawnLevelRange(2, 4)));
        assertEquals(2, WildSpawnLeveling.levelFor(bound -> 0, species, rule, 80, 60));
        assertEquals(4, WildSpawnLeveling.levelFor(bound -> bound - 1, species, rule, 80, 60));
        assertEquals(2, WildSpawnLeveling.levelFor(bound -> 0, species, null, 4, 60));
        assertEquals(6, WildSpawnLeveling.levelFor(bound -> bound - 1, species, null, 4, 60));
        assertEquals(60, WildSpawnLeveling.levelFor(bound -> { throw new AssertionError(); }, species, null, null, 60));
    }

    @Test
    void rangesClampAtPokemonLevelLimitsAndRejectInvalidWeights() {
        assertEquals(1, new AdventureWorldContext.WildSpawnLevelRange(-1, 3).sample(bound -> 0));
        assertEquals(100, new AdventureWorldContext.WildSpawnLevelRange(98, 102).sample(bound -> bound - 1));
        assertThrows(IllegalArgumentException.class,
            () -> new AdventureWorldContext.WildSpawnLevelRange(2, 4, Map.of(5, 1)));
        assertThrows(IllegalArgumentException.class,
            () -> new AdventureWorldContext.WildSpawnLevelRange(2, 4, Map.of(3, 0)));
    }

    @Test
    void naturalWaterSpawnsUseTheSurfEncounterPool() {
        assertEquals(
            AdventureWorldContext.WildEncounterMethod.SURF,
            WildSpawnLeveling.naturalEncounterMethod(true)
        );
        assertEquals(
            AdventureWorldContext.WildEncounterMethod.LAND,
            WildSpawnLeveling.naturalEncounterMethod(false)
        );
    }
}
