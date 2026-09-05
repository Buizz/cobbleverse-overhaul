package dev.buizz.cobbleventure.pokefinder.mixin;

import com.metacontent.cobblenav.client.gui.widget.button.IconButton;
import com.metacontent.cobblenav.client.gui.widget.button.PokenavButton;
import com.metacontent.cobblenav.client.gui.widget.radialmenu.OpenedRadialMenu;
import com.metacontent.cobblenav.client.gui.widget.radialmenu.RadialPopupMenu;
import com.metacontent.cobblenav.os.PokenavOS;
import dev.buizz.cobbleventure.pokefinder.client.PokefinderRadarClient;
import dev.buizz.cobbleventure.pokefinder.client.PinnedPokefinderHud;
import java.util.List;
import kotlin.Unit;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuses CobbleNav's unfinished contacts slot as the integrated Pokefinder control. */
@Mixin(OpenedRadialMenu.class)
abstract class OpenedRadialMenuMixin {
    @Unique private static final int CONTACTS_BUTTON_INDEX = 2;
    @Unique private static final ResourceLocation POKEFINDER_ICON = ResourceLocation.fromNamespaceAndPath(
        "cobblenav", "textures/gui/button/pokefinder_button.png"
    );

    @Shadow @Final private List<IconButton> buttons;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cobbleventure$installPokefinderButton(
        PokenavOS os,
        RadialPopupMenu popup,
        int x,
        int y,
        CallbackInfo callback
    ) {
        IconButton button = buttons.get(CONTACTS_BUTTON_INDEX);
        button.setTexture(POKEFINDER_ICON);
        button.setDisabled(!PinnedPokefinderHud.pokenavAvailable());
    }

    @Inject(method = "buttons$lambda$4", at = @At("HEAD"), cancellable = true)
    private static void cobbleventure$openPokefinder(
        RadialPopupMenu popup,
        PokenavOS os,
        PokenavButton button,
        CallbackInfoReturnable<Unit> callback
    ) {
        PokefinderRadarClient.cycleHud();
        if (popup.getParentScreen() != null) popup.getParentScreen().onClose();
        callback.setReturnValue(Unit.INSTANCE);
    }
}
