package dev.buizz.cobbleventure.habitat;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record BiomeProfileCatalog(int schemaVersion, List<BiomeProfile> profiles, List<BiomeSet> sets) {
    public BiomeProfileCatalog {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        sets = sets == null ? List.of() : List.copyOf(sets);
    }

    public record BiomeProfile(
        String id,
        Map<String, String> displayName,
        String habitat,
        List<String> minecraftBiomes,
        SpawnSettings settings,
        Set<String> forcedIncludes,
        Set<String> excludedPokemon
    ) {
        public BiomeProfile {
            displayName = displayName == null ? Map.of() : Map.copyOf(displayName);
            minecraftBiomes = minecraftBiomes == null ? List.of() : List.copyOf(minecraftBiomes);
            forcedIncludes = forcedIncludes == null ? Set.of() : Set.copyOf(forcedIncludes);
            excludedPokemon = excludedPokemon == null ? Set.of() : Set.copyOf(excludedPokemon);
        }
    }

    public record BiomeSet(
        String id,
        Map<String, String> displayName,
        List<ProfileReference> profiles,
        Set<String> unconditionalSpawns
    ) {
        public BiomeSet {
            displayName = displayName == null ? Map.of() : Map.copyOf(displayName);
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            unconditionalSpawns = unconditionalSpawns == null ? Set.of() : Set.copyOf(unconditionalSpawns);
        }
    }

    public record ProfileReference(String profile, int weight) {
    }
}
