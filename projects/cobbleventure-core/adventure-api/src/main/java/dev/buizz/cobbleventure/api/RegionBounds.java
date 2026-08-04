package dev.buizz.cobbleventure.api;

public record RegionBounds(int minX, int minZ, int maxX, int maxZ) {
    public boolean contains(BlockPosition position) {
        return position.x() >= minX
            && position.x() <= maxX
            && position.z() >= minZ
            && position.z() <= maxZ;
    }

    public boolean overlaps(RegionBounds other) {
        return minX <= other.maxX
            && maxX >= other.minX
            && minZ <= other.maxZ
            && maxZ >= other.minZ;
    }
}
