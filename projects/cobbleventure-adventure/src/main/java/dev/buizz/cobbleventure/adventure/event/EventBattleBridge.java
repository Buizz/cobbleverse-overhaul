package dev.buizz.cobbleventure.adventure.event;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Starts authored trainer battles and maps Cobblemon outcomes to CVES await results. */
public final class EventBattleBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long AWAIT_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final long START_TIMEOUT_TICKS = 20L * 20L;
    private static final int MAX_RESUME_STEPS = 10_000;
    private static final Map<UUID, PendingBattle> PENDING = new HashMap<>();
    private static boolean registered;

    private EventBattleBridge() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventBattleBridge::onServerTick);
        CobblemonEvents.BATTLE_STARTED_POST.subscribe(
            (Consumer<BattleStartedEvent.Post>) EventBattleBridge::onBattleStarted
        );
        CobblemonEvents.BATTLE_VICTORY.subscribe(
            (Consumer<BattleVictoryEvent>) EventBattleBridge::onBattleVictory
        );
        CobblemonEvents.BATTLE_FLED.subscribe(
            (Consumer<BattleFledEvent>) EventBattleBridge::onBattleFled
        );
    }

    public static EventBattleGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> open(player, request);
    }

    private static EventBattleGateway.OpenResult open(
        ServerPlayer player, EventBattleGateway.BattleRequest request
    ) {
        if (!request.sessionKey().playerId().equals(player.getUUID())) {
            throw new EventRuntimeException("battle 요청의 player와 gateway player가 다릅니다.");
        }
        if (PENDING.containsKey(player.getUUID())
            || BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null) {
            throw new EventRuntimeException("플레이어가 이미 배틀을 시작했거나 진행 중입니다.");
        }
        EventBattlePreset preset = EventBattlePresetRepository.instance()
            .find(request.battleId())
            .orElseThrow(() -> new EventRuntimeException(
                "battle preset을 찾을 수 없습니다: " + request.battleId()
            ));
        Entity opponent = findEntity(player, request.sessionKey().npcId());
        if (opponent == null || !opponent.isAlive()) {
            throw new EventRuntimeException(
                "battle 상대 NPC를 찾을 수 없습니다: " + request.sessionKey().npcId()
            );
        }

        String token = UUID.randomUUID().toString();
        PendingBattle pending = new PendingBattle(
            request.sessionKey(), token, preset,
            player.getServer().overworld().getGameTime() + START_TIMEOUT_TICKS
        );
        PENDING.put(player.getUUID(), pending);
        int result;
        try {
            cancelLegacyProximity(player);
            if (preset.moneyReward() != null) {
                String rewardCommand = preset.moneyReward().prepareCommand(
                    player.getGameProfile().getName()
                );
                if (rewardCommand != null) {
                    int prepared = player.getServer().getCommands().getDispatcher().execute(
                        rewardCommand,
                        player.createCommandSourceStack().withPermission(4).withSuppressedOutput()
                    );
                    if (prepared <= 0) {
                        throw new EventRuntimeException("battle 상금 준비 명령이 거부됐습니다.");
                    }
                }
            }
            result = player.getServer().getCommands().getDispatcher().execute(
                preset.launchCommand(player.getGameProfile().getName(), opponent.getUUID()),
                opponent.createCommandSourceStack().withPermission(4).withSuppressedOutput()
            );
        } catch (CommandSyntaxException | RuntimeException error) {
            PENDING.remove(player.getUUID(), pending);
            throw new EventRuntimeException("battle 시작 명령 실행에 실패했습니다.", error);
        }
        if (result <= 0) {
            PENDING.remove(player.getUUID(), pending);
            throw new EventRuntimeException("battle 시작 명령이 거부됐습니다: " + preset.battleId());
        }
        return new EventBattleGateway.OpenResult(
            token, System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        );
    }

    private static void cancelLegacyProximity(ServerPlayer player) {
        try {
            player.getServer().getCommands().getDispatcher().execute(
                "cobbleventure_proximity_cancel "
                    + player.getGameProfile().getName(),
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput()
            );
        } catch (CommandSyntaxException error) {
            LOGGER.debug(
                "Legacy proximity cancellation command is unavailable for player {}",
                player.getGameProfile().getName(), error
            );
        }
    }

    private static Entity findEntity(ServerPlayer player, UUID entityId) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) return entity;
        }
        return null;
    }

    private static void onBattleStarted(BattleStartedEvent.Post event) {
        UUID battleId = event.getBattle().getBattleId();
        for (BattleActor actor : event.getBattle().getActors()) {
            if (!(actor instanceof PlayerBattleActor playerActor)) continue;
            PendingBattle pending = PENDING.get(playerActor.getUuid());
            if (pending == null || pending.battleInstanceId != null) continue;
            pending.battleInstanceId = battleId;
            LOGGER.debug(
                "CVES battle attached: player={}, preset={}, battle={}",
                playerActor.getUuid(), pending.preset.battleId(), battleId
            );
        }
    }

    private static void onBattleVictory(BattleVictoryEvent event) {
        UUID battleId = event.getBattle().getBattleId();
        List<BattleActor> winners = event.getWinners();
        for (BattleActor actor : event.getBattle().getActors()) {
            if (!(actor instanceof PlayerBattleActor playerActor)) continue;
            PendingBattle pending = matching(playerActor.getUuid(), battleId);
            if (pending == null) continue;
            PENDING.remove(playerActor.getUuid(), pending);
            ServerPlayer player = playerActor.getEntity();
            if (player == null) continue;
            boolean won = winners.contains(actor);
            complete(
                player, pending, won ? "win" : "loss",
                won ? EventSession.CompletionKind.COMPLETED
                    : EventSession.CompletionKind.FAILED
            );
        }
    }

    private static void onBattleFled(BattleFledEvent event) {
        UUID playerId = event.getPlayer().getUuid();
        PendingBattle pending = matching(playerId, event.getBattle().getBattleId());
        if (pending == null) return;
        PENDING.remove(playerId, pending);
        ServerPlayer player = event.getPlayer().getEntity();
        if (player != null) {
            complete(player, pending, "cancelled", EventSession.CompletionKind.CANCELLED);
        }
    }

    private static PendingBattle matching(UUID playerId, UUID battleId) {
        PendingBattle pending = PENDING.get(playerId);
        return pending != null && battleId.equals(pending.battleInstanceId) ? pending : null;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        long tick = event.getServer().overworld().getGameTime();
        List<Map.Entry<UUID, PendingBattle>> expired = PENDING.entrySet().stream()
            .filter(entry -> entry.getValue().battleInstanceId == null
                && entry.getValue().startExpiresAtTick <= tick)
            .toList();
        for (Map.Entry<UUID, PendingBattle> entry : expired) {
            PendingBattle pending = entry.getValue();
            if (!PENDING.remove(entry.getKey(), pending)) continue;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                complete(player, pending, "cancelled", EventSession.CompletionKind.FAILED);
            }
        }
    }

    private static void complete(
        ServerPlayer player, PendingBattle pending, String outcome,
        EventSession.CompletionKind kind
    ) {
        EventScript script = EventScriptRepository.instance()
            .find(pending.key.scriptId()).orElse(null);
        JsonObject result = new JsonObject();
        result.addProperty("outcome", outcome);
        result.addProperty("opponent", pending.preset.trainerId());
        try {
            if (script == null) {
                LOGGER.warn(
                    "CVES battle source script disappeared before callback: preset={}, script={}",
                    pending.preset.battleId(), pending.key.scriptId()
                );
                return;
            }
            EventAwaitCompletionService.Outcome completed =
                EventAwaitCompletionService.completeAndRun(
                    player.getUUID(), pending.key, pending.token,
                    new EventSession.AwaitCompletion(kind, result),
                    script,
                    new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
                    EventDialogueNetwork.serverAdapter(player),
                    SavedEventSessionStore.get(player.getServer()),
                    MAX_RESUME_STEPS
                );
            if (completed.status() != EventAwaitCompletionService.Status.RESUMED
                && completed.status() != EventAwaitCompletionService.Status.DUPLICATE) {
                LOGGER.warn(
                    "CVES battle callback was not resumed: player={}, preset={}, status={}",
                    player.getGameProfile().getName(), pending.preset.battleId(),
                    completed.status()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES battle callback failed: player={}, preset={}, outcome={}",
                player.getGameProfile().getName(), pending.preset.battleId(), outcome, error
            );
        } finally {
            try {
                EventServerSignalDispatcher.battleFinished(
                    player, pending.preset.battleId(), outcome
                );
            } catch (RuntimeException error) {
                LOGGER.error(
                    "CVES battle_finished signal failed: player={}, preset={}, outcome={}",
                    player.getGameProfile().getName(), pending.preset.battleId(), outcome,
                    error
                );
            }
        }
    }

    private static final class PendingBattle {
        private final EventSessionKey key;
        private final String token;
        private final EventBattlePreset preset;
        private final long startExpiresAtTick;
        private UUID battleInstanceId;

        private PendingBattle(
            EventSessionKey key, String token, EventBattlePreset preset,
            long startExpiresAtTick
        ) {
            this.key = key;
            this.token = token;
            this.preset = preset;
            this.startExpiresAtTick = startExpiresAtTick;
        }
    }
}
