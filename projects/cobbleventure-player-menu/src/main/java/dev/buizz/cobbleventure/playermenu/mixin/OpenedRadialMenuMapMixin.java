package dev.buizz.cobbleventure.playermenu.mixin;

import com.metacontent.cobblenav.client.gui.widget.button.IconButton;
import com.metacontent.cobblenav.client.gui.widget.button.PokenavButton;
import com.metacontent.cobblenav.client.gui.widget.radialmenu.OpenedRadialMenu;
import com.metacontent.cobblenav.client.gui.widget.radialmenu.RadialPopupMenu;
import com.metacontent.cobblenav.os.PokenavOS;
import dev.buizz.cobbleventure.playermenu.ProgressionNetwork;
import dev.buizz.cobbleventure.playermenu.client.PlayerMenuClient;
import java.util.List;
import kotlin.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuses CobbleNav's unfinished map slot for Cobbleventure's progression-aware map. */
@Mixin(OpenedRadialMenu.class)
abstract class OpenedRadialMenuMapMixin {
    @Unique private static final int MAP_BUTTON_INDEX = 0;

    @Shadow @Final private List<IconButton> buttons;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cobbleventure$installWorldMapButton(
        PokenavOS os,
        RadialPopupMenu popup,
        int x,
        int y,
        CallbackInfo callback
    ) {
        buttons.get(MAP_BUTTON_INDEX).setDisabled(!ProgressionNetwork.clientSnapshot().map());
    }

    @Inject(method = "buttons$lambda$2", at = @At("HEAD"), cancellable = true)
    private static void cobbleventure$openWorldMap(
        RadialPopupMenu popup,
        PokenavOS os,
        PokenavButton button,
        CallbackInfoReturnable<Unit> callback
    ) {
        PlayerMenuClient.openWorldMap();
        callback.setReturnValue(Unit.INSTANCE);
    }
}
