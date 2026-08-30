package dev.buizz.cobbleventure.bootstrap;

/** Prevents cave entrance templates from being written more than once. */
final class CaveEntrancePlacementPolicy {
    private CaveEntrancePlacementPolicy() {}

    static boolean restoreEntrancesAtStartup(boolean mapComplete) {
        return mapComplete;
    }

    static boolean placeTemplate(boolean placementMarkerPresent) {
        return !placementMarkerPresent;
    }
}
