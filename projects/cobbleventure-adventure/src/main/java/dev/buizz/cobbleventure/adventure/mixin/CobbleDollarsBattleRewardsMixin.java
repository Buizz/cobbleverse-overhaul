package dev.buizz.cobbleventure.adventure.mixin;

import fr.harmex.cobbledollars.common.utils.MiscUtilsKt;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disables CobbleDollars' built-in battle payout in favor of NPC-configured rewards. */
@Mixin(value = MiscUtilsKt.class, remap = false)
public abstract class CobbleDollarsBattleRewardsMixin {
    @Inject(method = "calculateAndAwardCobbleDollars", at = @At("HEAD"), cancellable = true)
    private static void cobbleventure$disableAutomaticBattleMoney(
        ServerPlayer player, List<Integer> pokemonLevels, CallbackInfo callback
    ) {
        callback.cancel();
    }
}
