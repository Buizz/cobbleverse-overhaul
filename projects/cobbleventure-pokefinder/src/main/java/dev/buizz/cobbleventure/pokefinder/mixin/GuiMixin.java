package dev.buizz.cobbleventure.pokefinder.mixin;

import dev.buizz.cobbleventure.pokefinder.client.RadarMarkerRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(
        method = "renderTitle(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("TAIL")
    )
    private void cobbleventure$renderPokefinderMarkers(
        GuiGraphics graphics,
        DeltaTracker deltaTracker,
        CallbackInfo callback
    ) {
        RadarMarkerRenderer.render(graphics);
    }
}
