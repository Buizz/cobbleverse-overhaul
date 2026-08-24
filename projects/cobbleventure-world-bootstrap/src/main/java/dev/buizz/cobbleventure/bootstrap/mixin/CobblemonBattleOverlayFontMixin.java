package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.CobblemonResources;
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets battle HUD labels follow the active global Minecraft font. */
@Mixin(BattleOverlay.class)
public abstract class CobblemonBattleOverlayFontMixin {
    private static final ResourceLocation GLOBAL_FONT =
        ResourceLocation.withDefaultNamespace("default");

    @Redirect(
        method = "drawBattleTile",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/client/CobblemonResources;getDEFAULT_LARGE()Lnet/minecraft/resources/ResourceLocation;"
        )
    )
    private ResourceLocation cobbleventure$useGlobalFont(CobblemonResources resources) {
        return GLOBAL_FONT;
    }

    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/client/render/RenderHelperKt;drawScaledText$default(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/chat/MutableComponent;Ljava/lang/Number;Ljava/lang/Number;FLjava/lang/Number;IIZZLjava/lang/Integer;Ljava/lang/Integer;ILjava/lang/Object;)V"
        ),
        index = 1
    )
    private ResourceLocation cobbleventure$useGlobalFontForBattleActionPrompt(
        ResourceLocation originalFont
    ) {
        return GLOBAL_FONT;
    }
}
