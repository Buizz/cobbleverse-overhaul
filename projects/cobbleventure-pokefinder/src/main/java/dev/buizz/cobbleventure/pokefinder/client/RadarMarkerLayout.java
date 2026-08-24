package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves projected icon collisions while retaining deterministic priority order. */
final class RadarMarkerLayout {
    static final double ICON_SEPARATION = 7.0D;
    private static final Comparator<Candidate> PICK_ORDER = Comparator
        .comparingInt((Candidate candidate) -> candidate.marker.priority()).reversed()
        .thenComparing(candidate -> candidate.marker.id().toString());
    private static final Comparator<Placed> DRAW_ORDER = Comparator
        .comparingInt((Placed placed) -> placed.marker.priority())
        .thenComparing(placed -> placed.marker.id().toString());

    private RadarMarkerLayout() {}

    static List<Placed> resolve(List<Candidate> candidates) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(PICK_ORDER);
        List<Group> groups = new ArrayList<>();
        double minimumSquared = ICON_SEPARATION * ICON_SEPARATION;
        for (Candidate candidate : ordered) {
            Group collision = groups.stream()
                .filter(group -> distanceSquared(group.point, candidate.point) < minimumSquared)
                .findFirst()
                .orElse(null);
            if (collision == null) {
                groups.add(new Group(candidate.marker, candidate.point));
            } else {
                collision.overlapCount++;
            }
        }
        List<Placed> result = groups.stream()
            .map(group -> new Placed(group.marker, group.point, group.overlapCount))
            .sorted(DRAW_ORDER)
            .toList();
        return List.copyOf(result);
    }

    private static double distanceSquared(
        Cobblenav233LayoutAdapter.RadarPoint left,
        Cobblenav233LayoutAdapter.RadarPoint right
    ) {
        double dx = left.x() - right.x();
        double dy = left.y() - right.y();
        return dx * dx + dy * dy;
    }

    record Candidate(
        RadarMarker marker, Cobblenav233LayoutAdapter.RadarPoint point
    ) {}

    record Placed(
        RadarMarker marker,
        Cobblenav233LayoutAdapter.RadarPoint point,
        int overlapCount
    ) {}

    private static final class Group {
        final RadarMarker marker;
        final Cobblenav233LayoutAdapter.RadarPoint point;
        int overlapCount;

        Group(RadarMarker marker, Cobblenav233LayoutAdapter.RadarPoint point) {
            this.marker = marker;
            this.point = point;
        }
    }
}
