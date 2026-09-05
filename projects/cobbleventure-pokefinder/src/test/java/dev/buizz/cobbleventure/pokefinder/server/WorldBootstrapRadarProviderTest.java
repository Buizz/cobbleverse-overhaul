package dev.buizz.cobbleventure.pokefinder.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorldBootstrapRadarProviderTest {
    @org.junit.jupiter.api.Test
    void hidesObjectiveBangWhenTheObjectiveIsAnNpc() {
        RadarMarker npc = marker("npc", RadarMarkerType.IMPORTANT_NPC, Vec3.ZERO);
        RadarMarker objective = marker("objective", RadarMarkerType.OBJECTIVE, Vec3.ZERO);

        assertEquals(
            List.of(npc),
            WorldBootstrapRadarProvider.withoutNpcObjectiveDuplicates(List.of(npc, objective))
        );
    }

    private static RadarMarker marker(String id, RadarMarkerType type, Vec3 position) {
        return new RadarMarker(
            ResourceLocation.fromNamespaceAndPath("test", id), type,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            position, id, ResourceLocation.fromNamespaceAndPath("test", "icon/" + id),
            0, RadarMarkerState.AVAILABLE, "", 64.0D, true
        );
    }
    @Test
    void mapsRuntimeLocationKinds() {
        assertEquals(RadarMarkerType.GYM_LEADER,
            WorldBootstrapRadarProvider.markerType("GYM"));
        assertEquals(RadarMarkerType.POKEMON_CENTER,
            WorldBootstrapRadarProvider.markerType("POKEMON_CENTER"));
        assertEquals(RadarMarkerType.POKEMART,
            WorldBootstrapRadarProvider.markerType("POKEMART"));
        assertEquals(RadarMarkerType.CAVE_ENTRANCE,
            WorldBootstrapRadarProvider.markerType("CAVE_ENTRANCE"));
        assertEquals(RadarMarkerType.DUNGEON_ENTRANCE,
            WorldBootstrapRadarProvider.markerType("DUNGEON_ENTRANCE"));
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

    @Test
    void loadsDataAuthoredIconsAndPriorities() {
        RadarIconSettings.Entry pokemart = RadarIconSettings.resolve("POKEMART", "fallback");
        assertEquals("pokemart", pokemart.icon());
        assertEquals(9, pokemart.pixels().size());
        RadarIconSettings.Entry trainer = RadarIconSettings.resolve("TRAINER", "fallback");
        assertEquals("trainer", trainer.icon());
    }
}
