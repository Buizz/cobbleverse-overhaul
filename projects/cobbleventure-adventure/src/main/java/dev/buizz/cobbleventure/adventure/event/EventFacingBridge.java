package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Resolves the session NPC and synchronizes authored facing on the server. */
public final class EventFacingBridge {
    private EventFacingBridge() {}

    public static EventFacingGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> face(player, request);
    }

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
