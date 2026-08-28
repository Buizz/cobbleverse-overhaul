package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Defines a symmetric two-trainer formation around an authored encounter marker. */
final class DungeonEncounterFormation {
    static final int PLAYER_DISTANCE = 5;

    private DungeonEncounterFormation() {}

    static Formation create(BlockPos center, float opponentYaw, int actorCount) {
        Direction facing = Direction.fromYRot(opponentYaw);
        if (actorCount <= 1) {
            return new Formation(
                List.of(center), List.of(center.relative(facing, PLAYER_DISTANCE)),
                facing
            );
        }
        if (actorCount != 2) {
            throw new IllegalStateException(
                "Dungeon battle formation supports one or two actors: " + actorCount
            );
        }
        Direction right = facing.getClockWise();
        BlockPos firstOpponent = center.relative(right.getOpposite());
        BlockPos secondOpponent = center.relative(right);
        return new Formation(
            List.of(firstOpponent, secondOpponent),
            List.of(
                firstOpponent.relative(facing, PLAYER_DISTANCE),
                secondOpponent.relative(facing, PLAYER_DISTANCE)
            ),
            facing
        );
    }

    record Formation(
        List<BlockPos> opponents,
        List<BlockPos> players,
        Direction opponentFacing
    ) {
        float playerYaw() {
            return opponentFacing.getOpposite().toYRot();
        }
    }
}
