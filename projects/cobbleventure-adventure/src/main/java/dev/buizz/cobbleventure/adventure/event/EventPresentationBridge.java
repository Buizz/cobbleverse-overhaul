package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Starts presentation only after await persistence and resumes through the shared callback path. */
public final class EventPresentationBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_RESUME_STEPS = 10_000;
    private static final long CALLBACK_GRACE_MILLIS = 30_000L;
    private static final Map<UUID, PendingPresentation> PENDING = new HashMap<>();
    private static boolean registered;

    private EventPresentationBridge() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventPresentationBridge::onServerTick);
    }

    public static EventPresentationGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> open(player, request);
    }

    static boolean cancel(EventSessionKey key) {
        Objects.requireNonNull(key, "key");
        PendingPresentation pending = PENDING.get(key.playerId());
        if (pending == null || !pending.request.sessionKey().equals(key)
            || !PENDING.remove(key.playerId(), pending)) {
            return false;
        }
        pending.hideFade();
        return true;
    }

    private static EventPresentationGateway.OpenResult open(
        ServerPlayer player, EventPresentationGateway.PresentationRequest request
    ) {
        if (!request.sessionKey().playerId().equals(player.getUUID())) {
            throw new EventRuntimeException("presentation 요청의 player와 gateway player가 다릅니다.");
        }
        long ticks = Math.max(1L, (long) Math.ceil(request.durationSeconds() * 20D));
        String token = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis()
            + (long) Math.ceil(request.durationSeconds() * 1000D)
            + CALLBACK_GRACE_MILLIS;
        PendingPresentation pending = new PendingPresentation(
            player, request, token,
            player.serverLevel().getGameTime() + ticks,
            expiresAt
        );
        if (PENDING.putIfAbsent(player.getUUID(), pending) != null) {
            throw new EventRuntimeException("플레이어에게 이미 대기 중인 presentation이 있습니다.");
        }
        return new EventPresentationGateway.OpenResult(token, expiresAt);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        List<Map.Entry<UUID, PendingPresentation>> values = PENDING.entrySet().stream().toList();
        for (Map.Entry<UUID, PendingPresentation> entry : values) {
            PendingPresentation pending = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                if (System.currentTimeMillis() >= pending.expiresAtEpochMilli
                    && PENDING.remove(entry.getKey(), pending)) {
                    pending.abandon();
                }
                continue;
            }
            pending.startFade();
            if (player.serverLevel().getGameTime() < pending.completeAtTick) continue;
            if (!PENDING.remove(entry.getKey(), pending)) continue;
            boolean succeeded;
            try {
                succeeded = pending.perform(player);
            } catch (RuntimeException error) {
                pending.hideFade();
                LOGGER.error(
                    "CVES presentation execution failed: player={}, instruction={}",
                    player.getGameProfile().getName(), pending.request.instructionId(), error
                );
                succeeded = false;
            }
            complete(player, pending, succeeded);
        }
    }

    private static void complete(
        ServerPlayer player, PendingPresentation pending, boolean succeeded
    ) {
        EventScript script = EventScriptRepository.instance()
            .find(pending.request.sessionKey().scriptId()).orElse(null);
        if (script == null) return;
        try {
            EventAwaitCompletionService.Outcome outcome =
                EventAwaitCompletionService.completeAndRun(
                    player.getUUID(), pending.request.sessionKey(), pending.token,
                    new EventSession.AwaitCompletion(
                        succeeded
                            ? EventSession.CompletionKind.COMPLETED
                            : EventSession.CompletionKind.FAILED,
                        new JsonPrimitive(succeeded)
                    ),
                    script,
                    new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
                    EventDialogueNetwork.serverAdapter(player),
                    SavedEventSessionStore.get(player.getServer()),
                    MAX_RESUME_STEPS
                );
            if (outcome.status() != EventAwaitCompletionService.Status.RESUMED
                && outcome.status() != EventAwaitCompletionService.Status.DUPLICATE) {
                LOGGER.warn(
                    "CVES presentation callback was not resumed: player={}, instruction={}, status={}",
                    player.getGameProfile().getName(), pending.request.instructionId(),
                    outcome.status()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES presentation callback failed: player={}, instruction={}",
                player.getGameProfile().getName(), pending.request.instructionId(), error
            );
        }
    }

    private static final class PendingPresentation {
        private final ServerPlayer owner;
        private final EventPresentationGateway.PresentationRequest request;
        private final String token;
        private final long completeAtTick;
        private final long expiresAtEpochMilli;
        private boolean fadeVisible;

        private PendingPresentation(
            ServerPlayer owner,
            EventPresentationGateway.PresentationRequest request,
            String token,
            long completeAtTick,
            long expiresAtEpochMilli
        ) {
            this.owner = owner;
            this.request = request;
            this.token = token;
            this.completeAtTick = completeAtTick;
            this.expiresAtEpochMilli = expiresAtEpochMilli;
        }

        private void startFade() {
            if (request.kind() != EventPresentationGateway.Kind.FADE || fadeVisible) return;
            EventDialogueNetwork.setFade(
                owner, request.fadeColor(), true
            );
            fadeVisible = true;
        }

        private void hideFade() {
            if (!fadeVisible) return;
            EventDialogueNetwork.setFade(owner, request.fadeColor(), false);
            fadeVisible = false;
        }

        private void abandon() {
            // The client connection is gone, so no close packet is necessary.
            fadeVisible = false;
        }

        private boolean perform(ServerPlayer player) {
            hideFade();
            return switch (request.kind()) {
                case FADE, WAIT -> true;
                case SOUND -> playSound(player, request.resourceId());
                case EFFECT -> spawnEffect(player, request.resourceId());
            };
        }

        private static boolean playSound(ServerPlayer player, String resourceId) {
            ResourceLocation id = ResourceLocation.tryParse(resourceId);
            if (id == null) return false;
            player.serverLevel().playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                SoundEvent.createVariableRangeEvent(id),
                SoundSource.MASTER,
                1F, 1F
            );
            return true;
        }

        private static boolean spawnEffect(ServerPlayer player, String resourceId) {
            ResourceLocation id = ResourceLocation.tryParse(resourceId);
            if (id == null) return false;
            return BuiltInRegistries.PARTICLE_TYPE.getOptional(id)
                .filter(SimpleParticleType.class::isInstance)
                .map(SimpleParticleType.class::cast)
                .map(particle -> player.serverLevel().sendParticles(
                    particle,
                    player.getX(), player.getY() + 1D, player.getZ(),
                    16, 0.35D, 0.5D, 0.35D, 0.02D
                ) >= 0)
                .orElse(false);
        }
    }
}
