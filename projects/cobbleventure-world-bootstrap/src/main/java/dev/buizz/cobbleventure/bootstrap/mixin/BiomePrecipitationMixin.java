package dev.buizz.cobbleventure.bootstrap.mixin;

import dev.buizz.cobbleventure.bootstrap.client.LocalWeatherEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes explicitly authored rain and snow independent of the biome temperature. */
@Mixin(Biome.class)
abstract class BiomePrecipitationMixin {
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$useLocalPrecipitation(
        BlockPos position,
        CallbackInfoReturnable<Biome.Precipitation> callback
    ) {
        switch (LocalWeatherEffects.weather()) {
            case "clear", "fog" -> callback.setReturnValue(Biome.Precipitation.NONE);
            case "rain" -> callback.setReturnValue(Biome.Precipitation.RAIN);
            case "snow" -> callback.setReturnValue(Biome.Precipitation.SNOW);
            default -> { }
        }
    }
}
