package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Executes movement after the interpreter has persisted its common await token. */
public final class EventMovementBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long AWAIT_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_RESUME_STEPS = 10_000;
    private static final Map<UUID, PendingMovement> PENDING = new HashMap<>();
    private static boolean registered;

    private EventMovementBridge() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(EventMovementBridge::onServerTick);
    }

    public static EventMovementGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> open(player, request);
    }

    private static EventMovementGateway.OpenResult open(
        ServerPlayer player, EventMovementGateway.MovementRequest request
    ) {
        if (!request.sessionKey().playerId().equals(player.getUUID())) {
            throw new EventRuntimeException("movement 요청의 player와 gateway player가 다릅니다.");
        }
        String token = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        if (request.options().mode() == EventMovementGateway.Mode.WALK
            && !(request.destination() instanceof EventLocationRef.Relative)) {
            throw new EventRuntimeException("walk movement에는 relative destination이 필요합니다.");
        }
        PendingMovement pending = new PendingMovement(player, request, token, expiresAt);
        if (PENDING.putIfAbsent(player.getUUID(), pending) != null) {
            throw new EventRuntimeException("플레이어에게 이미 대기 중인 movement가 있습니다.");
        }
        return new EventMovementGateway.OpenResult(token, expiresAt);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        List<Map.Entry<UUID, PendingMovement>> pending = PENDING.entrySet().stream().toList();
        for (Map.Entry<UUID, PendingMovement> entry : pending) {
            PendingMovement movement = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                if (System.currentTimeMillis() >= movement.expiresAt) {
                    PENDING.remove(entry.getKey(), movement);
                }
                continue;
            }
            if (movement.request.options().mode() == EventMovementGateway.Mode.WALK) {
                tickWalk(event, player, entry.getKey(), movement);
                continue;
            }
            EventSafeTeleport.Outcome outcome = movement.tickTeleport(player);
            if (outcome == null) continue;
            if (!PENDING.remove(entry.getKey(), movement)) continue;
            complete(player, movement, outcome);
        }
    }

    static boolean cancel(EventSessionKey key) {
        Objects.requireNonNull(key, "key");
        PendingMovement movement = PENDING.get(key.playerId());
        if (movement == null || !movement.request.sessionKey().equals(key)
            || !PENDING.remove(key.playerId(), movement)) {
            return false;
        }
        movement.cancelWalk();
        movement.hideFade();
        return true;
    }

    private static void tickWalk(
        ServerTickEvent.Post event,
        ServerPlayer player,
        UUID playerId,
        PendingMovement movement
    ) {
        Entity subject = findSubject(event, player, movement.request);
        long now = System.currentTimeMillis();
        if (subject == null) {
            if (now < movement.expiresAt) return;
            if (PENDING.remove(playerId, movement)) {
                complete(
                    player, movement,
                    new EventSafeTeleport.Outcome(
                        false, EventMovementFailureReason.MOVEMENT_SUBJECT_UNAVAILABLE
                    )
                );
            }
            return;
        }
        EventWalkController.Agent agent = new EntityWalkAgent(subject);
        EventWalkController controller = movement.controller(agent);
        EventWalkController.Step step;
        try {
            step = controller.tick(agent, now);
        } catch (RuntimeException error) {
            controller.cancel(agent);
            step = new EventWalkController.Step(
                EventWalkController.Status.COLLISION, EventMovementFailureReason.MOVEMENT_FAILED
            );
        }
        if (!step.terminal() || !PENDING.remove(playerId, movement)) return;
        complete(
            player, movement,
            new EventSafeTeleport.Outcome(
                step.status() == EventWalkController.Status.ARRIVED,
                step.failureReason()
            )
        );
    }

    private static Entity findSubject(
        ServerTickEvent.Post event,
        ServerPlayer player,
        EventMovementGateway.MovementRequest request
    ) {
        if (request.subject() == EventMovementGateway.Subject.PLAYER) return player;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Entity entity = level.getEntity(request.sessionKey().npcId());
            if (entity != null && entity.isAlive()) return entity;
        }
        return null;
    }

    private static void complete(
        ServerPlayer player,
        PendingMovement pending,
        EventSafeTeleport.Outcome outcome
    ) {
        EventScript script = EventScriptRepository.instance()
            .find(pending.request.sessionKey().scriptId()).orElse(null);
        if (script == null) return;
        JsonObject result = new JsonObject();
        result.addProperty("arrived", outcome.arrived());
        result.addProperty(
            "failure_reason", outcome.failureReason() == null ? "" : outcome.failureReason()
        );
        result.add("destination", pending.request.destination().toJson());
        EventSession.CompletionKind kind = outcome.arrived()
            ? EventSession.CompletionKind.COMPLETED
            : EventSession.CompletionKind.FAILED;
        try {
            EventAwaitCompletionService.Outcome completed =
                EventAwaitCompletionService.completeAndRun(
                    player.getUUID(), pending.request.sessionKey(), pending.token,
                    new EventSession.AwaitCompletion(kind, result), script,
                    new EventStateExpressionEnvironment(new ServerPlayerEventState(
                        player, pending.request.sessionKey().npcId()
                    )),
                    EventDialogueNetwork.serverAdapter(
                        player, pending.request.sessionKey().npcId()
                    ),
                    SavedEventSessionStore.get(player.getServer()), MAX_RESUME_STEPS
                );
            if (completed.status() != EventAwaitCompletionService.Status.RESUMED
                && completed.status() != EventAwaitCompletionService.Status.DUPLICATE) {
                LOGGER.warn(
                    "CVES movement callback was not resumed: player={}, instruction={}, status={}",
                    player.getGameProfile().getName(), pending.request.instructionId(),
                    completed.status()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES movement callback failed: player={}, instruction={}",
                player.getGameProfile().getName(), pending.request.instructionId(), error
            );
        }
    }

    private static final class PendingMovement {
        private final EventMovementGateway.MovementRequest request;
        private final ServerPlayer owner;
        private final String token;
        private final long expiresAt;
        private EventWalkController controller;
        private EventWalkController.Agent lastAgent;
        private boolean fadeVisible;
        private long teleportAtTick;
        private long fadeCompleteAtTick;
        private EventSafeTeleport.Outcome teleportOutcome;

        private PendingMovement(
            ServerPlayer owner,
            EventMovementGateway.MovementRequest request,
            String token,
            long expiresAt
        ) {
            this.owner = owner;
            this.request = request;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        private EventWalkController controller(EventWalkController.Agent agent) {
            lastAgent = agent;
            if (controller == null) {
                controller = new EventWalkController(
                    agent.position(),
                    (EventLocationRef.Relative) request.destination(),
                    request.options(),
                    expiresAt
                );
            }
            return controller;
        }

        private void cancelWalk() {
            if (controller != null && lastAgent != null) controller.cancel(lastAgent);
        }

        private EventSafeTeleport.Outcome tickTeleport(ServerPlayer player) {
            if (request.options().fade() == EventMovementGateway.Fade.NONE) {
                return executeTeleport(player);
            }
            if (!fadeVisible) {
                EventDialogueNetwork.setFade(
                    player,
                    request.options().fade() == EventMovementGateway.Fade.WHITE
                        ? EventPresentationGateway.FadeColor.WHITE
                        : EventPresentationGateway.FadeColor.BLACK,
                    true
                );
                fadeVisible = true;
                teleportAtTick = player.serverLevel().getGameTime() + 5L;
                fadeCompleteAtTick = player.serverLevel().getGameTime() + 10L;
                return null;
            }
            long gameTime = player.serverLevel().getGameTime();
            if (teleportOutcome == null && gameTime >= teleportAtTick) {
                teleportOutcome = executeTeleport(player);
            }
            if (gameTime < fadeCompleteAtTick) return null;
            hideFade();
            return teleportOutcome == null
                ? new EventSafeTeleport.Outcome(
                    false, EventMovementFailureReason.TELEPORT_FAILED
                )
                : teleportOutcome;
        }

        private EventSafeTeleport.Outcome executeTeleport(ServerPlayer player) {
            if (request.subject() != EventMovementGateway.Subject.PLAYER) {
                return new EventSafeTeleport.Outcome(
                    false, EventMovementFailureReason.NPC_SUBJECT_UNAVAILABLE
                );
            }
            try {
                return EventSafeTeleport.execute(
                    player, request.destination(), teleportOptions()
                );
            } catch (RuntimeException error) {
                return new EventSafeTeleport.Outcome(
                    false, EventMovementFailureReason.TELEPORT_FAILED
                );
            }
        }

        private EventMovementGateway.Options teleportOptions() {
            EventMovementGateway.Options value = request.options();
            return new EventMovementGateway.Options(
                value.mode(), value.speed(), value.lockInput(), value.collision(),
                value.safeLanding(), value.preloadChunks(), EventMovementGateway.Fade.NONE
            );
        }

        private void hideFade() {
            if (!fadeVisible) return;
            EventDialogueNetwork.setFade(
                owner,
                request.options().fade() == EventMovementGateway.Fade.WHITE
                    ? EventPresentationGateway.FadeColor.WHITE
                    : EventPresentationGateway.FadeColor.BLACK,
                false
            );
            fadeVisible = false;
        }
    }

    private static final class EntityWalkAgent implements EventWalkController.Agent {
        private final Entity entity;

        private EntityWalkAgent(Entity entity) {
            this.entity = entity;
        }

        @Override
        public EventWalkController.Position position() {
            return new EventWalkController.Position(
                entity.getX(), entity.getY(), entity.getZ()
            );
        }

        @Override
        public boolean canOccupy(EventWalkController.Position position) {
            if (!(entity.level() instanceof ServerLevel level)) return false;
            Vec3 offset = new Vec3(
                position.x() - entity.getX(),
                position.y() - entity.getY(),
                position.z() - entity.getZ()
            );
            return level.noCollision(entity, entity.getBoundingBox().move(offset));
        }

        @Override
        public boolean hasSupport(EventWalkController.Position position) {
            if (!(entity.level() instanceof ServerLevel level)) return false;
            BlockPos floor = BlockPos.containing(
                position.x(), position.y() - 0.05D, position.z()
            );
            if (level.getBlockState(floor).is(Blocks.BARRIER)
                || !level.getFluidState(floor.above()).isEmpty()) {
                return false;
            }
            return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
        }

        @Override
        public void moveTo(EventWalkController.Position position) {
            double x = position.x() - entity.getX();
            double z = position.z() - entity.getZ();
            if (Math.abs(x) + Math.abs(z) > 1.0E-6D) {
                entity.setYRot((float) Math.toDegrees(Math.atan2(-x, z)));
            }
            entity.teleportTo(position.x(), position.y(), position.z());
            entity.setDeltaMovement(Vec3.ZERO);
        }

        @Override
        public void setInputLocked(boolean locked) {
            entity.setDeltaMovement(Vec3.ZERO);
            // The common await lock adapter owns client input for the full event chain.
        }
    }
}
