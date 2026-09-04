package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Resolves the session NPC and synchronizes authored facing on the server. */
public final class EventFacingBridge {
    /** Conservative viewport approximation: only turn when the NPC is clearly off-screen. */
    static final float DIALOGUE_HORIZONTAL_HALF_ANGLE = 60.0F;
    static final float DIALOGUE_VERTICAL_HALF_ANGLE = 45.0F;
    private static final Set<EventSessionKey> DIALOGUE_SEQUENCES =
        ConcurrentHashMap.newKeySet();

    private EventFacingBridge() {}

    public static EventFacingGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> face(player, request);
    }

    /**
     * Faces the session NPC only when its eye position is outside the player's view.
     * This is presentation behavior for ordinary dialogue, not an authored `face` command.
     */
    static boolean faceDialogueNpcIfOutsideView(
        ServerPlayer player, EventSessionKey sessionKey
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(sessionKey, "sessionKey");
        if (!sessionKey.playerId().equals(player.getUUID())) return false;
        if (!claimDialogueFacing(sessionKey)) return false;
        Entity npc = findNpc(player, sessionKey);
        if (npc == null || npc.level() != player.level()) return false;
        Rotation target = rotationToward(
            player.getEyePosition(), npc instanceof LivingEntity living
                ? living.getEyePosition() : npc.getBoundingBox().getCenter(),
            player.getYRot()
        );
        if (isInsideDialogueView(
            player.getYRot(), player.getXRot(), target.yaw(), target.pitch()
        )) {
            return false;
        }
        player.teleportTo(
            player.serverLevel(),
            player.getX(), player.getY(), player.getZ(),
            target.yaw(), target.pitch()
        );
        return true;
    }

    static void beginDialogueSequence(EventSessionKey sessionKey) {
        DIALOGUE_SEQUENCES.remove(Objects.requireNonNull(sessionKey, "sessionKey"));
    }

    static boolean claimDialogueFacing(EventSessionKey sessionKey) {
        return DIALOGUE_SEQUENCES.add(Objects.requireNonNull(sessionKey, "sessionKey"));
    }

    static boolean isInsideDialogueView(
        float viewerYaw, float viewerPitch, float targetYaw, float targetPitch
    ) {
        return Math.abs(wrapDegrees(targetYaw - viewerYaw))
                <= DIALOGUE_HORIZONTAL_HALF_ANGLE
            && Math.abs(targetPitch - viewerPitch) <= DIALOGUE_VERTICAL_HALF_ANGLE;
    }

    static Rotation rotationToward(
        net.minecraft.world.phys.Vec3 origin,
        net.minecraft.world.phys.Vec3 target,
        float fallbackYaw
    ) {
        double x = target.x() - origin.x();
        double y = target.y() - origin.y();
        double z = target.z() - origin.z();
        double horizontal = Math.sqrt(x * x + z * z);
        float yaw = horizontal < 1.0E-6D
            ? fallbackYaw : (float) Math.toDegrees(Math.atan2(-x, z));
        float pitch = (float) -Math.toDegrees(Math.atan2(y, horizontal));
        return new Rotation(yaw, Math.max(-90.0F, Math.min(90.0F, pitch)));
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    record Rotation(float yaw, float pitch) {}

    private static void face(
        ServerPlayer player, EventFacingGateway.FacingRequest request
    ) {
        if (!request.sessionKey().playerId().equals(player.getUUID())) {
            throw new EventRuntimeException("face 요청의 player와 gateway player가 다릅니다.");
        }
        Entity npc = findNpc(player, request.sessionKey());
        Entity subject = request.subject() == EventFacingGateway.Subject.PLAYER ? player : npc;
        if (subject == null) {
            throw new EventRuntimeException("face 대상 NPC가 로드되지 않았습니다.");
        }
        float yaw = switch (request.direction()) {
            case SOUTH -> 0F;
            case WEST -> 90F;
            case NORTH -> 180F;
            case EAST -> -90F;
            case PLAYER -> yawToward(subject, player);
            case NPC -> {
                if (npc == null) {
                    throw new EventRuntimeException("바라볼 NPC가 로드되지 않았습니다.");
                }
                yield yawToward(subject, npc);
            }
        };
        subject.setYRot(yaw);
        if (subject instanceof LivingEntity living) {
            living.setYHeadRot(yaw);
            living.setYBodyRot(yaw);
        }
        if (subject instanceof ServerPlayer serverPlayer) {
            serverPlayer.teleportTo(
                serverPlayer.serverLevel(),
                serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                yaw, serverPlayer.getXRot()
            );
        }
    }

    private static float yawToward(Entity subject, Entity target) {
        if (subject == target) {
            throw new EventRuntimeException("face subject와 바라볼 대상이 같습니다.");
        }
        if (subject.level() != target.level()) {
            throw new EventRuntimeException("서로 다른 차원의 대상을 바라볼 수 없습니다.");
        }
        double x = target.getX() - subject.getX();
        double z = target.getZ() - subject.getZ();
        if (Math.abs(x) + Math.abs(z) < 1.0E-6D) {
            throw new EventRuntimeException("같은 위치의 대상을 바라볼 수 없습니다.");
        }
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    private static Entity findNpc(ServerPlayer player, EventSessionKey key) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity npc = level.getEntity(key.npcId());
            if (npc != null && npc.isAlive()) return npc;
        }
        return null;
    }
}
