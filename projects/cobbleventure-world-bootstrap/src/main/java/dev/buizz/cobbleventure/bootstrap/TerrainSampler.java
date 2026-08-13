package dev.buizz.cobbleventure.bootstrap;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.function.Supplier;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexWorldPlan;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.TerrainSample;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.WarpedPoint;

/** Owns cached terrain lookups independently from world lifecycle orchestration. */
final class TerrainSampler {
    private static final Cache<Key, Lookup> LOOKUPS = CacheBuilder.newBuilder()
        .maximumSize(262_144L).build();

    private TerrainSampler() {}

    static Lookup get(HexWorldPlan world, int x, int z) {
        return LOOKUPS.getIfPresent(key(world, x, z));
    }

    static Lookup getOrCompute(
        HexWorldPlan world, int x, int z,
        Supplier<Lookup> computer
    ) {
        Key key = key(world, x, z);
        Lookup cached = LOOKUPS.getIfPresent(key);
        if (cached != null) return cached;
        Lookup computed = computer.get();
        LOOKUPS.put(key, computed);
        return computed;
    }

    private static Key key(HexWorldPlan world, int x, int z) {
        return new Key(System.identityHashCode(world), world.seed(), x, z);
    }

    record Lookup(
        TerrainSample sample, WarpedPoint warped
    ) {}

    private record Key(int worldIdentity, long seed, int x, int z) {}
}
