package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps in-world Pokémon labels visible with Iris while Caxton handles GUI text. */
@Mixin(PokemonRenderer.class)
public abstract class CobblemonPokemonLabelFontMixin {
    private static final ResourceLocation WORLD_LABEL_FONT =
        ResourceLocation.withDefaultNamespace("uniform");

    @Inject(method = "resolveBaseLabel", at = @At("RETURN"), cancellable = true)
    private void cobbleventure$useWorldLabelFont(
        PokemonEntity entity,
        CallbackInfoReturnable<MutableComponent> callback
    ) {
        MutableComponent label = callback.getReturnValue();
        if (label != null) {
            callback.setReturnValue(
                label.copy().withStyle(style -> style.withFont(WORLD_LABEL_FONT))
            );
        }
    }
}
