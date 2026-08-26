package dev.buizz.cobbleventure.pokefinder.mixin;

import dev.buizz.cobbleventure.pokefinder.client.PinnedPokefinderHud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers that the player reached the integrated Pokefinder through PokéNav. */
@Mixin(targets = "com.metacontent.cobblenav.client.gui.screen.MainScreen")
abstract class MainScreenMixin extends Screen {
    protected MainScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "initScreen", at = @At("TAIL"))
    private void cobbleventure$markPokenavOpened(CallbackInfo callback) {
        PinnedPokefinderHud.markPokenavOpened();
    }
}
