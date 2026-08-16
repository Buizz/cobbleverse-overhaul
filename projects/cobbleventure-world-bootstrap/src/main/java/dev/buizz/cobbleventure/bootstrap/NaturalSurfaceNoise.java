package dev.buizz.cobbleventure.bootstrap;

/** Smooth deterministic surface noise without visible square sampling cells. */
final class NaturalSurfaceNoise {
    private NaturalSurfaceNoise() {}

    static double sample2D(long seed, int x, int z) {
        return valueNoise(seed, x, z, 7) * 0.68D
            + valueNoise(seed ^ 0x9E3779B97F4A7C15L, x, z, 3) * 0.32D;
    }

    private static double valueNoise(long seed, int x, int z, int scale) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double localX = Math.floorMod(x, scale) / (double) scale;
        double localZ = Math.floorMod(z, scale) / (double) scale;
        double blendX = smooth(localX);
        double blendZ = smooth(localZ);
        double north = lerp(
            hash(seed, cellX, cellZ), hash(seed, cellX + 1, cellZ), blendX
        );
        double south = lerp(
            hash(seed, cellX, cellZ + 1), hash(seed, cellX + 1, cellZ + 1), blendX
        );
        return lerp(north, south, blendZ);
    }

    private static double hash(long seed, int x, int z) {
        long value = seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
    }

    private static double smooth(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }
}
