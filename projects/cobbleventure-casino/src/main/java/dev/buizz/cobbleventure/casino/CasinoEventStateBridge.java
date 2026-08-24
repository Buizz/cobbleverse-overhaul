package dev.buizz.cobbleventure.casino;

import net.minecraft.server.level.ServerPlayer;

/** Read-only values exposed to the optional V5 event runtime through reflection. */
public final class CasinoEventStateBridge {
    private CasinoEventStateBridge() {}

    public static long casinoBalance(ServerPlayer player) {
        return net.narrnouille.cobblemoncasino.data.PlayerCasinoBalanceData
            .get(player.getServer()).getBalance(player.getUUID());
    }

    public static int ticketPrice(String profile) {
        return Math.toIntExact(machine(profile).ticket.price);
    }

    public static int ticketPurchaseMin(String profile) {
        return machine(profile).ticket.purchase_min;
    }

    public static int ticketPurchaseMax(String profile) {
        return machine(profile).ticket.purchase_max;
    }

    private static GachaCatalog.Machine machine(String profile) {
        return CobbleventureCasino.catalog().machine(profile).orElseThrow(() ->
            new IllegalArgumentException("가챠 기계 프로필을 찾을 수 없습니다: " + profile)
        );
    }
}
