package dev.buizz.cobbleventure.pokefinder.mixin;

import dev.buizz.cobbleventure.pokefinder.client.PinnedPokefinderHud;
import dev.buizz.cobbleventure.pokefinder.client.PokefinderHudPosition;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the built-in Pokefinder controls to the PokéNav home screen. */
@Mixin(targets = "com.metacontent.cobblenav.client.gui.screen.MainScreen")
abstract class MainScreenMixin extends Screen {
    @Unique private Button cobbleventure$hudToggle;
    @Unique private Button cobbleventure$positionToggle;

    protected MainScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "initScreen", at = @At("TAIL"))
    private void cobbleventure$addPokefinderControls(CallbackInfo callback) {
        PokenavScreenAccessor screen = (PokenavScreenAccessor) (Object) this;
        PinnedPokefinderHud.markPokenavOpened();

        int x = screen.cobbleventure$getScreenX() + 196;
        int y = screen.cobbleventure$getScreenY() + 190;
        cobbleventure$hudToggle = Button.builder(
            cobbleventure$hudLabel(),
            button -> {
                PinnedPokefinderHud.toggleEnabled();
                cobbleventure$refreshLabels();
            }
        ).bounds(x, y, 132, 18).build();
        cobbleventure$positionToggle = Button.builder(
            cobbleventure$positionLabel(),
            button -> {
                PinnedPokefinderHud.togglePosition();
                cobbleventure$refreshLabels();
            }
        ).bounds(x, y + 21, 132, 18).build();

        screen.cobbleventure$addUnblockableWidget(cobbleventure$hudToggle);
        screen.cobbleventure$addUnblockableWidget(cobbleventure$positionToggle);
        cobbleventure$refreshLabels();
    }

    @Unique
    private void cobbleventure$refreshLabels() {
        if (cobbleventure$hudToggle != null) {
            cobbleventure$hudToggle.setMessage(cobbleventure$hudLabel());
        }
        if (cobbleventure$positionToggle != null) {
            cobbleventure$positionToggle.setMessage(cobbleventure$positionLabel());
            cobbleventure$positionToggle.active = PinnedPokefinderHud.enabled();
        }
    }

    @Unique
    private static Component cobbleventure$hudLabel() {
        return Component.translatable(
            "screen.cobbleventure_pokefinder.pokenav.hud",
            Component.translatable(
                PinnedPokefinderHud.enabled()
                    ? "screen.cobbleventure_pokefinder.state.on"
                    : "screen.cobbleventure_pokefinder.state.off"
            )
        );
    }

    @Unique
    private static Component cobbleventure$positionLabel() {
        PokefinderHudPosition position = PinnedPokefinderHud.position();
        return Component.translatable(
            "screen.cobbleventure_pokefinder.pokenav.position",
            Component.translatable(
                position == PokefinderHudPosition.LEFT
                    ? "screen.cobbleventure_pokefinder.position.left"
                    : "screen.cobbleventure_pokefinder.position.right"
            )
        );
    }
}
