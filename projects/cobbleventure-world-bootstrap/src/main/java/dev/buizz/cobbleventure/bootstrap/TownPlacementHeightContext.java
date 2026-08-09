package dev.buizz.cobbleventure.bootstrap;

import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * Limits the custom terrain-height lookup to the server thread while a
 * Cobbleventure town is being assembled by vanilla Jigsaw placement.
 */
public final class TownPlacementHeightContext {
    private static final Set<ResourceLocation> BCA_HOUSE_POOLS = Set.of(
        ResourceLocation.parse("bca:default/general"),
        ResourceLocation.parse("bca:fighting/fighting"),
        ResourceLocation.parse("bca:dark/dark"),
        ResourceLocation.parse("bca:ice/ice")
    );
    private static final ResourceLocation BCA_DEFAULT_ONE_OFF =
        ResourceLocation.parse("bca:default/one_off");
    private static final ResourceLocation EMPTY_POOL = ResourceLocation.parse("minecraft:empty");
    private static final ThreadLocal<State> ACTIVE_STATE = new ThreadLocal<>();

    private TownPlacementHeightContext() {
    }

    public static Scope open(HeightResolver resolver) {
        return open(resolver, null);
    }

    public static Scope open(HeightResolver resolver, String housePool) {
        return open(resolver, housePool, false);
    }

    public static Scope open(
        HeightResolver resolver, String housePool, boolean disableCommercialOneOff
    ) {
        if (ACTIVE_STATE.get() != null) {
            throw new IllegalStateException("Town placement height context is already active");
        }
        ResourceLocation parsedHousePool = housePool == null
            ? null
            : ResourceLocation.tryParse(housePool);
        if (housePool != null && parsedHousePool == null) {
            throw new IllegalArgumentException("Invalid house pool: " + housePool);
        }
        ACTIVE_STATE.set(new State(resolver, parsedHousePool, disableCommercialOneOff));
        return ACTIVE_STATE::remove;
    }

    public static Integer resolve(int x, int z, Heightmap.Types heightmap) {
        State state = ACTIVE_STATE.get();
        return state == null ? null : state.resolver().baseHeight(x, z, heightmap);
    }

    public static ResourceKey<StructureTemplatePool> remapHousePool(
        ResourceKey<StructureTemplatePool> original
    ) {
        State state = ACTIVE_STATE.get();
        if (state != null && state.disableCommercialOneOff()
            && original.location().equals(BCA_DEFAULT_ONE_OFF)) {
            return ResourceKey.create(Registries.TEMPLATE_POOL, EMPTY_POOL);
        }
        if (state == null || state.housePool() == null
            || !BCA_HOUSE_POOLS.contains(original.location())) {
            return original;
        }
        // BCA themes use different Jigsaw target/name contracts. Redirecting a
        // default road connector to the fighting, dark or ice house pool makes
        // every candidate fail to attach and leaves an empty road network.
        // Preserve the pool requested by the assembled path unless a custom
        // pool (with an explicitly compatible connector contract) is used.
        if (BCA_HOUSE_POOLS.contains(state.housePool())
            && !original.location().equals(state.housePool())) {
            return original;
        }
        return ResourceKey.create(Registries.TEMPLATE_POOL, state.housePool());
    }

    public static boolean shouldLowerTemplate(ResourceLocation template) {
        if (ACTIVE_STATE.get() == null || !template.getNamespace().equals("bca")) {
            return false;
        }
        String path = template.getPath();
        return path.equals("default/centers/center_the_academy")
            || path.startsWith("default/structures/")
            || path.startsWith("fighting/structures/")
            || path.startsWith("dark/structures/")
            || path.startsWith("ice/structures/")
            || path.startsWith("general/general_decor/")
            || path.startsWith("general/berries/")
            || path.startsWith("general/lamp_posts/");
    }

    public static boolean shouldPreserveTerrain(ResourceLocation template) {
        if (ACTIVE_STATE.get() == null || !template.getNamespace().equals("bca")) {
            return false;
        }
        String path = template.getPath();
        return path.startsWith("general/general_decor/")
            || path.startsWith("general/berries/")
            || path.startsWith("general/lamp_posts/")
            || path.startsWith("default/paths/")
            || path.startsWith("fighting/paths/")
            || path.startsWith("dark/paths/")
            || path.startsWith("ice/paths/");
    }

    @FunctionalInterface
    public interface HeightResolver {
        int baseHeight(int x, int z, Heightmap.Types heightmap);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record State(
        HeightResolver resolver,
        ResourceLocation housePool,
        boolean disableCommercialOneOff
    ) {}
}
