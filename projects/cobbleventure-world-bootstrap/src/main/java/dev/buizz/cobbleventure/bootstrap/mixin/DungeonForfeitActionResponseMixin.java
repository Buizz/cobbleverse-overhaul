package dev.buizz.cobbleventure.bootstrap.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.ForfeitActionResponse;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMakeChoicePacket;
import com.cobblemon.mod.common.net.messages.client.battle.BattleQueueRequestPacket;
import com.cobblemon.mod.common.net.messages.server.battle.BattleSelectActionsPacket;
import com.cobblemon.mod.common.net.serverhandling.battle.BattleSelectActionsHandler;
import dev.buizz.cobbleventure.bootstrap.DungeonBattleRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Consumes forbidden dungeon forfeit packets before Cobblemon reports an invalid choice. */
@Mixin(value = BattleSelectActionsHandler.class, remap = false)
public abstract class DungeonForfeitActionResponseMixin {
    @Inject(
        method = "handle(Lcom/cobblemon/mod/common/net/messages/server/battle/BattleSelectActionsPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cobbleventure$enforceDungeonFleeRule(
        BattleSelectActionsPacket packet,
        MinecraftServer server,
        ServerPlayer player,
        CallbackInfo callback
    ) {
        boolean requestedForfeit = packet.getShowdownActionResponses().stream()
            .anyMatch(ForfeitActionResponse.class::isInstance);
        if (!requestedForfeit || DungeonBattleRules.allowsFlee(player)) return;

        callback.cancel();

        PokemonBattle battle = BattleRegistry.getBattle(packet.getBattleId());
        if (battle == null) return;
        for (BattleActor actor : battle.getActors()) {
            if (!containsPlayer(actor, player)) continue;
            if (actor.getRequest() != null) {
                actor.sendUpdate(new BattleQueueRequestPacket(actor.getRequest()));
            }
            actor.sendUpdate(new BattleMakeChoicePacket());
            return;
        }
    }

    private static boolean containsPlayer(BattleActor actor, ServerPlayer player) {
        for (java.util.UUID playerId : actor.getPlayerUUIDs()) {
            if (player.getUUID().equals(playerId)) return true;
        }
        return false;
    }
}
