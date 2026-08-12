package dev.buizz.cobbleventure.habitat;

import dev.buizz.cobbleventure.habitat.BiomeProfileCatalog.BiomeProfile;
import dev.buizz.cobbleventure.habitat.EncounterCandidate.MatchReason;
import dev.buizz.cobbleventure.habitat.PokemonHabitatCatalog.PokemonHabitat;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 포켓몬 후보 계산의 단일 기준. 지도/도감과 Cobblemon 스폰 어댑터가 같은 결과를 소비한다.
 */
public final class HabitatIndex {
    private final Map<String, PokemonHabitat> pokemon;
    private final Map<String, BiomeProfile> profiles;
    private final int maxPokemonPerHabitatVariant;

    public HabitatIndex(PokemonHabitatCatalog pokemonCatalog, BiomeProfileCatalog biomeCatalog) {
        pokemon = uniqueIndex(pokemonCatalog.pokemon(), PokemonHabitat::id, "포켓몬");
        profiles = uniqueIndex(biomeCatalog.profiles(), BiomeProfile::id, "바이옴 프로필");
        maxPokemonPerHabitatVariant = biomeCatalog.maxPokemonPerHabitatVariant();
    }

    public List<EncounterCandidate> candidates(
        String profileId,
        SpawnSettings zoneSettings,
        Set<String> unconditionalSpawns
    ) {
        BiomeProfile profile = requireProfile(profileId);
        SpawnSettings settings = zoneSettings == null ? profile.settings() : zoneSettings;
        Set<String> unconditional = unconditionalSpawns == null ? Set.of() : unconditionalSpawns;
        Map<String, EncounterCandidate> matches = new LinkedHashMap<>();

        for (PokemonHabitat entry : pokemon.values()) {
            if (!entry.implemented() || profile.excludedPokemon().contains(entry.id())) {
                continue;
            }
            MatchReason reason = matchReason(entry, profile, settings, unconditional);
            if (reason != null) {
                matches.put(entry.id(), candidate(entry, reason));
            }
        }

        List<EncounterCandidate> ordered = matches.values().stream()
            .sorted(Comparator.comparingInt(EncounterCandidate::dexNumber))
            .toList();
        return selectHabitatVariant(ordered, settings.habitatVariant());
    }

    public int habitatVariantCount(
        String profileId,
        SpawnSettings zoneSettings,
        Set<String> unconditionalSpawns
    ) {
        SpawnSettings settings = zoneSettings == null ? requireProfile(profileId).settings() : zoneSettings;
        List<EncounterCandidate> base = candidates(
            profileId, settings.withHabitatVariant(0), unconditionalSpawns
        );
        long ordinaryCount = base.stream()
            .filter(candidate -> candidate.matchReason() != MatchReason.UNCONDITIONAL
                && candidate.matchReason() != MatchReason.FORCED_INCLUDE)
            .count();
        return Math.max(1,
            (int) ((ordinaryCount + maxPokemonPerHabitatVariant - 1) / maxPokemonPerHabitatVariant));
    }

    public List<EncounterCandidate> candidatesForVariant(
        String profileId,
        SpawnSettings zoneSettings,
        Set<String> unconditionalSpawns,
        int habitatVariant
    ) {
        SpawnSettings settings = zoneSettings == null ? requireProfile(profileId).settings() : zoneSettings;
        return candidates(
            profileId, settings.withHabitatVariant(habitatVariant), unconditionalSpawns
        );
    }

    private List<EncounterCandidate> selectHabitatVariant(
        List<EncounterCandidate> candidates,
        int selectedVariant
    ) {
        if (selectedVariant <= 0) {
            return candidates;
        }
        List<EncounterCandidate> explicit = candidates.stream()
            .filter(candidate -> candidate.matchReason() == MatchReason.UNCONDITIONAL
                || candidate.matchReason() == MatchReason.FORCED_INCLUDE)
            .toList();
        List<EncounterCandidate> ordinary = candidates.stream()
            .filter(candidate -> candidate.matchReason() != MatchReason.UNCONDITIONAL
                && candidate.matchReason() != MatchReason.FORCED_INCLUDE)
            .toList();
        if (ordinary.isEmpty()) {
            return explicit;
        }
        int variantCount = Math.max(1,
            (ordinary.size() + maxPokemonPerHabitatVariant - 1) / maxPokemonPerHabitatVariant);
        List<EncounterCandidate> selected = new java.util.ArrayList<>(explicit);
        for (int index = 0; index < ordinary.size(); index++) {
            int variant = (index * variantCount / ordinary.size()) + 1;
            if (variant == selectedVariant) {
                selected.add(ordinary.get(index));
            }
        }
        return selected.stream()
            .sorted(Comparator.comparingInt(EncounterCandidate::dexNumber))
            .toList();
    }

    public PokemonHabitat requirePokemon(String pokemonId) {
        PokemonHabitat result = pokemon.get(pokemonId);
        if (result == null) {
            throw new IllegalArgumentException("알 수 없는 포켓몬 ID입니다: " + pokemonId);
        }
        return result;
    }

    private MatchReason matchReason(
        PokemonHabitat entry,
        BiomeProfile profile,
        SpawnSettings settings,
        Set<String> unconditional
    ) {
        if (unconditional.contains(entry.id())) {
            return MatchReason.UNCONDITIONAL;
        }
        if (profile.forcedIncludes().contains(entry.id())) {
            return MatchReason.FORCED_INCLUDE;
        }
        if (entry.isLegendary() || entry.isMythical()) {
            return null;
        }
        if (!settings.rarities().contains(entry.preferences().rarity())
            || !matchesSeriesOrGeneration(settings, entry)
            || !compatible(settings.temperature(), entry.preferences().temperature())
            || !compatible(settings.humidity(), entry.preferences().humidity())
            || !compatible(settings.weather(), entry.preferences().weather())
            || !compatible(settings.time(), entry.preferences().time())) {
            return null;
        }
        if (profile.habitat().equals(entry.habitats().primary())) {
            return MatchReason.PRIMARY_HABITAT;
        }
        if (settings.includeSecondary() && profile.habitat().equals(entry.habitats().secondary())) {
            return MatchReason.SECONDARY_HABITAT;
        }
        return null;
    }

    private boolean matchesSeriesOrGeneration(SpawnSettings settings, PokemonHabitat entry) {
        if (!settings.series().isBlank()) {
            return entry.seriesAppearances().contains(settings.series());
        }
        return settings.generation() == 0 || settings.generation() == entry.generation();
    }

    private boolean compatible(String requested, String preference) {
        return "any".equals(requested) || "any".equals(preference) || requested.equals(preference);
    }

    private EncounterCandidate candidate(PokemonHabitat entry, MatchReason reason) {
        return new EncounterCandidate(
            entry.dexNumber(),
            entry.id(),
            entry.displayName(),
            entry.preferences().rarity(),
            reason
        );
    }

    private BiomeProfile requireProfile(String profileId) {
        BiomeProfile profile = profiles.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("알 수 없는 바이옴 프로필 ID입니다: " + profileId);
        }
        return profile;
    }

    private static <T> Map<String, T> uniqueIndex(List<T> values, Function<T, String> id, String label) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String key = id.apply(value);
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(label + " ID가 중복되었습니다: " + key);
            }
        }
        return Map.copyOf(result);
    }
}
