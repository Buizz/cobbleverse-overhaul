package dev.buizz.cobbleventure.habitat;

import dev.buizz.cobbleventure.habitat.EncounterCandidate.MatchReason;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHabitatIndexTest {
    private static final Path CONTENT = Path.of(
        System.getProperty("cobbleventure.contentRoot"), "catalogs"
    );

    @Test
    void loadsCanonicalCatalogsAndFindsPokemonOnlyInGeneratedWorldLocations() throws Exception {
        HabitatCatalogLoader loader = new HabitatCatalogLoader();
        HabitatIndex habitats = new HabitatIndex(
            loader.loadPokemon(CONTENT.resolve("pokemon-habitats.json")),
            loader.loadBiomes(CONTENT.resolve("biome-profiles.json"))
        );
        GeneratedHabitatZone forest = new GeneratedHabitatZone(
            "cobbleventure:location/starter_town/outer_forest",
            "cobbleventure:settlement/starter_town",
            "outer_forest",
            "minecraft:forest",
            "cobbleventure:biome_profile/forest",
            Map.of("ko_kr", "시작 마을 외곽 숲"),
            720,
            -390,
            96,
            new SpawnSettings(
                1, "temperate", "humid", "any", "any",
                Set.of("common", "medium", "uncommon", "rare"), true
            ),
            Set.of()
        );
        WorldHabitatIndex world = new WorldHabitatIndex(habitats, List.of(forest));

        List<PokemonLocation> bulbasaurLocations = world.locationsFor("cobblemon:bulbasaur");

        assertEquals(1, bulbasaurLocations.size());
        assertEquals(forest.locationId(), bulbasaurLocations.getFirst().locationId());
        assertEquals(MatchReason.PRIMARY_HABITAT, bulbasaurLocations.getFirst().matchReason());
        assertTrue(world.encountersAt(forest.locationId()).stream()
            .anyMatch(candidate -> candidate.pokemonId().equals("cobblemon:bulbasaur")));
        assertFalse(world.encountersAt(forest.locationId()).stream()
            .anyMatch(candidate -> candidate.pokemonId().equals("cobblemon:charmander")));
    }

    @Test
    void returnsNoLocationWhenAValidPokemonDoesNotExistInTheGeneratedMap() throws Exception {
        HabitatCatalogLoader loader = new HabitatCatalogLoader();
        HabitatIndex habitats = new HabitatIndex(
            loader.loadPokemon(CONTENT.resolve("pokemon-habitats.json")),
            loader.loadBiomes(CONTENT.resolve("biome-profiles.json"))
        );
        WorldHabitatIndex emptyWorld = new WorldHabitatIndex(habitats, List.of());

        assertEquals(List.of(), emptyWorld.locationsFor("cobblemon:bulbasaur"));
        assertThrows(IllegalArgumentException.class,
            () -> emptyWorld.locationsFor("cobblemon:not_a_pokemon"));
    }

    @Test
    void secondaryHabitatsAreIncludedOnlyWhenTheZoneEnablesThem() throws Exception {
        HabitatCatalogLoader loader = new HabitatCatalogLoader();
        HabitatIndex habitats = new HabitatIndex(
            loader.loadPokemon(CONTENT.resolve("pokemon-habitats.json")),
            loader.loadBiomes(CONTENT.resolve("biome-profiles.json"))
        );
        SpawnSettings primaryOnly = new SpawnSettings(
            1, "temperate", "humid", "rain", "any", Set.of("rare"), false
        );
        SpawnSettings withSecondary = new SpawnSettings(
            1, "temperate", "humid", "rain", "any", Set.of("rare"), true
        );

        assertFalse(habitats.candidates(
            "cobbleventure:biome_profile/wetland", primaryOnly, Set.of()
        ).stream().anyMatch(candidate -> candidate.pokemonId().equals("cobblemon:bulbasaur")));
        assertTrue(habitats.candidates(
            "cobbleventure:biome_profile/wetland", withSecondary, Set.of()
        ).stream().anyMatch(candidate -> candidate.pokemonId().equals("cobblemon:bulbasaur")
            && candidate.matchReason() == MatchReason.SECONDARY_HABITAT));
    }
}
