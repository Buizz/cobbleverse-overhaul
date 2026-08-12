package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes Cobblemon's final "forfeit" tile from trainer battles. */
@Mixin(value = BattleGeneralActionSelection.class, remap = false)
public abstract class BattleGeneralActionSelectionMixin {
    @Shadow
    public abstract List<BattleOptionTile> getTiles();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cobbleventure$removeTrainerForfeit(
        BattleGUI battleGUI, SingleActionRequest request, CallbackInfo callback
    ) {
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null || !hasNpcActor(battle)) return;
        List<BattleOptionTile> tiles = getTiles();
        if (!tiles.isEmpty()) tiles.removeLast();
    }

    private static boolean hasNpcActor(ClientBattle battle) {
        for (ClientBattleSide side : battle.getSides()) {
            if (side.getActors().stream().anyMatch(actor -> actor.getType() == ActorType.NPC)) {
                return true;
            }
        }
        return false;
    }
}
