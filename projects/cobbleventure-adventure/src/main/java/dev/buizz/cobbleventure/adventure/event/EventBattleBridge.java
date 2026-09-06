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
import dev.buizz.cobbleventure.adventure.PokemonCenterDefeatReturn;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Starts authored trainer battles and maps Cobblemon outcomes to CVES await results. */
public final class EventBattleBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long AWAIT_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final long START_TIMEOUT_TICKS = 20L * 20L;
    private static final long RECONNECT_RESTART_DELAY_TICKS = 10L;
    private static final String INTERRUPTED_BATTLE_TAG = "cobbleventureInterruptedBattle";
    private static final int MAX_RESUME_STEPS = 10_000;
    private static final Map<UUID, PendingBattle> PENDING = new HashMap<>();
    private static volatile BattleLaunchOverride battleLaunchOverride;
    private static boolean registered;

    private EventBattleBridge() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventBattleBridge::onServerTick);
        NeoForge.EVENT_BUS.addListener(EventBattleBridge::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(EventBattleBridge::onPlayerLoggedIn);
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

    /** Allows a containing gameplay system to replace the physical battle launch. */
    public static void setBattleLaunchOverride(BattleLaunchOverride override) {
        battleLaunchOverride = override;
    }

    @FunctionalInterface
    public interface BattleLaunchOverride {
        /** Returns true after launching a replacement battle, or false to use the preset command. */
        boolean launch(
            ServerPlayer player, EventBattlePreset preset, Entity opponent
        );
    }

    /** Exposes the active CVES opponent to dungeon progression listeners. */
    public static Optional<BattleContext> pendingContext(UUID playerId) {
        PendingBattle pending = PENDING.get(playerId);
        return pending == null ? Optional.empty() : Optional.of(new BattleContext(
            pending.key.npcId(), pending.preset.battleId()
        ));
    }

    /** True from the authored launch request until its trainer battle resolves or times out. */
    public static boolean hasPendingTrainerBattle(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    public record BattleContext(UUID npcId, String battleId) {}

    private static EventBattleGateway.OpenResult open(
        ServerPlayer player, EventBattleGateway.BattleRequest request
    ) {
        if (!request.sessionKey().playerId().equals(player.getUUID())) {
            throw new EventRuntimeException("battle 요청의 player와 gateway player가 다릅니다.");
        }
        if (PENDING.containsKey(player.getUUID())
            || BattleRegistry.getBattleByParticipatingPlayerId(player.getUUID()) != null) {
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
        try {
            launch(player, pending, opponent);
        } catch (CommandSyntaxException | RuntimeException error) {
            PENDING.remove(player.getUUID(), pending);
            throw new EventRuntimeException("battle 시작 명령 실행에 실패했습니다.", error);
        }
        return new EventBattleGateway.OpenResult(
            token, System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        );
    }

    private static void launch(
        ServerPlayer player, PendingBattle pending, Entity opponent
    ) throws CommandSyntaxException {
        cancelLegacyProximity(player);
        EventBattlePreset.MoneyReward reward = rewardFor(
            pending.preset,
            EventNpcBindingRepository.instance().findByEntityTags(opponent.getTags()).orElse(null),
            pending.key.scriptId()
        );
        if (reward != null) {
            String rewardCommand = reward.prepareCommand(
                player.getGameProfile().getName(),
                new ServerPlayerEventState(player, pending.key.npcId())::flag
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
        BattleLaunchOverride override = battleLaunchOverride;
        if (override != null && override.launch(player, pending.preset, opponent)) return;
        int accepted = player.getServer().getCommands().getDispatcher().execute(
            pending.preset.launchCommand(player.getGameProfile().getName(), opponent.getUUID()),
            opponent.createCommandSourceStack().withPermission(4).withSuppressedOutput()
        );
        // The cinematic intro deliberately launches TBCS a few ticks later.
        if (accepted <= 0) {
            throw new EventRuntimeException("battle 시작 예약 명령이 거부됐습니다.");
        }
    }

    static EventBattlePreset.MoneyReward rewardFor(
        EventBattlePreset preset, EventNpcBinding binding, String scriptId
    ) {
        // An explicit NPC override (including disabled) wins; never prepare both.
        return binding != null && binding.scriptId().equals(scriptId) && binding.moneyReward() != null
            ? binding.moneyReward() : preset.moneyReward();
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
        boolean attachableTrainerBattle = shouldAttachTrainerBattle(
            event.getBattle().isPvW()
        );
        UUID battleId = event.getBattle().getBattleId();
        for (BattleActor actor : event.getBattle().getActors()) {
            if (!(actor instanceof PlayerBattleActor playerActor)) continue;
            PendingBattle pending = PENDING.get(playerActor.getUuid());
            if (pending == null || pending.battleInstanceId != null) continue;
            if (!attachableTrainerBattle) {
                LOGGER.warn(
                    "Ignoring non-trainer battle while CVES trainer launch is pending: player={}, battle={}",
                    playerActor.getUuid(), battleId
                );
                continue;
            }
            pending.battleInstanceId = battleId;
            LOGGER.info(
                "CVES battle attached: player={}, preset={}, battle={}",
                playerActor.getUuid(), pending.preset.battleId(), battleId
            );
        }
    }

    static boolean shouldAttachTrainerBattle(boolean playerVersusWild) {
        // RCT/TBCS trainer actors are not consistently exposed as one concrete
        // actor class. PvW is the stable distinction we need: never steal a
        // wild battle, but accept the authored non-wild battle launched while
        // this exact player has a CVES reservation.
        return !playerVersusWild;
    }

    private static void onBattleVictory(BattleVictoryEvent event) {
        UUID battleId = event.getBattle().getBattleId();
        List<BattleActor> winners = event.getWinners();
        for (BattleActor actor : event.getBattle().getActors()) {
            if (!(actor instanceof PlayerBattleActor playerActor)) continue;
            PendingBattle pending = PENDING.get(playerActor.getUuid());
            if (pending != null && pending.battleInstanceId == null
                && shouldAttachTrainerBattle(event.getBattle().isPvW())) {
                // Recover if a mod integration skipped/reordered BATTLE_STARTED_POST.
                pending.battleInstanceId = battleId;
                LOGGER.warn(
                    "Recovered missing CVES battle attachment from victory: player={}, preset={}, battle={}",
                    playerActor.getUuid(), pending.preset.battleId(), battleId
                );
            }
            pending = matching(playerActor.getUuid(), battleId);
            if (pending == null) continue;
            PENDING.remove(playerActor.getUuid(), pending);
            ServerPlayer player = playerActor.getEntity();
            if (player == null) continue;
            clearInterruptedBattle(player);
            boolean won = winners.contains(actor);
            LOGGER.info(
                "CVES battle completed: player={}, preset={}, battle={}, outcome={}",
                playerActor.getUuid(), pending.preset.battleId(), battleId,
                won ? "win" : "loss"
            );
            complete(
                player, pending, won ? "win" : "loss",
                won ? EventSession.CompletionKind.COMPLETED
                    : EventSession.CompletionKind.FAILED
            );
        }
    }

    private static void onBattleFled(BattleFledEvent event) {
        UUID playerId = event.getPlayer().getUuid();
        UUID battleId = event.getBattle().getBattleId();
        PendingBattle pending = PENDING.get(playerId);
        if (pending != null && pending.battleInstanceId == null
            && shouldAttachTrainerBattle(event.getBattle().isPvW())) {
            pending.battleInstanceId = battleId;
        }
        pending = matching(playerId, battleId);
        if (pending == null) return;
        PENDING.remove(playerId, pending);
        ServerPlayer player = event.getPlayer().getEntity();
        if (player != null) {
            clearInterruptedBattle(player);
            complete(player, pending, "cancelled", EventSession.CompletionKind.CANCELLED);
        }
    }

    private static PendingBattle matching(UUID playerId, UUID battleId) {
        PendingBattle pending = PENDING.get(playerId);
        return pending != null && battleId.equals(pending.battleInstanceId) ? pending : null;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        long tick = event.getServer().overworld().getGameTime();
        List<Map.Entry<UUID, PendingBattle>> restarting = PENDING.entrySet().stream()
            .filter(entry -> entry.getValue().restartAtTick > 0L
                && entry.getValue().restartAtTick <= tick)
            .toList();
        for (Map.Entry<UUID, PendingBattle> entry : restarting) {
            PendingBattle pending = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || PENDING.get(entry.getKey()) != pending) continue;
            if (BattleRegistry.getBattleByParticipatingPlayerId(entry.getKey()) != null) {
                pending.restartAtTick = tick + 1L;
                continue;
            }
            Entity opponent = findEntity(player, pending.key.npcId());
            if (opponent == null || !opponent.isAlive()) {
                if (pending.startExpiresAtTick <= tick) {
                    if (PENDING.remove(entry.getKey(), pending)) {
                        clearInterruptedBattle(player);
                        complete(player, pending, "cancelled", EventSession.CompletionKind.FAILED);
                    }
                    continue;
                }
                pending.restartAtTick = tick + 20L;
                continue;
            }
            pending.restartAtTick = 0L;
            try {
                launch(player, pending, opponent);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    pending.preset.canForfeit()
                        ? "중단된 트레이너 전투를 다시 시작합니다."
                        : "포기할 수 없는 전투가 중단되어 같은 전투를 다시 시작합니다."
                ));
            } catch (CommandSyntaxException | RuntimeException error) {
                LOGGER.error(
                    "Interrupted trainer battle could not restart: player={}, preset={}",
                    player.getGameProfile().getName(), pending.preset.battleId(), error
                );
                if (PENDING.remove(entry.getKey(), pending)) {
                    clearInterruptedBattle(player);
                    complete(player, pending, "cancelled", EventSession.CompletionKind.FAILED);
                }
            }
        }
        List<Map.Entry<UUID, PendingBattle>> expired = PENDING.entrySet().stream()
            .filter(entry -> entry.getValue().battleInstanceId == null
                && entry.getValue().restartAtTick == 0L
                && entry.getValue().startExpiresAtTick <= tick)
            .toList();
        for (Map.Entry<UUID, PendingBattle> entry : expired) {
            PendingBattle pending = entry.getValue();
            if (!PENDING.remove(entry.getKey(), pending)) continue;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                clearInterruptedBattle(player);
                complete(player, pending, "cancelled", EventSession.CompletionKind.FAILED);
            }
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PendingBattle pending = PENDING.get(player.getUUID());
        if (pending == null) return;
        pending.battleInstanceId = null;
        pending.startExpiresAtTick = Long.MAX_VALUE;
        pending.restartAtTick = -1L;
        saveInterruptedBattle(player, pending);
        LOGGER.info(
            "Trainer battle interrupted by disconnect: player={}, preset={}, canForfeit={}",
            player.getGameProfile().getName(), pending.preset.battleId(),
            pending.preset.canForfeit()
        );
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PendingBattle pending = PENDING.get(player.getUUID());
        if (pending == null) pending = restoreInterruptedBattle(player);
        if (pending == null) return;
        if (PokemonCenterDefeatReturn.isPartyWiped(player)) {
            PENDING.remove(player.getUUID(), pending);
            clearInterruptedBattle(player);
            complete(player, pending, "loss", EventSession.CompletionKind.FAILED);
            return;
        }
        long tick = player.getServer().overworld().getGameTime();
        pending.startExpiresAtTick = tick + START_TIMEOUT_TICKS;
        pending.restartAtTick = tick + RECONNECT_RESTART_DELAY_TICKS;
        PENDING.put(player.getUUID(), pending);
    }

    private static void saveInterruptedBattle(ServerPlayer player, PendingBattle pending) {
        CompoundTag value = new CompoundTag();
        value.putString("npc", pending.key.npcId().toString());
        value.putString("script", pending.key.scriptId());
        value.putString("trigger", pending.key.triggerInstance());
        value.putString("token", pending.token);
        value.putString("preset", pending.preset.battleId());
        player.getPersistentData().put(INTERRUPTED_BATTLE_TAG, value);
    }

    private static PendingBattle restoreInterruptedBattle(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(INTERRUPTED_BATTLE_TAG, Tag.TAG_COMPOUND)) return null;
        CompoundTag value = data.getCompound(INTERRUPTED_BATTLE_TAG);
        try {
            EventSessionKey key = new EventSessionKey(
                player.getUUID(), UUID.fromString(value.getString("npc")),
                value.getString("script"), value.getString("trigger")
            );
            String token = value.getString("token");
            EventSession session = SavedEventSessionStore.get(player.getServer())
                .find(key).orElse(null);
            EventBattlePreset preset = EventBattlePresetRepository.instance()
                .find(value.getString("preset")).orElse(null);
            if (session == null || preset == null
                || session.status() != EventSession.Status.WAITING
                || session.awaiting() == null
                || !session.awaiting().kind().equals("battle")
                || !session.awaiting().token().equals(token)) {
                clearInterruptedBattle(player);
                return null;
            }
            return new PendingBattle(key, token, preset, Long.MAX_VALUE);
        } catch (IllegalArgumentException error) {
            LOGGER.warn(
                "Invalid interrupted battle state was discarded for player {}",
                player.getGameProfile().getName(), error
            );
            clearInterruptedBattle(player);
            return null;
        }
    }

    private static void clearInterruptedBattle(ServerPlayer player) {
        player.getPersistentData().remove(INTERRUPTED_BATTLE_TAG);
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
                    new EventStateExpressionEnvironment(
                        new ServerPlayerEventState(player, pending.key.npcId())
                    ),
                    EventDialogueNetwork.serverAdapter(player, pending.key.npcId()),
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
        private long startExpiresAtTick;
        private UUID battleInstanceId;
        private long restartAtTick;

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
