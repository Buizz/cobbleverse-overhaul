package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.item.PokeBallItem;
import dev.buizz.cobbleventure.bootstrap.DungeonBattleRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents Poké Ball consumption and throwing when capture is disabled. */
@Mixin(value = PokeBallItem.class, remap = false)
public abstract class DungeonPokeBallItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void cobbleventure$enforceDungeonCaptureRule(
        Level level,
        Player player,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResultHolder<ItemStack>> callback
    ) {
        if (player instanceof ServerPlayer serverPlayer
            && !DungeonBattleRules.allowsCapture(serverPlayer)) {
            callback.setReturnValue(InteractionResultHolder.fail(
                player.getItemInHand(hand)
            ));
        }
    }
}
