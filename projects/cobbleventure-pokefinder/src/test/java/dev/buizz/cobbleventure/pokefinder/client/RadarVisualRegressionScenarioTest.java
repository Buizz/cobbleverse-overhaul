package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RadarVisualRegressionScenarioTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace(
        "overworld"
    );

    @Test
    void coversEveryMarkerTypeAndImportantVisualState() {
        List<RadarMarker> markers = RadarVisualRegressionScenario.create(
            DIMENSION, new Vec3(100.0D, 64.0D, 200.0D)
        );

        assertEquals(Set.of(RadarMarkerType.values()), markers.stream()
            .map(RadarMarker::type)
            .collect(Collectors.toSet()));
        assertTrue(markers.stream().anyMatch(marker ->
            marker.state() == RadarMarkerState.DEFEATED));
        assertTrue(markers.stream().anyMatch(marker ->
            marker.state() == RadarMarkerState.COMPLETED));
        assertTrue(markers.stream().anyMatch(marker ->
            marker.state() == RadarMarkerState.PRIMARY));
        assertTrue(markers.stream().allMatch(RadarVisualRegressionScenario::contains));
    }

    @Test
    void includesCollisionAndBothEdgeTrackingRanges() {
        List<RadarMarker> markers = RadarVisualRegressionScenario.create(DIMENSION, Vec3.ZERO);

        long collisions = markers.stream()
            .filter(marker -> marker.position().equals(new Vec3(-8.0D, 0.0D, 10.0D)))
            .count();
        assertEquals(3L, collisions);
        assertTrue(markers.stream().anyMatch(marker ->
            marker.type() == RadarMarkerType.GATE
                && marker.position().distanceTo(Vec3.ZERO) > marker.localRange()));
        assertTrue(markers.stream().anyMatch(marker ->
            marker.type() == RadarMarkerType.OBJECTIVE
                && marker.position().distanceTo(Vec3.ZERO)
                    > Cobblenav233LayoutAdapter.MAX_FALLBACK_RANGE));
    }
}
