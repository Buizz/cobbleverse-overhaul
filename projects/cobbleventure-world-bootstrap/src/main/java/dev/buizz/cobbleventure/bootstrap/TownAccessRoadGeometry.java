package dev.buizz.cobbleventure.bootstrap;

/** Pure geometry used to keep an approach-road brush outside authored buildings. */
final class TownAccessRoadGeometry {
    private TownAccessRoadGeometry() {
    }

    static boolean isBeyondEnd(
        int startX, int startZ, int endX, int endZ,
        int candidateX, int candidateZ
    ) {
        return (candidateX - endX) * Integer.signum(endX - startX)
            + (candidateZ - endZ) * Integer.signum(endZ - startZ) > 0;
    }

    static int cornerX(boolean entranceFacesEastOrWest, int startX, int endX) {
        return entranceFacesEastOrWest ? startX : endX;
    }

    static int cornerZ(boolean entranceFacesEastOrWest, int startZ, int endZ) {
        return entranceFacesEastOrWest ? endZ : startZ;
    }
}
