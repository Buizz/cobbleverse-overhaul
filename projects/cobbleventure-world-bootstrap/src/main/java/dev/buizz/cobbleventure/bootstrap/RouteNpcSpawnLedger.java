package dev.buizz.cobbleventure.bootstrap;

/** Stable persisted identity for one authored NPC slot on a route. */
final class RouteNpcSpawnLedger {
    private RouteNpcSpawnLedger() {}

    static String key(String routeId, String placementId) {
        return routeId + "/" + placementId;
    }
}
