package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.item.PokeBallItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Prevents players from throwing Poké Balls outside a Cobblemon battle. */
final class BattleOnlyPokeBallUse {
    private BattleOnlyPokeBallUse() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(BattleOnlyPokeBallUse::onRightClickItem);
    }

    private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getItemStack().getItem() instanceof PokeBallItem)
            || BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_bootstrap.poke_ball_requires_battle"
        ), true);
    }
}
