package dev.buizz.cobbleventure.pokefinder.mixin;

import com.metacontent.cobblenav.client.gui.screen.pokefinder.PokefinderScreen;
import dev.buizz.cobbleventure.pokefinder.client.RadarSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CobbleNav 2.3.3 compatibility hook for the integrated exploration settings. */
@Mixin(PokefinderScreen.class)
abstract class PokefinderScreenMixin extends Screen {
    @Shadow private int screenX;
    @Shadow private int screenY;

    protected PokefinderScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void cobbleventure$addExplorationSettings(CallbackInfo callback) {
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_pokefinder.exploration_settings"),
            button -> Minecraft.getInstance().setScreen(
                new RadarSettingsScreen((Screen) (Object) this)
            )
        ).bounds(screenX + 30, screenY + 1, 78, 18).build());
    }
}
