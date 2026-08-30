package dev.buizz.cobbleventure.bootstrap;

import java.util.List;

/** Small route decisions shared by regional-road placement and its tests. */
final class RegionalRouteGeometry {
    private RegionalRouteGeometry() {}

    static String gateTarget(String target, String routeId) {
        return target == null ? "route:" + routeId : target;
    }

    static boolean approachIsAquatic(
        String surfaceStyle, boolean aquaticTerrain, boolean bridgeOverOcean
    ) {
        return surfaceStyle.equals("log_bridge") ? bridgeOverOcean : aquaticTerrain;
    }

    static boolean corridorOverlapsHexTile(
        List<CobbleventureBootstrap.Point> centerline,
        CobbleventureBootstrap.Point tileCenter,
        int tileRadius,
        double corridorWidth
    ) {
        if (centerline.isEmpty()) {
            return false;
        }
        // A pointy-top hex is this far from its center to each flat edge.
        // Adding half the road width turns a mere edge touch into an overlap.
        double reach = Math.max(0, tileRadius) * Math.sqrt(3.0D) / 2.0D
            + Math.max(0.0D, corridorWidth) / 2.0D;
        return distanceToPolyline(centerline, tileCenter) <= reach;
    }

    static boolean nearestRouteEndIsLast(
        List<CobbleventureBootstrap.Point> centerline,
        CobbleventureBootstrap.Point target
    ) {
        if (centerline.isEmpty()) {
            return false;
        }
        CobbleventureBootstrap.Point first = centerline.getFirst();
        CobbleventureBootstrap.Point last = centerline.getLast();
        return distance(last, target) < distance(first, target);
    }

    static CobbleventureBootstrap.Point connectedEndpoint(
        List<CobbleventureBootstrap.Point> centerline, boolean last
    ) {
        if (centerline.isEmpty()) {
            return null;
        }
        return last ? centerline.getLast() : centerline.getFirst();
    }

    private static double distanceToPolyline(
        List<CobbleventureBootstrap.Point> centerline,
        CobbleventureBootstrap.Point target
    ) {
        if (centerline.size() == 1) {
            return distance(centerline.getFirst(), target);
        }
        double closest = Double.POSITIVE_INFINITY;
        for (int index = 1; index < centerline.size(); index++) {
            CobbleventureBootstrap.Point start = centerline.get(index - 1);
            CobbleventureBootstrap.Point end = centerline.get(index);
            double dx = end.x() - start.x();
            double dz = end.z() - start.z();
            double lengthSquared = dx * dx + dz * dz;
            double factor = lengthSquared == 0.0D ? 0.0D
                : ((target.x() - start.x()) * dx
                    + (target.z() - start.z()) * dz) / lengthSquared;
            factor = Math.max(0.0D, Math.min(1.0D, factor));
            double projectedX = start.x() + factor * dx;
            double projectedZ = start.z() + factor * dz;
            closest = Math.min(
                closest,
                Math.hypot(target.x() - projectedX, target.z() - projectedZ)
            );
        }
        return closest;
    }

    private static double distance(
        CobbleventureBootstrap.Point left,
        CobbleventureBootstrap.Point right
    ) {
        return Math.hypot(left.x() - right.x(), left.z() - right.z());
    }
}
