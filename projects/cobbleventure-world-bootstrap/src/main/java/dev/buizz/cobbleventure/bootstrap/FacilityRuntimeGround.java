package dev.buizz.cobbleventure.bootstrap;

import java.util.function.IntSupplier;

/** Keeps runtime facility placement anchored to terrain instead of an already placed roof. */
final class FacilityRuntimeGround {
    private FacilityRuntimeGround() {}

    static int correctedRoadSurfaceY(int loadedY, int plannedY) {
        return loadedY > plannedY + 2 ? plannedY : loadedY;
    }

    static int resolvedRoadSurfaceY(
        boolean chunkLoaded, IntSupplier loadedRoadY, int plannedY
    ) {
        if (!chunkLoaded) return plannedY;
        return correctedRoadSurfaceY(loadedRoadY.getAsInt(), plannedY);
    }
}
