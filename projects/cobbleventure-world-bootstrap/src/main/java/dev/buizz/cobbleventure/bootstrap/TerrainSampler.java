package dev.buizz.cobbleventure.bootstrap;

import java.util.function.Supplier;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.TerrainSample;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.WarpedPoint;

/** Owns cached terrain lookups independently from world lifecycle orchestration. */
final class TerrainSampler {
    private static final GenerationalCache<Key, Lookup> LOOKUPS =
        new GenerationalCache<>(262_144);

    private TerrainSampler() {}

    static Lookup get(HexWorldPlan world, int x, int z) {
        return LOOKUPS.getIfPresent(key(world, x, z));
    }

    static Lookup getOrCompute(
        HexWorldPlan world, int x, int z,
        Supplier<Lookup> computer
    ) {
        Key key = key(world, x, z);
        return LOOKUPS.getOrCompute(key, computer);
    }

    private static Key key(HexWorldPlan world, int x, int z) {
        return new Key(System.identityHashCode(world), world.seed(), x, z);
    }

    record Lookup(
        TerrainSample sample, WarpedPoint warped
    ) {}

    private record Key(int worldIdentity, long seed, int x, int z) {
        @Override
        public int hashCode() {
            return CacheKeyHash.spatial(worldIdentity, seed, x, z);
        }
    }
}
