package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/** Keeps participating players close to every Cobblemon battle without locking movement. */
final class BattleMovementBoundary {
    private static final double HORIZONTAL_RADIUS_SQUARED = 24.0D * 24.0D;
    private static final double VERTICAL_RADIUS = 12.0D;
    private static final double MAX_TICK_TRAVEL_SQUARED = 8.0D * 8.0D;
    private static final long MESSAGE_COOLDOWN_TICKS = 40L;
    private static final Map<UUID, Boundary> BOUNDARIES = new HashMap<>();

    private BattleMovementBoundary() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(BattleMovementBoundary::onTeleport);
    }

    static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player, server);
        BOUNDARIES.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    static void forget(ServerPlayer player) {
        BOUNDARIES.remove(player.getUUID());
    }

    private static void tick(ServerPlayer player, MinecraftServer server) {
        PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
        if (battle == null) {
            BOUNDARIES.remove(player.getUUID());
            return;
        }
        Boundary boundary = BOUNDARIES.get(player.getUUID());
        if (boundary == null || !boundary.battleId.equals(battle.getBattleId())) {
            BOUNDARIES.put(player.getUUID(), new Boundary(
                battle.getBattleId(), player.serverLevel().dimension(),
                player.position(), player.getYRot(), player.getXRot()
            ));
            return;
        }
        if (!player.isAlive()) return;
        Vec3 current = player.position();
        double dx = current.x - boundary.anchor.x;
        double dz = current.z - boundary.anchor.z;
        boolean changedDimension = !player.serverLevel().dimension().equals(boundary.dimension);
        boolean outsideArena = dx * dx + dz * dz > HORIZONTAL_RADIUS_SQUARED
            || Math.abs(current.y - boundary.anchor.y) > VERTICAL_RADIUS;
        boolean teleported = !changedDimension
            && current.distanceToSqr(boundary.previousPosition) > MAX_TICK_TRAVEL_SQUARED;
        if (changedDimension || outsideArena || teleported) {
            restore(player, server, boundary);
            warn(player, boundary);
            return;
        }
        boundary.previousPosition = current;
    }

    private static void onTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || BattleRegistry.getBattleByParticipatingPlayer(player) == null) {
            return;
        }
        event.setCanceled(true);
        Boundary boundary = BOUNDARIES.get(player.getUUID());
        if (boundary != null) warn(player, boundary);
        else player.displayClientMessage(Component.literal(
            "[Cobbleventure] 전투 중에는 순간이동할 수 없습니다."
        ), true);
    }

    private static void restore(
        ServerPlayer player, MinecraftServer server, Boundary boundary
    ) {
        ServerLevel destination = server.getLevel(boundary.dimension);
        if (destination == null) return;
        player.stopRiding();
        player.teleportTo(
            destination,
            boundary.anchor.x,
            boundary.anchor.y,
            boundary.anchor.z,
            boundary.yRot,
            boundary.xRot
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static void warn(ServerPlayer player, Boundary boundary) {
        long gameTime = player.serverLevel().getGameTime();
        if (gameTime < boundary.nextMessageTick) return;
        boundary.nextMessageTick = gameTime + MESSAGE_COOLDOWN_TICKS;
        player.displayClientMessage(Component.literal(
            "[Cobbleventure] 전투가 끝날 때까지 전장 주변을 벗어날 수 없습니다."
        ), true);
    }

    private static final class Boundary {
        private final UUID battleId;
        private final ResourceKey<Level> dimension;
        private final Vec3 anchor;
        private final float yRot;
        private final float xRot;
        private Vec3 previousPosition;
        private long nextMessageTick;

        private Boundary(
            UUID battleId, ResourceKey<Level> dimension, Vec3 anchor,
            float yRot, float xRot
        ) {
            this.battleId = battleId;
            this.dimension = dimension;
            this.anchor = anchor;
            this.previousPosition = anchor;
            this.yRot = yRot;
            this.xRot = xRot;
        }
    }
}
