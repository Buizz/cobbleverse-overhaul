package dev.buizz.cobbleventure.casino.mixin;

import dev.buizz.cobbleventure.casino.CobbleventureCasino;
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

/** Keeps the original machine model while routing authored machines to Cobbleventure gacha. */
@Mixin(targets = {
    "net.narrnouille.cobblemoncasino.block.custom.GachaMachineBlock",
    "net.narrnouille.cobblemoncasino.block.custom.PokemonGachaMachineBlock",
    "net.narrnouille.cobblemoncasino.block.custom.EventGachaMachineBlock",
    "net.narrnouille.cobblemoncasino.block.custom.PlushiesGachaMachineBlock"
}, remap = false)
abstract class CasinoGachaMachineUseMixin {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobbleventure$useConfiguredGacha(
        BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit,
        CallbackInfoReturnable<InteractionResult> callback
    ) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
            && CobbleventureCasino.useConfiguredMachine(serverPlayer, position)) {
            callback.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
