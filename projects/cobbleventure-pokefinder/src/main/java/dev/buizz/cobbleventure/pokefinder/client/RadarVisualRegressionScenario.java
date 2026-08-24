package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.CobbleventurePokefinder;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Deterministic marker set used to visually verify the CobbleNav integration. */
final class RadarVisualRegressionScenario {
    static final String SYSTEM_PROPERTY = "cobbleventure.pokefinder.visualRegression";
    private static final String ID_PREFIX = "visual_regression/";

    private RadarVisualRegressionScenario() {}

    static List<RadarMarker> create(ResourceLocation dimension, Vec3 origin) {
        List<RadarMarker> markers = new ArrayList<>();
        markers.add(marker(dimension, origin, "trainer", RadarMarkerType.TRAINER,
            -42.0D, -30.0D, 100, RadarMarkerState.DEFEATED, 64.0D, false));
        markers.add(marker(dimension, origin, "gym", RadarMarkerType.GYM_LEADER,
            -22.0D, -42.0D, 800, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "npc", RadarMarkerType.IMPORTANT_NPC,
            0.0D, -44.0D, 700, RadarMarkerState.COMPLETED, 64.0D, false));
        markers.add(marker(dimension, origin, "center", RadarMarkerType.POKEMON_CENTER,
            22.0D, -42.0D, 600, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "mart", RadarMarkerType.POKEMART,
            42.0D, -30.0D, 600, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "casino", RadarMarkerType.CASINO,
            48.0D, -10.0D, 600, RadarMarkerState.LOCKED, 64.0D, true));
        markers.add(marker(dimension, origin, "building", RadarMarkerType.SPECIAL_BUILDING,
            48.0D, 12.0D, 600, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "cave", RadarMarkerType.CAVE_ENTRANCE,
            34.0D, 34.0D, 500, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "forest", RadarMarkerType.FOREST_ENTRANCE,
            10.0D, 48.0D, 500, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "gate", RadarMarkerType.GATE,
            -18.0D, 45.0D, 500, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "objective", RadarMarkerType.OBJECTIVE,
            -42.0D, 28.0D, 1_000, RadarMarkerState.PRIMARY, 64.0D, true));

        // The objective wins this collision; the small plus sign confirms aggregation.
        markers.add(marker(dimension, origin, "overlap_objective", RadarMarkerType.OBJECTIVE,
            -8.0D, 10.0D, 1_000, RadarMarkerState.PRIMARY, 64.0D, true));
        markers.add(marker(dimension, origin, "overlap_gym", RadarMarkerType.GYM_LEADER,
            -8.0D, 10.0D, 800, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "overlap_trainer", RadarMarkerType.TRAINER,
            -8.0D, 10.0D, 100, RadarMarkerState.AVAILABLE, 64.0D, false));

        // One normal tracked place and one long-range objective exercise edge pinning.
        markers.add(marker(dimension, origin, "edge_gate", RadarMarkerType.GATE,
            96.0D, 0.0D, 500, RadarMarkerState.AVAILABLE, 64.0D, true));
        markers.add(marker(dimension, origin, "edge_objective", RadarMarkerType.OBJECTIVE,
            -1_024.0D, 0.0D, 1_000, RadarMarkerState.SECONDARY, 64.0D, true));
        return List.copyOf(markers);
    }

    static boolean contains(RadarMarker marker) {
        return marker.id().getNamespace().equals(CobbleventurePokefinder.MOD_ID)
            && marker.id().getPath().startsWith(ID_PREFIX);
    }

    private static RadarMarker marker(
        ResourceLocation dimension,
        Vec3 origin,
        String name,
        RadarMarkerType type,
        double deltaX,
        double deltaZ,
        int priority,
        RadarMarkerState state,
        double localRange,
        boolean edgeTracking
    ) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
            CobbleventurePokefinder.MOD_ID, ID_PREFIX + name
        );
        return new RadarMarker(
            id,
            type,
            dimension,
            origin.add(deltaX, 0.0D, deltaZ),
            "VR " + name,
            id,
            priority,
            state,
            "visual_regression",
            localRange,
            edgeTracking
        );
    }
}
