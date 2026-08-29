package dev.buizz.cobbleventure.playermenu.mixin;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Cobblemon XP Bar compatible with Cobbleventure's battle HUD. */
@Mixin(targets = "com.cobblemonxpbar.client.XPBarOverlay", remap = false)
abstract class XPBarOverlayMixin {
    private static final int HEADER_Y_OFFSET = 4;

    @Inject(
        method = "lambda$registerOverlays$3",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        remap = false
    )
    private static void cobbleventure$hideXpBarInMultiPokemonBattles(
        GuiGraphics graphics,
        DeltaTracker deltaTracker,
        CallbackInfo callback
    ) {
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle != null && (hasMultipleActivePokemon(battle.getSide1())
            || hasMultipleActivePokemon(battle.getSide2()))) {
            callback.cancel();
        }
    }

    private static boolean hasMultipleActivePokemon(ClientBattleSide side) {
        int activePokemon = 0;
        for (var actor : side.getActors()) {
            activePokemon += actor.getActivePokemon().size();
            if (activePokemon > 1) {
                return true;
            }
        }
        return false;
    }

    @ModifyConstant(
        method = "lambda$registerOverlays$3",
        constant = @Constant(intValue = 32),
        require = 1,
        remap = false
    )
    private static int cobbleventure$moveXpBarIntoHeaderGap(int originalOffset) {
        return HEADER_Y_OFFSET;
    }
}
