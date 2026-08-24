package dev.buizz.cobbleventure.casino.mixin;

import dev.buizz.cobbleventure.casino.BlackjackTableFacade;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops Playing Cards' own stack mutation and item recovery methods for casino decor. */
@Mixin(
    targets = {
        "com.ombremoon.playingcards.entity.EntityPokerChip",
        "com.ombremoon.playingcards.entity.EntityCard",
        "com.ombremoon.playingcards.entity.EntityCardDeck"
    },
    remap = false
)
abstract class PlayingCardsDecorationEntityMixin {
    @Inject(
        method = "tick", at = @At("HEAD"), cancellable = true,
        remap = false, require = 0
    )
    private void cobbleventure$freezeAuthoredDecoration(CallbackInfo callback) {
        Entity entity = (Entity)(Object)this;
        if (BlackjackTableFacade.isLockedPlayingCardsDecoration(entity)) {
            // EntityCard otherwise validates its saved DeckUUID every 20 ticks.
            // Structure placement remaps the deck entity UUID but cannot remap that
            // mod-specific field, so authored cards would discard themselves.
            callback.cancel();
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobbleventure$preventDecorationInteraction(
        Player player,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> callback
    ) {
        Entity entity = (Entity)(Object)this;
        if (!BlackjackTableFacade.isLockedPlayingCardsDecoration(entity)) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BlackjackTableFacade.showDecorationOnlyMessage(serverPlayer);
        }
        callback.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobbleventure$preventDecorationDamage(
        DamageSource source,
        float amount,
        CallbackInfoReturnable<Boolean> callback
    ) {
        Entity entity = (Entity)(Object)this;
        if (BlackjackTableFacade.isLockedPlayingCardsDecoration(entity)) {
            callback.setReturnValue(false);
        }
    }
}
