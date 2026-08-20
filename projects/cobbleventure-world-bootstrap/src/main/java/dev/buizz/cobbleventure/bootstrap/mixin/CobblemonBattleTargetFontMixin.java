package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.CobblemonResources;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps Pokémon names on the battle target screen on the global font. */
@Mixin(BattleTargetSelection.TargetTile.class)
public abstract class CobblemonBattleTargetFontMixin {
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
