package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import com.cobblemon.mod.common.Cobblemon;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.PokemonCenterHealingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Owns machine/fallback healing completion and resumes the persisted CVES await. */
public final class EventHealingBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SEARCH_RADIUS = 8;
    private static final int MAX_RESUME_STEPS = 10_000;
    private static final long TIMEOUT_MILLIS = 2L * 60L * 1000L;
    private static final Map<UUID, PendingHealing> PENDING = new HashMap<>();
    private static boolean registered;

    private EventHealingBridge() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventHealingBridge::onServerTick);
    }

    public static EventHealingGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> open(player, request);
    }

    static boolean cancel(EventSessionKey key) {
        PendingHealing pending = PENDING.get(key.playerId());
        return pending != null
            && pending.request.sessionKey().equals(key)
            && PENDING.remove(key.playerId(), pending);
    }

    private static EventHealingGateway.OpenResult open(
        ServerPlayer player, EventHealingGateway.HealingRequest request
    ) {
        if (!request.sessionKey().playerId().equals(player.getUUID())) {
            throw new EventRuntimeException("heal_party 요청의 player와 gateway player가 다릅니다.");
        }
        if (PENDING.containsKey(player.getUUID())) {
            throw new EventRuntimeException("플레이어에게 이미 대기 중인 치료가 있습니다.");
        }
        Entity npc = player.serverLevel().getEntity(request.sessionKey().npcId());
        HealingOutcome immediateOutcome = null;
        PokemonCenterHealingService.StartResult started = npc == null
            ? new PokemonCenterHealingService.StartResult(
                PokemonCenterHealingService.StartStatus.HEALING_MACHINE_NOT_FOUND, null
            )
            : PokemonCenterHealingService.start(player, npc.blockPosition(), SEARCH_RADIUS);
        if (npc != null && shouldFallback(request.fallbackWithoutMachine(), started.status())) {
            var party = Cobblemon.INSTANCE.getStorage().getParty(player);
            if (party.iterator().hasNext()) {
                party.heal();
                immediateOutcome = HealingOutcome.success();
            } else {
                immediateOutcome = HealingOutcome.failure("healing_unavailable");
            }
        }
        String token = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + TIMEOUT_MILLIS;
        PendingHealing pending = new PendingHealing(request, token, expiresAt, started, immediateOutcome);
        PENDING.put(player.getUUID(), pending);
        return new EventHealingGateway.OpenResult(token, expiresAt);
    }

    static boolean shouldFallback(boolean enabled, PokemonCenterHealingService.StartStatus status) {
        return enabled && status == PokemonCenterHealingService.StartStatus.HEALING_MACHINE_NOT_FOUND;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        List<Map.Entry<UUID, PendingHealing>> values = PENDING.entrySet().stream().toList();
        for (Map.Entry<UUID, PendingHealing> entry : values) {
            PendingHealing pending = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                if (System.currentTimeMillis() >= pending.expiresAtEpochMilli) {
                    PENDING.remove(entry.getKey(), pending);
                }
                continue;
            }
            HealingOutcome outcome = pending.outcome(player);
            if (outcome == null || !PENDING.remove(entry.getKey(), pending)) continue;
            complete(player, pending, outcome);
        }
    }

    private static void complete(
        ServerPlayer player, PendingHealing pending, HealingOutcome outcome
    ) {
        EventScript script = EventScriptRepository.instance()
            .find(pending.request.sessionKey().scriptId()).orElse(null);
        if (script == null) return;
        JsonObject result = new JsonObject();
        result.addProperty("healed", outcome.healed);
        result.addProperty("failure_reason", outcome.failureReason);
        try {
            EventAwaitCompletionService.Outcome completion =
                EventAwaitCompletionService.completeAndRun(
                    player.getUUID(), pending.request.sessionKey(), pending.token,
                    new EventSession.AwaitCompletion(
                        EventSession.CompletionKind.COMPLETED, result
                    ),
                    script,
                    new EventStateExpressionEnvironment(new ServerPlayerEventState(
                        player, pending.request.sessionKey().npcId()
                    )),
                    EventDialogueNetwork.serverAdapter(
                        player, pending.request.sessionKey().npcId()
                    ),
                    SavedEventSessionStore.get(player.getServer()),
                    MAX_RESUME_STEPS
                );
            if (completion.status() != EventAwaitCompletionService.Status.RESUMED
                && completion.status() != EventAwaitCompletionService.Status.DUPLICATE) {
                LOGGER.warn(
                    "CVES healing callback was not resumed: player={}, instruction={}, status={}",
                    player.getGameProfile().getName(), pending.request.instructionId(),
                    completion.status()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES healing callback failed: player={}, instruction={}",
                player.getGameProfile().getName(), pending.request.instructionId(), error
            );
        }
    }

    private record HealingOutcome(boolean healed, String failureReason) {
        private static HealingOutcome success() {
            return new HealingOutcome(true, "");
        }

        private static HealingOutcome failure(String reason) {
            return new HealingOutcome(false, reason);
        }
    }

    private static final class PendingHealing {
        private final EventHealingGateway.HealingRequest request;
        private final String token;
        private final long expiresAtEpochMilli;
        private final PokemonCenterHealingService.StartResult started;
        private final HealingOutcome immediateOutcome;

        private PendingHealing(
            EventHealingGateway.HealingRequest request,
            String token,
            long expiresAtEpochMilli,
            PokemonCenterHealingService.StartResult started,
            HealingOutcome immediateOutcome
        ) {
            this.request = request;
            this.token = token;
            this.expiresAtEpochMilli = expiresAtEpochMilli;
            this.started = started;
            this.immediateOutcome = immediateOutcome;
        }

        private HealingOutcome outcome(ServerPlayer player) {
            // Resume on the next server tick, after the interpreter has persisted its await token.
            if (immediateOutcome != null) return immediateOutcome;
            if (System.currentTimeMillis() >= expiresAtEpochMilli) {
                return HealingOutcome.failure("healing_timeout");
            }
            return switch (started.status()) {
                case HEALING_MACHINE_NOT_FOUND ->
                    HealingOutcome.failure("healing_machine_not_found");
                case HEALING_UNAVAILABLE -> HealingOutcome.failure("healing_unavailable");
                case STARTED -> progress(player, started.machinePosition());
            };
        }

        private static HealingOutcome progress(ServerPlayer player, BlockPos machinePosition) {
            return switch (PokemonCenterHealingService.progress(player, machinePosition)) {
                case RUNNING -> null;
                case COMPLETED -> HealingOutcome.success();
                case HEALING_MACHINE_NOT_FOUND ->
                    HealingOutcome.failure("healing_machine_not_found");
                case HEALING_INTERRUPTED -> HealingOutcome.failure("healing_interrupted");
            };
        }
    }
}
