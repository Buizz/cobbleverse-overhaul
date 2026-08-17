package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Pulls already-sent-out player Pokemon into their computed battle formation. */
final class BattlePokemonPositioning {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double REPOSITION_DISTANCE_SQUARED = 3.0D * 3.0D;
    private static final int RETRY_TICKS = 20;
    private static final Map<UUID, PendingBattle> PENDING = new HashMap<>();
    private static boolean registered;

    private BattlePokemonPositioning() {}

    static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(BattlePokemonPositioning::onServerTick);
        CobblemonEvents.BATTLE_STARTED_POST.subscribe(
            (Consumer<BattleStartedEvent.Post>) BattlePokemonPositioning::onBattleStarted
        );
    }

    private static void onBattleStarted(BattleStartedEvent.Post event) {
        reposition(event.getBattle());
        PENDING.put(
            event.getBattle().getBattleId(),
            new PendingBattle(event.getBattle(), RETRY_TICKS)
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, PendingBattle>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingBattle pending = iterator.next().getValue();
            reposition(pending.battle);
            if (--pending.remainingTicks <= 0) iterator.remove();
        }
    }

    private static void reposition(PokemonBattle battle) {
        int repositioned = 0;
        for (BattleActor actor : battle.getActors()) {
            if (!(actor instanceof PlayerBattleActor playerActor)) continue;
            ServerPlayer player = playerActor.getEntity();
            if (player == null) continue;

            for (ActiveBattlePokemon active : playerActor.getActivePokemon()) {
                if (active.getBattlePokemon() == null) continue;
                PokemonEntity pokemon = active.getBattlePokemon().getEntity();
                if (pokemon == null || !pokemon.isAlive()
                    || pokemon.level() != player.level()
                    || !battle.getBattleId().equals(pokemon.getBattleId())) {
                    continue;
                }
                Vec3 target = active.getSendOutPosition();
                if (target == null || pokemon.position().distanceToSqr(target)
                    <= REPOSITION_DISTANCE_SQUARED) {
                    continue;
                }
                pokemon.getNavigation().stop();
                pokemon.setDeltaMovement(Vec3.ZERO);
                pokemon.teleportTo(target.x, target.y, target.z);
                pokemon.resetFallDistance();
                repositioned++;
            }
        }
        if (repositioned > 0) {
            LOGGER.debug(
                "Player Pokemon moved into battle formation: battle={}, count={}",
                battle.getBattleId(), repositioned
            );
        }
    }

    private static final class PendingBattle {
        private final PokemonBattle battle;
        private int remainingTicks;

        private PendingBattle(PokemonBattle battle, int remainingTicks) {
            this.battle = battle;
            this.remainingTicks = remainingTicks;
        }
    }
}
