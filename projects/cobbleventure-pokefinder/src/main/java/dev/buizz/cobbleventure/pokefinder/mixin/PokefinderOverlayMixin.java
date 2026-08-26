package dev.buizz.cobbleventure.pokefinder.mixin;

import com.metacontent.cobblenav.client.gui.overlay.PokefinderOverlay;
import dev.buizz.cobbleventure.pokefinder.client.PinnedPokefinderHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a held legacy item from bypassing the PokéNav HUD switch. */
@Mixin(PokefinderOverlay.class)
abstract class PokefinderOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$onlyRenderFromPokenav(
        GuiGraphics graphics,
        DeltaTracker deltaTracker,
        CallbackInfo callback
    ) {
        if (!PinnedPokefinderHud.isRenderingIntegratedOverlay()) callback.cancel();
    }
}
