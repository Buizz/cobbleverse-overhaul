package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent;
import com.cobblemon.mod.common.block.PCBlock;
import com.cobblemon.mod.common.trade.PlayerTradeParticipant;
import com.cobblemon.mod.common.trade.TradeManager;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Prevents party mutation through Cobblemon UI while dungeon entry is locked. */
final class DungeonWaitingPokemonAccess {
    private static boolean registered;

    private DungeonWaitingPokemonAccess() {}

    static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST, DungeonWaitingPokemonAccess::onPcInteract
        );
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST, DungeonWaitingPokemonAccess::onPlayerInteract
        );
        NeoForge.EVENT_BUS.addListener(
            EventPriority.HIGHEST, DungeonWaitingPokemonAccess::onCommand
        );
        CobblemonEvents.TRADE_EVENT_PRE.subscribe(
            (Consumer<TradeEvent.Pre>) DungeonWaitingPokemonAccess::onTrade
        );
    }

    static boolean hasActiveTrade(ServerPlayer player) {
        return TradeManager.INSTANCE.getActiveTrade(player.getUUID()) != null;
    }

    static void cancelTradeRequests(ServerPlayer player) {
        var manager = TradeManager.INSTANCE;
        var outbound = manager.getOutboundRequest(player.getUUID());
        if (outbound != null) manager.cancelRequest(outbound, true);
        for (var inbound : List.copyOf(manager.getInboundRequests(player.getUUID()))) {
            manager.cancelRequest(inbound, true);
        }
    }

    static void cancelTradeActivity(ServerPlayer player) {
        cancelTradeRequests(player);
        var active = TradeManager.INSTANCE.getActiveTrade(player.getUUID());
        if (active == null) return;
        active.cancelTrade();
        player.sendSystemMessage(Component.literal(
            "[던전] 매칭 대기 중에는 포켓몬을 교환할 수 없습니다."
        ));
    }

    private static void onPcInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
            || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)
            || !DungeonSystem.entryPartyLocked(player.getUUID())
            || !(event.getLevel().getBlockState(event.getPos()).getBlock()
                instanceof PCBlock)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.displayClientMessage(Component.literal(
            "[던전] 입장 확인 또는 매칭 대기 중에는 포켓몬 PC를 사용할 수 없습니다."
        ), true);
    }

    private static void onPlayerInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()
            || event.getHand() != InteractionHand.MAIN_HAND
            || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getTarget() instanceof ServerPlayer target)
            || (!DungeonSystem.entryPartyLocked(player.getUUID())
                && !DungeonSystem.entryPartyLocked(target.getUUID()))) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.displayClientMessage(Component.literal(
            "[던전] 입장 확인 또는 매칭 대기 중에는 포켓몬 교환을 시작할 수 없습니다."
        ), true);
    }

    private static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity()
            instanceof ServerPlayer player)
            || !DungeonSystem.entryPartyLocked(player.getUUID())) {
            return;
        }
        String command = event.getParseResults().getReader().getString().stripLeading();
        if (command.startsWith("/")) command = command.substring(1);
        String root = command.split("\\s+", 2)[0];
        if (!root.equalsIgnoreCase("pc")) return;
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal(
            "[던전] 입장 확인 또는 매칭 대기 중에는 포켓몬 PC를 사용할 수 없습니다."
        ));
    }

    private static void onTrade(TradeEvent.Pre event) {
        ServerPlayer blocked = null;
        if (event.getTradeParticipant1() instanceof PlayerTradeParticipant participant
            && DungeonSystem.entryPartyLocked(participant.getPlayer().getUUID())) {
            blocked = participant.getPlayer();
        } else if (event.getTradeParticipant2() instanceof PlayerTradeParticipant participant
            && DungeonSystem.entryPartyLocked(participant.getPlayer().getUUID())) {
            blocked = participant.getPlayer();
        }
        if (blocked == null) return;
        event.cancel();
        blocked.sendSystemMessage(Component.literal(
            "[던전] 입장 확인 또는 매칭 대기 중에는 포켓몬을 교환할 수 없습니다."
        ));
    }
}
