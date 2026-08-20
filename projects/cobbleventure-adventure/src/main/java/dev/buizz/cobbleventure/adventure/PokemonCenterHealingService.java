package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.block.entity.HealingMachineBlockEntity;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Shared Pokemon Center machine logic used by V5 events and the legacy fallback. */
public final class PokemonCenterHealingService {
    public enum StartStatus {
        STARTED,
        HEALING_MACHINE_NOT_FOUND,
        HEALING_UNAVAILABLE
    }

    public enum Progress {
        RUNNING,
        COMPLETED,
        HEALING_MACHINE_NOT_FOUND,
        HEALING_INTERRUPTED
    }

    public record StartResult(StartStatus status, BlockPos machinePosition) {
        public StartResult {
            Objects.requireNonNull(status, "status");
            machinePosition = machinePosition == null ? null : machinePosition.immutable();
            if ((status == StartStatus.STARTED) != (machinePosition != null)) {
                throw new IllegalArgumentException("시작된 치료에만 치료기 위치가 필요합니다.");
            }
        }
    }

    private PokemonCenterHealingService() {}

    public static StartResult start(ServerPlayer player, BlockPos center, int radius) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(center, "center");
        if (radius < 1 || radius > 32) {
            throw new IllegalArgumentException("치료기 검색 반경은 1..32여야 합니다.");
        }
        HealingMachineBlockEntity healer = nearestHealingMachine(
            player.serverLevel(), center, radius
        );
        if (healer == null) {
            return new StartResult(StartStatus.HEALING_MACHINE_NOT_FOUND, null);
        }
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        if (!healer.canHeal(party)) {
            return new StartResult(StartStatus.HEALING_UNAVAILABLE, null);
        }
        healer.activate(player.getUUID(), party);
        return new StartResult(StartStatus.STARTED, healer.getBlockPos());
    }

    public static Progress progress(ServerPlayer player, BlockPos machinePosition) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(machinePosition, "machinePosition");
        if (!(player.serverLevel().getBlockEntity(machinePosition)
            instanceof HealingMachineBlockEntity healer)) {
            return Progress.HEALING_MACHINE_NOT_FOUND;
        }
        if (player.getUUID().equals(healer.getCurrentUser())) {
            return healer.isInUse() ? Progress.RUNNING : Progress.COMPLETED;
        }
        return healer.isInUse() ? Progress.HEALING_INTERRUPTED : Progress.COMPLETED;
    }

    private static HealingMachineBlockEntity nearestHealingMachine(
        ServerLevel level, BlockPos center, int radius
    ) {
        HealingMachineBlockEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos position : BlockPos.betweenClosed(
            center.offset(-radius, -3, -radius), center.offset(radius, 3, radius)
        )) {
            if (!(level.getBlockEntity(position) instanceof HealingMachineBlockEntity candidate)) {
                continue;
            }
            double distance = position.distSqr(center);
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
