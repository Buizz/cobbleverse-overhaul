package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** Exact placed-template bounds used to identify an authored building interior. */
final class BuildingEventSpaceBounds {
    private BuildingEventSpaceBounds() {}

    static boolean contains(BlockPos origin, Vec3i size, BlockPos position) {
        return size != null
            && position.getX() >= origin.getX()
            && position.getY() >= origin.getY()
            && position.getZ() >= origin.getZ()
            && position.getX() < origin.getX() + size.getX()
            && position.getY() < origin.getY() + size.getY()
            && position.getZ() < origin.getZ() + size.getZ();
    }
}
