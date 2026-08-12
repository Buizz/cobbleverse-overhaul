package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

/** Renders unloaded space around generated caves as an unlit black void. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class CaveDimensionEffects {
    private static final ResourceLocation CAVE_EFFECTS =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "cave_world");

    private CaveDimensionEffects() {
    }

    @SubscribeEvent
    public static void register(RegisterDimensionSpecialEffectsEvent event) {
        event.register(CAVE_EFFECTS, new DimensionSpecialEffects(
            Float.NaN,
            true,
            DimensionSpecialEffects.SkyType.NONE,
            false,
            false
        ) {
            @Override
            public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
                return Vec3.ZERO;
            }

            @Override
            public boolean isFoggyAt(int x, int z) {
                return false;
            }
        });
    }
}
