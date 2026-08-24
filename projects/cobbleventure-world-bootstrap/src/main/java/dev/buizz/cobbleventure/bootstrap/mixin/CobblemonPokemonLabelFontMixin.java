package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps in-world Pokémon labels visible with Iris while Caxton handles GUI text. */
@Mixin(PokemonRenderer.class)
public abstract class CobblemonPokemonLabelFontMixin {
    private static final ResourceLocation WORLD_LABEL_FONT =
        ResourceLocation.withDefaultNamespace("uniform");
    private static final ResourceLocation CHALLENGE_LABEL_FONT =
        ResourceLocation.withDefaultNamespace("default");

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

    @ModifyArg(
        method = "renderNameTag(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
            ordinal = 2
        ),
        index = 0
    )
    private Component cobbleventure$useDefaultFontForChallengeShadow(Component label) {
        return useDefaultChallengeFont(label);
    }

    @ModifyArg(
        method = "renderNameTag(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
            ordinal = 3
        ),
        index = 0
    )
    private Component cobbleventure$useDefaultFontForChallenge(Component label) {
        return useDefaultChallengeFont(label);
    }

    private static Component useDefaultChallengeFont(Component label) {
        return label.copy().withStyle(style -> style.withFont(CHALLENGE_LABEL_FONT));
    }
}
