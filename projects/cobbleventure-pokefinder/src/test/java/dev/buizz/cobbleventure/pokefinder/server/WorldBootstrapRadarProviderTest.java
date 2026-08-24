package dev.buizz.cobbleventure.pokefinder.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import org.junit.jupiter.api.Test;

class WorldBootstrapRadarProviderTest {
    @Test
    void mapsRuntimeLocationKinds() {
        assertEquals(RadarMarkerType.GYM_LEADER,
            WorldBootstrapRadarProvider.markerType("GYM"));
        assertEquals(RadarMarkerType.POKEMON_CENTER,
            WorldBootstrapRadarProvider.markerType("POKEMON_CENTER"));
        assertEquals(RadarMarkerType.CAVE_ENTRANCE,
            WorldBootstrapRadarProvider.markerType("CAVE_ENTRANCE"));
        assertEquals(RadarMarkerType.SPECIAL_BUILDING,
            WorldBootstrapRadarProvider.markerType("UNKNOWN"));
    }

    @Test
    void convertsRuntimeIdsToResourcePaths() {
        assertEquals(
            "building/cobbleventure_facilities/pokemon_center/1/2/3",
            WorldBootstrapRadarProvider.safePath(
                "building/cobbleventure:facilities/pokemon_center/1/2/3"
            )
        );
    }
}
