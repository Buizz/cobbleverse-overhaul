package dev.buizz.cobbleventure.playermenu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Places Cobblemon XP Bar between the name header and HP row. */
@Mixin(targets = "com.cobblemonxpbar.client.XPBarOverlay", remap = false)
abstract class XPBarOverlayMixin {
    private static final int HEADER_Y_OFFSET = 4;

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
