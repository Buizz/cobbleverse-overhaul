package dev.buizz.cobbleventure.adventure.event;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Resolves direct CVES positions and performs a server-authoritative safe teleport. */
final class EventSafeTeleport {
    record Outcome(boolean arrived, String failureReason) {}
    private record Target(ServerLevel level, double x, double y, double z, float yaw, float pitch) {}

    private EventSafeTeleport() {}

    static Outcome execute(
        ServerPlayer player,
        EventLocationRef destination,
        EventMovementGateway.Options options
    ) {
        if (options.fade() != EventMovementGateway.Fade.NONE) {
            return new Outcome(false, EventMovementFailureReason.FADE_UNAVAILABLE);
        }
        EventLocationRef directDestination = destination;
        if (destination instanceof EventLocationRef.Resource resource) {
            EventLocationResolverRegistry.Resolution resolution;
            try {
                resolution = EventLocationResolverRegistry.resolve(player.getServer(), resource);
            } catch (RuntimeException error) {
                return new Outcome(false, EventMovementFailureReason.LOCATION_RESOLUTION_FAILED);
            }
            if (!resolution.isResolved()) {
                return new Outcome(false, resolution.failureReason());
            }
            directDestination = resolution.location().toPosition();
        }
        Target requested = resolve(player, directDestination);
        if (requested == null) {
            return new Outcome(false, EventMovementFailureReason.DESTINATION_UNAVAILABLE);
        }
        Target target = safeTarget(requested, options);
        if (target == null) return new Outcome(false, EventMovementFailureReason.UNSAFE_LANDING);
        try {
            player.teleportTo(
                target.level(), target.x(), target.y(), target.z(),
                target.yaw(), target.pitch()
            );
            return new Outcome(true, null);
        } catch (RuntimeException error) {
            return new Outcome(false, EventMovementFailureReason.TELEPORT_FAILED);
        }
    }

    private static Target resolve(ServerPlayer player, EventLocationRef destination) {
        if (destination instanceof EventLocationRef.Relative relative) {
            return new Target(
                player.serverLevel(),
                player.getX() + relative.x(),
                player.getY() + relative.y(),
                player.getZ() + relative.z(),
                player.getYRot(), player.getXRot()
            );
        }
        if (destination instanceof EventLocationRef.Position position) {
            ResourceLocation id = ResourceLocation.tryParse(position.dimension());
            if (id == null) return null;
            ServerLevel level = player.getServer().getLevel(
                ResourceKey.create(Registries.DIMENSION, id)
            );
            if (level == null) return null;
            return new Target(
                level, position.x(), position.y(), position.z(),
                position.yaw() == null ? player.getYRot() : position.yaw(),
                position.pitch() == null ? player.getXRot() : position.pitch()
            );
        }
        return null;
    }

    private static Target safeTarget(
        Target requested, EventMovementGateway.Options options
    ) {
        if (options.safeLanding() == EventMovementGateway.SafeLanding.DISABLED) {
            BlockPos position = BlockPos.containing(
                requested.x(), requested.y(), requested.z()
            );
            if (options.preloadChunks()) {
                requested.level().getChunk(position.getX() >> 4, position.getZ() >> 4);
            }
            return insideWorld(requested.level(), position) ? requested : null;
        }
        BlockPos origin = BlockPos.containing(requested.x(), requested.y(), requested.z());
        for (BlockPos candidate : candidates(origin)) {
            if (options.preloadChunks()) {
                requested.level().getChunk(candidate.getX() >> 4, candidate.getZ() >> 4);
            }
            if (!safe(requested.level(), candidate)) continue;
            double x = candidate.equals(origin) ? requested.x() : candidate.getX() + 0.5D;
            double z = candidate.equals(origin) ? requested.z() : candidate.getZ() + 0.5D;
            return new Target(
                requested.level(), x, candidate.getY(), z,
                requested.yaw(), requested.pitch()
            );
        }
        // PREFERRED may search nearby cells, but it must never turn an empty or unsupported
        // destination into a successful teleport. Falling back to the raw coordinate here
        // previously allowed unresolved town/interior anchors to drop players into the void.
        return null;
    }

    private static List<BlockPos> candidates(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int radius = 0; radius <= 2; radius++) {
            for (int yOffset : List.of(0, 1, -1, 2, -2, 3, -3, 4, -4)) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (radius > 0 && Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        result.add(origin.offset(x, yOffset, z));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean safe(ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!insideWorld(level, feet) || !insideWorld(level, head)
            || !insideWorld(level, floor)) return false;
        if (level.getBlockState(feet).is(Blocks.BARRIER)
            || level.getBlockState(head).is(Blocks.BARRIER)
            || level.getBlockState(floor).is(Blocks.BARRIER)) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return false;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()) {
            return false;
        }
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private static boolean insideWorld(ServerLevel level, BlockPos position) {
        return !level.isOutsideBuildHeight(position)
            && level.getWorldBorder().isWithinBounds(position);
    }
}
