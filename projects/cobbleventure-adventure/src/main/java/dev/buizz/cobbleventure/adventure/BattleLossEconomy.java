package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Applies the main-series-style money penalty for a lost or forfeited battle. */
final class BattleLossEconomy {
    private static final BigInteger MONEY_PER_HIGHEST_LEVEL = BigInteger.valueOf(20L);
    private static final long SETTLEMENT_RETENTION_TICKS = 20L * 60L * 5L;
    private static final Map<SettlementKey, StoredSettlement> SETTLEMENTS = new HashMap<>();

    private BattleLossEconomy() {}

    static Settlement settle(BattleVictoryEvent event, PlayerBattleActor loser) {
        return settle(
            event.getBattle().getBattleId(),
            loser,
            event.getWinners(),
            event.getBattle().isPvW(),
            false
        );
    }

    static Settlement settle(BattleFledEvent event) {
        PlayerBattleActor loser = event.getPlayer();
        List<BattleActor> opponents = new ArrayList<>();
        for (BattleActor actor : event.getBattle().getActors()) {
            if (actor.getSide() != loser.getSide()) {
                opponents.add(actor);
            }
        }
        return settle(
            event.getBattle().getBattleId(),
            loser,
            opponents,
            event.getBattle().isPvW(),
            true
        );
    }

    static void announce(ServerPlayer player, Settlement settlement) {
        if (settlement == null || settlement.announced) {
            return;
        }
        settlement.announced = true;
        if (settlement.forfeited) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_bootstrap.battle_forfeit",
                player.getDisplayName()
            ));
        }
        String amount = NumberFormat.getIntegerInstance(Locale.US).format(settlement.amount);
        player.sendSystemMessage(settlement.wild
            ? Component.translatable(
                "message.cobbleventure_bootstrap.battle_money_lost", amount
            )
            : Component.translatable(
                "message.cobbleventure_bootstrap.battle_money_paid",
                amount,
                settlement.opponentName
            )
        );
    }

    static void cleanup(long gameTime) {
        SETTLEMENTS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= gameTime);
    }

    private static Settlement settle(
        UUID battleId,
        PlayerBattleActor loserActor,
        Iterable<? extends BattleActor> opponents,
        boolean wildBattle,
        boolean forfeited
    ) {
        ServerPlayer loser = loserActor.getEntity();
        if (loser == null) {
            return null;
        }

        List<BattleActor> opposingActors = new ArrayList<>();
        for (BattleActor opponent : opponents) {
            if (opponent.getSide() != loserActor.getSide()) {
                opposingActors.add(opponent);
            }
        }
        BattleActor namedOpponent = opposingActors.isEmpty() ? null : opposingActors.getFirst();
        PlayerBattleActor playerRecipient = opposingActors.stream()
            .filter(PlayerBattleActor.class::isInstance)
            .map(PlayerBattleActor.class::cast)
            .findFirst()
            .orElse(null);
        boolean wild = wildBattle || (namedOpponent != null
            && namedOpponent.getType() == ActorType.WILD);
        if (!shouldChargeLoss(
            wild, forfeited, PokemonCenterDefeatReturn.isPartyWiped(loser)
        )) {
            return null;
        }

        SettlementKey key = new SettlementKey(battleId, loser.getUUID());
        StoredSettlement existing = SETTLEMENTS.get(key);
        if (existing != null) {
            return existing.settlement;
        }

        BigInteger balance = PlayerExtensionKt.getCobbleDollars(loser).max(BigInteger.ZERO);
        BigInteger requested = BigInteger.valueOf(highestPartyLevel(loser))
            .multiply(MONEY_PER_HIGHEST_LEVEL);
        BigInteger amount = requested.min(balance);
        PlayerExtensionKt.setCobbleDollars(loser, balance.subtract(amount));

        if (playerRecipient != null && amount.signum() > 0) {
            ServerPlayer recipient = playerRecipient.getEntity();
            if (recipient != null) {
                BigInteger recipientBalance = PlayerExtensionKt.getCobbleDollars(recipient)
                    .max(BigInteger.ZERO);
                PlayerExtensionKt.setCobbleDollars(recipient, recipientBalance.add(amount));
            } else {
                PlayerExtensionKt.addOfflineCobbleDollars(
                    playerRecipient.getUuid(), loser.getServer(), amount
                );
            }
        }

        Component opponentName = namedOpponent == null
            ? Component.translatable("message.cobbleventure_bootstrap.battle_opponent")
            : namedOpponent.getName();
        Settlement settlement = new Settlement(amount, opponentName, wild, forfeited);
        long gameTime = loser.getServer().overworld().getGameTime();
        SETTLEMENTS.put(
            key,
            new StoredSettlement(settlement, gameTime + SETTLEMENT_RETENTION_TICKS)
        );
        return settlement;
    }

    static boolean shouldChargeLoss(
        boolean wildBattle, boolean forfeited, boolean partyWiped
    ) {
        return !wildBattle || (!forfeited && partyWiped);
    }

    private static int highestPartyLevel(ServerPlayer player) {
        int highest = 1;
        for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            highest = Math.max(highest, pokemon.getLevel());
        }
        return highest;
    }

    static final class Settlement {
        private final BigInteger amount;
        private final Component opponentName;
        private final boolean wild;
        private final boolean forfeited;
        private boolean announced;

        private Settlement(
            BigInteger amount, Component opponentName, boolean wild, boolean forfeited
        ) {
            this.amount = amount;
            this.opponentName = opponentName;
            this.wild = wild;
            this.forfeited = forfeited;
        }
    }

    private record SettlementKey(UUID battleId, UUID playerId) {}

    private record StoredSettlement(Settlement settlement, long expiresAt) {}
}
