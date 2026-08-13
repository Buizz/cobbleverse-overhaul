package dev.buizz.cobbleventure.bootstrap.client;

import com.mojang.blaze3d.shaders.FogShape;
import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Applies the server-resolved local weather without changing another player's view. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class LocalWeatherEffects {
    private static volatile String weather = "natural_clear";

    private LocalWeatherEffects() {}

    public static void apply(String value) {
        weather = value;
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(LocalWeatherEffects::applyNow);
    }

    public static String weather() {
        return weather;
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        applyNow();
    }

    private static void applyNow() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        boolean raining = switch (weather) {
            case "rain", "snow", "thunder", "natural_rain", "natural_thunder" -> true;
            default -> false;
        };
        boolean thundering = weather.equals("thunder")
            || weather.equals("natural_thunder");
        minecraft.level.getLevelData().setRaining(raining);
        minecraft.level.setRainLevel(raining ? 1.0F : 0.0F);
        minecraft.level.setThunderLevel(thundering ? 1.0F : 0.0F);
    }

    @SubscribeEvent
    public static void renderFog(ViewportEvent.RenderFog event) {
        if (!weather.equals("fog")) return;
        event.setNearPlaneDistance(6.0F);
        event.setFarPlaneDistance(42.0F);
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void colorFog(ViewportEvent.ComputeFogColor event) {
        if (!weather.equals("fog")) return;
        event.setRed(event.getRed() * 0.72F + 0.18F);
        event.setGreen(event.getGreen() * 0.76F + 0.18F);
        event.setBlue(event.getBlue() * 0.80F + 0.18F);
    }
}
