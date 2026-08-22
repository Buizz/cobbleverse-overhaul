package dev.buizz.cobbleventure.bootstrap;

/** Keeps runtime facility placement anchored to terrain instead of an already placed roof. */
final class FacilityRuntimeGround {
    private FacilityRuntimeGround() {}

    static int correctedRoadSurfaceY(int loadedY, int plannedY) {
        return loadedY > plannedY + 2 ? plannedY : loadedY;
    }
}
