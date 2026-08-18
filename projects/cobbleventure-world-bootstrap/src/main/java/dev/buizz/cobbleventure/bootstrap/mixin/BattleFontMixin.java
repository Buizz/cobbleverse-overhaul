package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keeps every Cobblemon battle label on the active resource pack's default font. */
@Mixin(targets = "com.cobblemon.mod.common.client.render.RenderHelperKt", remap = false)
public abstract class BattleFontMixin {
    private static final ResourceLocation DEFAULT_FONT = ResourceLocation.withDefaultNamespace("default");
    private static final String DRAW_SCALED_TEXT =
        "drawScaledText(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/chat/MutableComponent;Ljava/lang/Number;Ljava/lang/Number;FLjava/lang/Number;IIZZLjava/lang/Integer;Ljava/lang/Integer;)V";
    private static final String DRAW_SCALED_TEXT_RIGHT =
        "drawScaledTextJustifiedRight(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/chat/MutableComponent;Ljava/lang/Number;Ljava/lang/Number;FLjava/lang/Number;IIZ)V";

    @ModifyVariable(
        method = {
            DRAW_SCALED_TEXT,
            DRAW_SCALED_TEXT_RIGHT
        },
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private static ResourceLocation cobbleventure$useBattleFont(ResourceLocation requested) {
        if (Minecraft.getInstance().screen instanceof BattleGUI) {
            return DEFAULT_FONT;
        }
        return requested;
    }

    @ModifyVariable(
        method = DRAW_SCALED_TEXT,
        at = @At("HEAD"), argsOnly = true, ordinal = 1
    )
    private static boolean cobbleventure$disableBattleFontShadow(
        boolean requested
    ) {
        return Minecraft.getInstance().screen instanceof BattleGUI
            ? false : requested;
    }

    @ModifyVariable(
        method = DRAW_SCALED_TEXT_RIGHT,
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private static boolean cobbleventure$disableRightAlignedBattleFontShadow(
        boolean requested
    ) {
        return Minecraft.getInstance().screen instanceof BattleGUI
            ? false : requested;
    }
}
