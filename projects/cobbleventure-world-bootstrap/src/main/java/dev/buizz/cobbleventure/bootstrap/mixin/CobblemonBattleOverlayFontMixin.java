package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.CobblemonResources;
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
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
}
