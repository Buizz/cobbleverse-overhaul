package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.CobblemonResources;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets move names follow the active global Minecraft font. */
@Mixin(BattleMoveSelection.MoveTile.class)
public abstract class CobblemonBattleMoveFontMixin {
    private static final ResourceLocation GLOBAL_FONT =
        ResourceLocation.withDefaultNamespace("default");

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/client/CobblemonResources;getDEFAULT_LARGE()Lnet/minecraft/resources/ResourceLocation;"
        )
    )
    private ResourceLocation cobbleventure$useGlobalFont(CobblemonResources resources) {
        return GLOBAL_FONT;
    }
}
