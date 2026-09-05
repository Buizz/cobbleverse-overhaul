package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import java.util.Comparator;
import java.util.List;

/** Keeps projected icons at their true radar positions in a deterministic draw order. */
final class RadarMarkerLayout {
    private static final Comparator<Candidate> STABLE_ORDER = Comparator
        .comparing(candidate -> candidate.marker.id().toString());

    private RadarMarkerLayout() {}

    static List<Placed> resolve(List<Candidate> candidates) {
        return candidates.stream()
            .sorted(STABLE_ORDER)
            .map(candidate -> new Placed(candidate.marker, candidate.point, 0))
            .toList();
    }

    record Candidate(
        RadarMarker marker, Cobblenav233LayoutAdapter.RadarPoint point
    ) {}

    record Placed(
        RadarMarker marker,
        Cobblenav233LayoutAdapter.RadarPoint point,
        int overlapCount
    ) {}

}
