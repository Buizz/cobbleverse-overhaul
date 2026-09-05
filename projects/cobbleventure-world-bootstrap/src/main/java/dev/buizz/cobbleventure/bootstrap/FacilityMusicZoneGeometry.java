package dev.buizz.cobbleventure.bootstrap;

/** Pure geometry for an overworld facility's interior music detection bounds. */
final class FacilityMusicZoneGeometry {
    private FacilityMusicZoneGeometry() {}

    static Bounds bounds(
        int originX, int originY, int originZ, int width, int depth, int height
    ) {
        return new Bounds(
            originX, originY, originZ,
            originX + Math.max(1, width),
            originY + Math.max(4, height),
            originZ + Math.max(1, depth)
        );
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX && y >= minY && y < maxY
                && z >= minZ && z < maxZ;
        }
    }
}
