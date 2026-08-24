package dev.buizz.cobbleventure.casino.mixin;

import dev.buizz.cobbleventure.casino.CasinoGameAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects empty-balance players before a slot or blackjack menu is opened or locked. */
@Mixin(targets = {
    "net.narrnouille.cobblemoncasino.block.custom.SlotMachineBlock",
    "net.narrnouille.cobblemoncasino.block.custom.BlackjackTableBlock"
}, remap = false)
abstract class CasinoMachineUseMixin {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobbleventure$requireBalanceBeforeOpening(
        BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit,
        CallbackInfoReturnable<InteractionResult> callback
    ) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
            && CasinoGameAccess.denyIfEmpty(serverPlayer)) {
            callback.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
