package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RadarLocationCatalogTest {
    @Test
    void classifiesFacilityStructures() {
        assertEquals(
            RadarLocationCatalog.Kind.POKEMON_CENTER,
            RadarLocationCatalog.buildingKind("cobbleventure:facilities/pokemon_center")
        );
        assertEquals(
            RadarLocationCatalog.Kind.POKEMART,
            RadarLocationCatalog.buildingKind("cobbleventure:facilities/pokemart")
        );
        assertEquals(
            RadarLocationCatalog.Kind.CASINO,
            RadarLocationCatalog.buildingKind("cobbleventure:interiors/casino")
        );
        assertEquals(
            RadarLocationCatalog.Kind.GYM,
            RadarLocationCatalog.buildingKind("cobbleventure:gyms/pewter_gym")
        );
        assertEquals(
            RadarLocationCatalog.Kind.SPECIAL_BUILDING,
            RadarLocationCatalog.buildingKind("cobbleventure:interiors/laboratory")
        );
    }
}
