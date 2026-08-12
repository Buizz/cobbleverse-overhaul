package dev.buizz.cobbleventure.habitat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 현재 생성된 월드만을 기준으로 출현 장소와 후보 목록을 양방향 조회한다.
 */
public final class WorldHabitatIndex {
    private final HabitatIndex habitats;
    private final Map<String, GeneratedHabitatZone> zones;
    private final Map<String, List<EncounterCandidate>> encountersByLocation;
    private final Map<String, List<PokemonLocation>> locationsByPokemon;

    public WorldHabitatIndex(HabitatIndex habitats, List<GeneratedHabitatZone> generatedZones) {
        this.habitats = habitats;
        Map<String, GeneratedHabitatZone> zoneIndex = new LinkedHashMap<>();
        Map<String, List<EncounterCandidate>> encounters = new LinkedHashMap<>();
        Map<String, List<PokemonLocation>> locations = new LinkedHashMap<>();
        Map<String, Integer> nextVariantByProfile = new LinkedHashMap<>();

        for (GeneratedHabitatZone zone : generatedZones) {
            if (zoneIndex.putIfAbsent(zone.locationId(), zone) != null) {
                throw new IllegalArgumentException("월드 서식지 위치 ID가 중복되었습니다: " + zone.locationId());
            }
            int selectedVariant = zone.settings() != null ? zone.settings().habitatVariant() : 0;
            if (selectedVariant <= 0) {
                int variantCount = habitats.habitatVariantCount(
                    zone.profileId(), zone.settings(), zone.unconditionalSpawns()
                );
                int next = nextVariantByProfile.getOrDefault(zone.profileId(), 0);
                selectedVariant = (next % variantCount) + 1;
                nextVariantByProfile.put(zone.profileId(), next + 1);
            }
            List<EncounterCandidate> candidates = habitats.candidatesForVariant(
                zone.profileId(), zone.settings(), zone.unconditionalSpawns(), selectedVariant
            );
            encounters.put(zone.locationId(), candidates);
            for (EncounterCandidate candidate : candidates) {
                locations.computeIfAbsent(candidate.pokemonId(), ignored -> new ArrayList<>())
                    .add(toLocation(zone, candidate));
            }
        }

        locations.replaceAll((ignored, values) -> values.stream()
            .sorted(Comparator.comparing(PokemonLocation::settlementId)
                .thenComparing(PokemonLocation::zoneId))
            .toList());
        this.zones = Map.copyOf(zoneIndex);
        this.encountersByLocation = Map.copyOf(encounters);
        this.locationsByPokemon = Map.copyOf(locations);
    }

    public List<EncounterCandidate> encountersAt(String locationId) {
        requireZone(locationId);
        return encountersByLocation.get(locationId);
    }

    public List<PokemonLocation> locationsFor(String pokemonId) {
        habitats.requirePokemon(pokemonId);
        return locationsByPokemon.getOrDefault(pokemonId, List.of());
    }

    public List<GeneratedHabitatZone> zones() {
        return zones.values().stream()
            .sorted(Comparator.comparing(GeneratedHabitatZone::settlementId)
                .thenComparing(GeneratedHabitatZone::zoneId))
            .toList();
    }

    public GeneratedHabitatZone requireZone(String locationId) {
        GeneratedHabitatZone zone = zones.get(locationId);
        if (zone == null) {
            throw new IllegalArgumentException("알 수 없는 월드 서식지 위치 ID입니다: " + locationId);
        }
        return zone;
    }

    private PokemonLocation toLocation(GeneratedHabitatZone zone, EncounterCandidate candidate) {
        return new PokemonLocation(
            zone.locationId(),
            zone.settlementId(),
            zone.zoneId(),
            zone.biomeId(),
            zone.displayName(),
            zone.centerX(),
            zone.centerZ(),
            zone.radiusBlocks(),
            candidate.rarity(),
            candidate.matchReason()
        );
    }
}
