package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class RadarMarkerLayoutTest {
    @Test
    void keepsAndFansOutEveryOverlappingMarker() {
        var point = point(50.0D, 50.0D);
        List<RadarMarkerLayout.Placed> placed = RadarMarkerLayout.resolve(List.of(
            candidate(marker("gate", RadarMarkerType.GATE, 200), point),
            candidate(marker("trainer", RadarMarkerType.TRAINER, 400), point),
            candidate(marker("objective", RadarMarkerType.OBJECTIVE, 600), point)
        ));

        assertEquals(3, placed.size());
        assertEquals(3, placed.stream().map(value -> value.marker().type()).distinct().count());
        assertEquals(3, placed.stream().map(RadarMarkerLayout.Placed::point).distinct().count());
    }

    @Test
    void keepsSeparatedMarkersInStableIdOrder() {
        List<RadarMarkerLayout.Placed> placed = RadarMarkerLayout.resolve(List.of(
            candidate(marker("objective", RadarMarkerType.OBJECTIVE, 600), point(20, 20)),
            candidate(marker("gate", RadarMarkerType.GATE, 200), point(40, 40)),
            candidate(marker("trainer", RadarMarkerType.TRAINER, 400), point(30, 30))
        ));

        assertEquals(List.of(
            RadarMarkerType.GATE, RadarMarkerType.OBJECTIVE, RadarMarkerType.TRAINER
        ), placed.stream().map(value -> value.marker().type()).toList());
    }

    @Test
    void treatsExactlySevenPixelsAsNonOverlapping() {
        List<RadarMarkerLayout.Placed> placed = RadarMarkerLayout.resolve(List.of(
            candidate(marker("left", RadarMarkerType.GATE, 200), point(10, 10)),
            candidate(marker("right", RadarMarkerType.GATE, 200), point(17, 10))
        ));

        assertEquals(2, placed.size());
    }

    private static RadarMarkerLayout.Candidate candidate(
        RadarMarker marker, Cobblenav233LayoutAdapter.RadarPoint point
    ) {
        return new RadarMarkerLayout.Candidate(marker, point);
    }

    private static Cobblenav233LayoutAdapter.RadarPoint point(double x, double y) {
        return new Cobblenav233LayoutAdapter.RadarPoint(x, y, true, false);
    }

    private static RadarMarker marker(String id, RadarMarkerType type, int priority) {
        return new RadarMarker(
            ResourceLocation.fromNamespaceAndPath("test", id), type,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            Vec3.ZERO, id,
            ResourceLocation.fromNamespaceAndPath("test", "icon/" + id),
            priority, RadarMarkerState.AVAILABLE, "", 64.0D, true
        );
    }
}
