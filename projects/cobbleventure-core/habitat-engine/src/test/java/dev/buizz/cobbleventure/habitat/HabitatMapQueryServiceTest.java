package dev.buizz.cobbleventure.habitat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.buizz.cobbleventure.habitat.BiomeProfileCatalog.BiomeProfile;
import dev.buizz.cobbleventure.habitat.CobblemonSpawnRuleCatalog.CobblemonSpawnRule;
import dev.buizz.cobbleventure.habitat.HabitatMapPanel.SpawnEntry;
import dev.buizz.cobbleventure.habitat.PokemonHabitatCatalog.Habitats;
import dev.buizz.cobbleventure.habitat.PokemonHabitatCatalog.PokemonHabitat;
import dev.buizz.cobbleventure.habitat.PokemonHabitatCatalog.Preferences;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HabitatMapQueryServiceTest {
    @Test
    void buildsRightPanelFromCustomZoneAndOriginalCobblemonWeights() {
        PokemonHabitat bulbasaur = pokemon(1, "cobblemon:bulbasaur", "이상해씨", "rare");
        PokemonHabitat pidgey = pokemon(16, "cobblemon:pidgey", "구구", "common");
        HabitatIndex habitats = new HabitatIndex(
            new PokemonHabitatCatalog(1, List.of(bulbasaur, pidgey)),
            new BiomeProfileCatalog(1, List.of(profile()), List.of())
        );
        GeneratedHabitatZone zone = zone();
        WorldHabitatIndex world = new WorldHabitatIndex(habitats, List.of(zone));
        CobblemonSpawnRuleCatalog catalog = new CobblemonSpawnRuleCatalog(
            1,
            new CobblemonSpawnRuleCatalog.Source("cobblemon", "1.7.3", "spawn_pool_world", 2),
            Map.of(),
            List.of(
                rule("bulbasaur-1", "cobblemon:bulbasaur", 6.0, "5-32"),
                rule("pidgey-1", "cobblemon:pidgey", 4.0, "1-25")
            )
        );
        HabitatMapQueryService query = new HabitatMapQueryService(
            world, new CobblemonSpawnRuleIndex(catalog)
        );

        HabitatMapPanel panel = query.discoveredPanelFor(zone.locationId(), Set.of(zone.locationId()));
        SpawnEntry bulbasaurEntry = panel.pokemon().stream()
            .filter(entry -> entry.pokemonId().equals("cobblemon:bulbasaur"))
            .findFirst().orElseThrow();

        assertEquals(zone, panel.zone());
        assertTrue(bulbasaurEntry.naturalSpawnSupported());
        assertEquals(Set.of("5-32"), bulbasaurEntry.levelRanges());
        assertEquals(60.0, bulbasaurEntry.baseBucketSharePercent().get("common"));
        assertEquals(List.of(zone), query.visibleZones(Set.of(zone.locationId())));
        assertEquals(List.of(), query.visibleZones(Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> query.discoveredPanelFor(zone.locationId(), Set.of()));
    }

    private PokemonHabitat pokemon(int dex, String id, String name, String rarity) {
        return new PokemonHabitat(
            dex, id, id.substring(id.indexOf(':') + 1), Map.of("ko_kr", name), 1,
            List.of(),
            new Preferences("land", "wooded", "any", "any", "any", "any", rarity, "none"),
            new Habitats("forest", null, "high"),
            true
        );
    }

    private BiomeProfile profile() {
        return new BiomeProfile(
            "cobbleventure:biome_profile/forest", Map.of("ko_kr", "숲"), "forest",
            List.of("minecraft:forest"),
            new SpawnSettings(1, "any", "any", "any", "any", Set.of("common", "rare"), true),
            Set.of(), Set.of()
        );
    }

    private GeneratedHabitatZone zone() {
        return new GeneratedHabitatZone(
            "cobbleventure:location/starter/forest", "cobbleventure:settlement/starter",
            "forest", "minecraft:forest", "cobbleventure:biome_profile/forest",
            Map.of("ko_kr", "시작 마을 숲"), 100, 200, 96, null, Set.of()
        );
    }

    private CobblemonSpawnRule rule(String id, String species, double weight, String level) {
        return new CobblemonSpawnRule(
            "data/cobblemon/spawn_pool_world/" + id + ".json", id, species,
            species.substring(species.indexOf(':') + 1), true, "pokemon", "grounded",
            "common", level, weight, List.of("natural"),
            JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
            null, List.of(), JsonNodeFactory.instance.objectNode()
        );
    }
}
