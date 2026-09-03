package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.api.storage.player.client.ClientGeneralPlayerData;
import com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.util.LocalizationUtilsKt;
import dev.buizz.cobbleventure.bootstrap.client.PokemonChallengeLabelFont;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    /**
     * Cobblemon hides the challenge hint after the player's first battle win.
     * Cobbleventure uses the hint as a permanent field interaction guide, so
     * keep it enabled while leaving Cobblemon's canBattle check intact.
     */
    @Redirect(
        method = "renderNameTag(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/api/storage/player/client/ClientGeneralPlayerData;getShowChallengeLabel()Z"
        )
    )
    private boolean cobbleventure$alwaysShowChallengeLabel(
        ClientGeneralPlayerData playerData
    ) {
        return true;
    }

    // Resolve the font before width measurement, not just before the draw calls.
    @Redirect(
        method = "renderNameTag(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/util/LocalizationUtilsKt;lang(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"
        )
    )
    private MutableComponent cobbleventure$useVanillaFontForChallenge(String key, Object[] arguments) {
        MutableComponent label = LocalizationUtilsKt.lang(key, arguments);
        return "challenge_label".equals(key) ? PokemonChallengeLabelFont.apply(label) : label;
    }
}
