package dev.buizz.cobbleventure.casino;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Shared server-side gate for casino games that consume the virtual casino balance. */
public final class CasinoGameAccess {
    private CasinoGameAccess() {}

    public static boolean hasBalance(ServerPlayer player) {
        return net.narrnouille.cobblemoncasino.data.PlayerCasinoBalanceData
            .get(player.getServer()).getBalance(player.getUUID()) > 0L;
    }

    public static boolean denyIfEmpty(ServerPlayer player) {
        if (hasBalance(player)) return false;
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_casino.game.balance_required"), true);
        CasinoHudNetwork.syncNow(player);
        return true;
    }
}
