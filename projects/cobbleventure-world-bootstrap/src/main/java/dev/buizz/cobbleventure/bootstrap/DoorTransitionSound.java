package dev.buizz.cobbleventure.bootstrap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Replays door sounds after the client has completed a cross-dimension load. */
final class DoorTransitionSound {
    private static final int DIMENSION_LOAD_DELAY_TICKS = 8;
    private static final int MAX_WAIT_TICKS = 40;
    private static final double MAX_TARGET_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final Map<UUID, PendingSound> PENDING = new HashMap<>();

    private DoorTransitionSound() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(DoorTransitionSound::onServerTick);
    }

    static void reset() {
        PENDING.clear();
    }

    static void afterTeleport(
        ServerPlayer player, ResourceKey<Level> sourceDimension, BlockPos target
    ) {
        if (player.level().dimension().equals(sourceDimension)) {
            play(player);
            return;
        }
        PENDING.put(player.getUUID(), new PendingSound(
            player.level().dimension(), target.immutable(),
            DIMENSION_LOAD_DELAY_TICKS, 0
        ));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, PendingSound>> iterator =
            PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingSound> entry = iterator.next();
            PendingSound pending = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList()
                .getPlayer(entry.getKey());
            if (player == null || pending.waitedTicks() >= MAX_WAIT_TICKS) {
                iterator.remove();
                continue;
            }
            if (pending.delayTicks() > 0) {
                entry.setValue(pending.tickDelay());
                continue;
            }
            if (!player.level().dimension().equals(pending.dimension())
                || player.distanceToSqr(
                    pending.target().getX() + 0.5D,
                    pending.target().getY(),
                    pending.target().getZ() + 0.5D
                ) > MAX_TARGET_DISTANCE_SQUARED) {
                entry.setValue(pending.waitOneTick());
                continue;
            }
            play(player);
            iterator.remove();
        }
    }

    private static void play(ServerPlayer player) {
        player.playNotifySound(
            SoundEvents.WOODEN_DOOR_OPEN,
            SoundSource.BLOCKS, 0.9F, 1.0F
        );
    }

    private record PendingSound(
        ResourceKey<Level> dimension,
        BlockPos target,
        int delayTicks,
        int waitedTicks
    ) {
        private PendingSound tickDelay() {
            return new PendingSound(
                dimension, target, delayTicks - 1, waitedTicks + 1
            );
        }

        private PendingSound waitOneTick() {
            return new PendingSound(dimension, target, 0, waitedTicks + 1);
        }
    }
}
