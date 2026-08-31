package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buizz.cobbleventure.pokefinder.client.RadarDisplaySettings.Option;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import java.util.EnumMap;
import java.util.Properties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class RadarDisplaySettingsTest {
    @Test
    void usesSafeDefaultsAndDecodesOverrides() {
        Properties properties = new Properties();
        properties.setProperty("show_trainers", "false");
        EnumMap<Option, Boolean> values = RadarDisplaySettings.decode(properties);
        assertFalse(values.get(Option.TRAINERS));
        assertTrue(values.get(Option.FACILITIES));
        assertFalse(values.get(Option.NAMES));
        assertTrue(values.get(Option.DEFEATED_TRAINERS));
        assertTrue(values.get(Option.PLAYERS));
    }

    @Test
    void keepsGymEntrancesInFacilitiesAndLeadersInTrainers() {
        assertEquals(Option.PLAYERS, RadarDisplaySettings.category(
            RadarMarkerType.PLAYER, "player/test"
        ));
        assertEquals(Option.FACILITIES, RadarDisplaySettings.category(
            RadarMarkerType.GYM_LEADER, "building/cobbleventure_gyms/base_gym"
        ));
        assertEquals(Option.TRAINERS, RadarDisplaySettings.category(
            RadarMarkerType.GYM_LEADER, "npc/00000000-0000-0000-0000-000000000000"
        ));
        assertEquals(Option.ENTRANCES, RadarDisplaySettings.category(
            RadarMarkerType.CAVE_ENTRANCE, "cave/rock_tunnel"
        ));
        assertEquals(Option.OBJECTIVES, RadarDisplaySettings.category(
            RadarMarkerType.OBJECTIVE, "objective/gym/pewter"
        ));
    }

    @Test
    void identifiesOnlyMajorFacilityMarkersAsLandmarks() {
        assertTrue(RadarDisplaySettings.isLandmark(marker(
            "building/pokemon_center", RadarMarkerType.POKEMON_CENTER
        )));
        assertTrue(RadarDisplaySettings.isLandmark(marker(
            "building/pokemart", RadarMarkerType.POKEMART
        )));
        assertTrue(RadarDisplaySettings.isLandmark(marker(
            "building/gym", RadarMarkerType.GYM_LEADER
        )));
        assertFalse(RadarDisplaySettings.isLandmark(marker(
            "npc/gym_leader", RadarMarkerType.GYM_LEADER
        )));
    }

    private static RadarMarker marker(String id, RadarMarkerType type) {
        return new RadarMarker(
            ResourceLocation.fromNamespaceAndPath("test", id), type,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            Vec3.ZERO, id,
            ResourceLocation.fromNamespaceAndPath("test", "radar/test"),
            100, RadarMarkerState.AVAILABLE, "", 64.0D, true
        );
    }
}
