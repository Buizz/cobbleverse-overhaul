package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps empty doubles slots in place without fabricating a Pokemon or changing target IDs. */
@Mixin(value = BattleTargetSelection.TargetTile.class, remap = false)
public abstract class BattleTargetEmptySlotMixin {
    @Shadow public abstract ActiveClientBattlePokemon getTarget();

    // Cobblemon 1.7.3's isCurrentPokemon initializer alone uses target.battlePokemon!!.uuid.
    // These paired redirects make that dereference nullable; render already skips empty slots.
    // Keep the match count strict so an upstream constructor change fails at load time.
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lkotlin/jvm/internal/Intrinsics;checkNotNull(Ljava/lang/Object;)V"),
        require = 1, allow = 1)
    protected void cobbleventure$allowEmptyTargetForUuidComparison(Object pokemon) {
        // Deliberately allow null only at this constructor's single non-null assertion.
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/cobblemon/mod/common/client/battle/ClientBattlePokemon;getUuid()Ljava/util/UUID;",
        ordinal = 1), require = 1, allow = 1)
    protected UUID cobbleventure$nullableTargetUuid(ClientBattlePokemon pokemon) {
        return pokemon == null ? null : pokemon.getUuid();
    }

    @Inject(method = "getSelectable", at = @At("HEAD"), cancellable = true, require = 1)
    protected void cobbleventure$disableEmptyTarget(CallbackInfoReturnable<Boolean> callback) {
        // Preserve Cobblemon's move-specific eligibility for occupied slots, including spread moves.
        // Read the current slot contents: a slot may become empty after this tile was created.
        if (getTarget().getBattlePokemon() == null) callback.setReturnValue(false);
    }

    @Inject(method = "isHovered", at = @At("HEAD"), cancellable = true, require = 1)
    protected void cobbleventure$ignoreEmptyTargetHitbox(CallbackInfoReturnable<Boolean> callback) {
        // Spread-move tiles share hover detection. An empty tile must not win firstOrNull
        // in mousePrimaryClicked and swallow a click intended for an occupied target.
        if (getTarget().getBattlePokemon() == null) callback.setReturnValue(false);
    }
}
