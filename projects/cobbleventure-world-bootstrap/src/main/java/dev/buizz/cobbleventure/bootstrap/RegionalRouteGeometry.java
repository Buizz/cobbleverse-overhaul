package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
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

    /** Keep the authored bridge intact, extending its ends to the compiled town roads. */
    static List<CobbleventureBootstrap.Point> connectLogBridgeTownRoads(
        List<CobbleventureBootstrap.Point> centerline,
        CobbleventureBootstrap.Point fromRoad,
        CobbleventureBootstrap.Point toRoad
    ) {
        if (centerline.size() < 2) return List.copyOf(centerline);
        List<CobbleventureBootstrap.Point> connected = new ArrayList<>(centerline);
        extendBridgeEnd(connected, toRoad);
        Collections.reverse(connected);
        extendBridgeEnd(connected, fromRoad);
        Collections.reverse(connected);
        return List.copyOf(connected);
    }

    private static void extendBridgeEnd(
        List<CobbleventureBootstrap.Point> points,
        CobbleventureBootstrap.Point road
    ) {
        if (road == null || road.equals(points.getLast())) return;
        CobbleventureBootstrap.Point end = points.getLast();
        CobbleventureBootstrap.Point previous = points.get(points.size() - 2);
        boolean alongX = Math.abs(end.x() - previous.x())
            >= Math.abs(end.z() - previous.z());
        CobbleventureBootstrap.Point elbow = alongX
            ? new CobbleventureBootstrap.Point(road.x(), end.z())
            : new CobbleventureBootstrap.Point(end.x(), road.z());
        if (!elbow.equals(end)) points.add(elbow);
        if (!road.equals(points.getLast())) points.add(road);
    }

    /** Former raised deck height, used only to remove old generated land decks. */
    static int legacyLogBridgeDeckY(int waterSurfaceY, int groundY) {
        return Math.max(waterSurfaceY, groundY) + 1;
    }

    static boolean logBridgeUsesWood(int groundY, int waterTopY) {
        // A coastal biome can contain both submerged and dry columns.
        return waterTopY > groundY;
    }

    static int logBridgeLandSurfaceY(int groundY, boolean ascending) {
        return groundY + (ascending ? 1 : 0);
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
