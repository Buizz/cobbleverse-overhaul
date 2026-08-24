package dev.buizz.cobbleventure.pokefinder.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
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
        assertEquals(RadarMarkerType.TRAINER,
            WorldBootstrapRadarProvider.markerType("TRAINER"));
        assertEquals(RadarMarkerType.IMPORTANT_NPC,
            WorldBootstrapRadarProvider.markerType("IMPORTANT_NPC"));
        assertEquals(RadarMarkerType.OBJECTIVE,
            WorldBootstrapRadarProvider.markerType("OBJECTIVE"));
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

    @Test
    void mapsPlayerSpecificRuntimeStates() {
        assertEquals(RadarMarkerState.DEFEATED,
            WorldBootstrapRadarProvider.markerState("DEFEATED"));
        assertEquals(RadarMarkerState.COMPLETED,
            WorldBootstrapRadarProvider.markerState("COMPLETED"));
        assertEquals(RadarMarkerState.AVAILABLE,
            WorldBootstrapRadarProvider.markerState("UNKNOWN"));
    }
}
