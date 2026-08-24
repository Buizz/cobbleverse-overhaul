package dev.buizz.cobbleventure.casino;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

final class GachaTicketVendor {
    static final String VENDOR_TAG = "cobbleventure_npc/cobbleventure/npc/gacha_ticket_vendor";
    private static final double RANGE = 8.0D;

    private GachaTicketVendor() {}

    static int buy(ServerPlayer player, GachaCatalog.Machine machine, int amount) {
        if (!nearVendor(player)) {
            player.displayClientMessage(Component.literal("가챠 티켓 교환상 가까이에서 이용해 주세요."), true);
            return 0;
        }
        if (!CasinoCashier.hasCoinCase(player)) {
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_casino.cashier.coin_case_required"), true);
            return 0;
        }
        if (amount < machine.ticket.purchase_min || amount > machine.ticket.purchase_max) {
            player.displayClientMessage(Component.literal("구매 수량은 " + machine.ticket.purchase_min
                + "~" + machine.ticket.purchase_max + "장이어야 합니다."), true);
            return 0;
        }
        long price;
        try {
            price = Math.multiplyExact(machine.ticket.price, amount);
        } catch (ArithmeticException overflow) {
            player.displayClientMessage(Component.literal("구매 금액이 너무 큽니다."), true);
            return 0;
        }
        var balances = net.narrnouille.cobblemoncasino.data.PlayerCasinoBalanceData
            .get(player.getServer());
        long before = balances.getBalance(player.getUUID());
        if (before < price) {
            player.displayClientMessage(Component.literal("카지노 칩이 부족합니다. 필요: " + price
                + " · 보유: " + before), true);
            return 0;
        }
        GachaTickets.give(player, machine, amount);
        balances.setBalance(player.getUUID(), before - price);
        CasinoHudNetwork.syncNow(player);
        player.displayClientMessage(Component.literal(machine.ticket.display_name + " ×" + amount
            + "을(를) 구매했습니다. 남은 카지노 칩: " + (before - price)), true);
        return 1;
    }

    private static boolean nearVendor(ServerPlayer player) {
        AABB area = new AABB(player.blockPosition()).inflate(RANGE);
        return !player.serverLevel().getEntities((Entity)null, area,
            entity -> entity.isAlive() && entity.getTags().contains(VENDOR_TAG)).isEmpty();
    }
}
