package dev.buizz.cobbleventure.habitat;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record PokemonHabitatCatalog(int schemaVersion, List<PokemonHabitat> pokemon) {
    public PokemonHabitatCatalog {
        pokemon = pokemon == null ? List.of() : List.copyOf(pokemon);
    }

    public record PokemonHabitat(
        int dexNumber,
        String id,
        String slug,
        Map<String, String> displayName,
        int generation,
        Set<String> seriesAppearances,
        boolean isLegendary,
        boolean isMythical,
        List<String> types,
        Preferences preferences,
        Habitats habitats,
        boolean implemented
    ) {
        public PokemonHabitat {
            displayName = displayName == null ? Map.of() : Map.copyOf(displayName);
            seriesAppearances = seriesAppearances == null ? Set.of() : Set.copyOf(seriesAppearances);
            types = types == null ? List.of() : List.copyOf(types);
        }

        public PokemonHabitat(
            int dexNumber,
            String id,
            String slug,
            Map<String, String> displayName,
            int generation,
            List<String> types,
            Preferences preferences,
            Habitats habitats,
            boolean implemented
        ) {
            this(dexNumber, id, slug, displayName, generation, Set.of(), false, false, types, preferences, habitats, implemented);
        }
    }

    public record Preferences(
        String livingZone,
        String terrain,
        String temperature,
        String humidity,
        String weather,
        String time,
        String rarity,
        String specialTag
    ) {
    }

    public record Habitats(String primary, String secondary, String confidence) {
    }
}
