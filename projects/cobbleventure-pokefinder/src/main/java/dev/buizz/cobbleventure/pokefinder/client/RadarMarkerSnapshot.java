package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import java.util.List;

/** Immutable client-side view replaced atomically when a server snapshot arrives. */
public final class RadarMarkerSnapshot {
    private static volatile List<RadarMarker> markers = List.of();

    private RadarMarkerSnapshot() {}

    public static List<RadarMarker> markers() {
        return markers;
    }

    public static void replace(List<RadarMarker> next) {
        markers = List.copyOf(next);
    }

    public static void clear() {
        markers = List.of();
    }
}
