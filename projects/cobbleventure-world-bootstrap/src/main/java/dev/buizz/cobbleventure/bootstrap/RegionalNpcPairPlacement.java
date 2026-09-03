package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;

/** Two visible trainers stand side by side, relative to their authored facing. */
final class RegionalNpcPairPlacement {
    private RegionalNpcPairPlacement() {}

    static BlockPos partnerPosition(BlockPos owner, float yaw) {
        double radians = Math.toRadians(yaw);
        return owner.offset((int) Math.round(Math.cos(radians) * 2), 0,
            (int) Math.round(Math.sin(radians) * 2));
    }
}
