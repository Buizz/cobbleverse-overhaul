package dev.buizz.cobbleventure.adventure;

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
        // A client may already have predicted PokeBallItem.use and reduced its
        // hotbar stack. Send the authoritative inventory even though the server
        // never consumed the item, so modded clients cannot remain desynchronised.
        player.containerMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) {
            player.inventoryMenu.sendAllDataToRemote();
        }
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_bootstrap.poke_ball_requires_battle"
        ), true);
    }
}
