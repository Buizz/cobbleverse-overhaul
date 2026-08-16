package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.keybind.keybinds.PartySendBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps forced wild encounters modal instead of allowing R to minimise them. */
@Mixin(value = PartySendBinding.class, remap = false)
public abstract class PartySendBindingMixin {
    @Inject(method = "onRelease", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$keepPursuitBattleOpen(CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (minecraft.player == null || battle == null || battle.getWildActor() == null
            || !isPursuitDimension(minecraft.player.level().dimension().location())) {
            return;
        }
        battle.setMinimised(false);
        if (!(minecraft.screen instanceof BattleGUI)) {
            minecraft.setScreen(new BattleGUI());
        }
        callback.cancel();
    }

    private static boolean isPursuitDimension(ResourceLocation dimension) {
        return dimension.getNamespace().equals("cobbleventure")
            && (dimension.getPath().equals("dungeons")
                || dimension.getPath().equals("forests"));
    }
}
