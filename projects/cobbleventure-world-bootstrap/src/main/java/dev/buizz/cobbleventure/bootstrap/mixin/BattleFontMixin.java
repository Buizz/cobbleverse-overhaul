package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keeps every Cobblemon battle label on the active global default font. */
@Mixin(targets = "com.cobblemon.mod.common.client.render.RenderHelperKt", remap = false)
public abstract class BattleFontMixin {
    private static final ResourceLocation BATTLE_FONT =
        ResourceLocation.withDefaultNamespace("default");
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
            return BATTLE_FONT;
        }
        return requested;
    }

    @ModifyVariable(
        method = {
            DRAW_SCALED_TEXT,
            DRAW_SCALED_TEXT_RIGHT
        },
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private static MutableComponent cobbleventure$useRegularBattleText(MutableComponent text) {
        if (!(Minecraft.getInstance().screen instanceof BattleGUI)) {
            return text;
        }

        // Minecraft simulates bold by drawing each glyph again one pixel to the
        // right. On the resource-pack pixel font this looks like doubled text,
        // especially on small symbols such as the gender marker.
        return text.copy().withStyle(style -> style.withBold(false));
    }

    @ModifyVariable(
        method = {
            DRAW_SCALED_TEXT,
            DRAW_SCALED_TEXT_RIGHT
        },
        at = @At("HEAD"), argsOnly = true, ordinal = 0
    )
    private static float cobbleventure$snapBattleFontScale(float requested) {
        if (!(Minecraft.getInstance().screen instanceof BattleGUI)) {
            return requested;
        }

        // A bitmap-font texel stays sharp only when it covers a whole number
        // of physical screen pixels. Cobblemon uses 0.5-scale labels in a few
        // places, which otherwise land on 1.5 pixels at GUI scale 3.
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int physicalPixels = Math.max(1, (int) Math.round(requested * guiScale));
        return (float) (physicalPixels / guiScale);
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
