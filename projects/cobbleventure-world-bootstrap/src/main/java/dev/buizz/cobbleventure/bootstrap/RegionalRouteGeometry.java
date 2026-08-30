package dev.buizz.cobbleventure.bootstrap;

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
}
