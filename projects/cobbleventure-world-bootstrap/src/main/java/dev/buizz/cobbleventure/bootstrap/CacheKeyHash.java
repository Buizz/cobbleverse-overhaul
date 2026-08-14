package dev.buizz.cobbleventure.bootstrap;

/** Produces well-distributed hashes for spatial cache keys. */
final class CacheKeyHash {
    private CacheKeyHash() {}

    static int spatial(int worldIdentity, long seed, long x, long z) {
        long value = seed
            ^ (Integer.toUnsignedLong(worldIdentity) * 0x9E3779B97F4A7C15L)
            ^ (x * 0xC2B2AE3D27D4EB4FL)
            ^ Long.rotateLeft(z * 0x165667B19E3779F9L, 29);
        return avalanche(value);
    }

    static int spatial(
        int worldIdentity, long seed, Object discriminator, long x, long z
    ) {
        long value = Integer.toUnsignedLong(discriminator.hashCode())
            * 0xD6E8FEB86659FD93L;
        value ^= Integer.toUnsignedLong(spatial(worldIdentity, seed, x, z));
        return avalanche(value);
    }

    private static int avalanche(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return (int) (value ^ value >>> 32);
    }
}
