package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/** Deterministic, validated placement result shared by preview and runtime generation. */
record DungeonPiecePlan(
    long seed,
    BlockPos bounds,
    List<Placement> placements,
    List<Link> links
) {
    record Placement(
        int index,
        String pieceId,
        String role,
        BlockPos templateOrigin,
        Rotation rotation,
        BlockPos minimum,
        BlockPos size,
        boolean criticalPath
    ) {}

    record Link(
        int fromIndex,
        String fromConnector,
        int toIndex,
        String toConnector,
        boolean criticalPath
    ) {}
}
