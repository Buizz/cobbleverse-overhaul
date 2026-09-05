package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keeps every projected icon visible and fans out markers that occupy the same spot. */
final class RadarMarkerLayout {
    static final double ICON_SEPARATION = 7.0D;
    private static final Comparator<Candidate> STABLE_ORDER = Comparator
        .comparing(candidate -> candidate.marker.id().toString());

    private RadarMarkerLayout() {}

    static List<Placed> resolve(List<Candidate> candidates) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(STABLE_ORDER);
        List<Group> groups = new ArrayList<>();
        double minimumSquared = ICON_SEPARATION * ICON_SEPARATION;
        for (Candidate candidate : ordered) {
            Group collision = groups.stream()
                .filter(group -> distanceSquared(group.point, candidate.point) < minimumSquared)
                .findFirst()
                .orElse(null);
            if (collision == null) {
                groups.add(new Group(candidate));
            } else {
                collision.candidates.add(candidate);
            }
        }
        List<Placed> result = new ArrayList<>();
        for (Group group : groups) {
            int count = group.candidates.size();
            if (count == 1) {
                Candidate candidate = group.candidates.getFirst();
                result.add(new Placed(candidate.marker, candidate.point, 0));
                continue;
            }
            double radius = ICON_SEPARATION + Math.max(0, count - 6) * 1.5D;
            for (int index = 0; index < count; index++) {
                Candidate candidate = group.candidates.get(index);
                double angle = -Math.PI / 2.0D + Math.PI * 2.0D * index / count;
                result.add(new Placed(
                    candidate.marker,
                    new Cobblenav233LayoutAdapter.RadarPoint(
                        group.point.x() + Math.cos(angle) * radius,
                        group.point.y() + Math.sin(angle) * radius,
                        candidate.point.visible(), candidate.point.edgePinned()
                    ),
                    0
                ));
            }
        }
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
        final Cobblenav233LayoutAdapter.RadarPoint point;
        final List<Candidate> candidates = new ArrayList<>();

        Group(Candidate candidate) {
            this.point = candidate.point;
            this.candidates.add(candidate);
        }
    }
}
