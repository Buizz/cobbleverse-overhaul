package dev.buizz.cobbleventure.bootstrap;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEntranceTriggerTest {
    @Test
    void acceptsAnyBlockInAConnectedTransitionRegion() {
        Vec3 player = new Vec3(12.5D, 5.0D, 15.5D);

        assertFalse(DungeonSystem.isNearAnyEntranceTrigger(
            player, Set.of(new BlockPos(12, 1, 15)), 9.0D
        ));
        assertTrue(DungeonSystem.isNearAnyEntranceTrigger(
            player,
            Set.of(new BlockPos(12, 1, 15), new BlockPos(12, 5, 15)),
            9.0D
        ));
    }

    @Test
    void rejectsTheSameHorizontalPositionOnAnotherFloor() {
        assertFalse(DungeonSystem.isNearAnyEntranceTrigger(
            new Vec3(12.5D, 8.0D, 15.5D),
            Set.of(new BlockPos(12, 3, 15)),
            100.0D
        ));
    }

    @Test
    void transitionRadiusAllowsAComfortableDoorApproach() {
        BlockPos barrier = new BlockPos(12, 3, 15);

        assertTrue(DungeonSystem.isNearAnyEntranceTrigger(
            new Vec3(14.5D, 3.0D, 15.5D), Set.of(barrier), 6.25D
        ));
        assertFalse(DungeonSystem.isNearAnyEntranceTrigger(
            new Vec3(15.5D, 3.0D, 15.5D), Set.of(barrier), 6.25D
        ));
    }

    @Test
    void acceptsDoorStepsButStillRejectsAnotherFloor() {
        BlockPos barrier = new BlockPos(12, 3, 15);

        assertTrue(DungeonSystem.isNearAnyEntranceTrigger(
            new Vec3(12.5D, 5.5D, 15.5D), Set.of(barrier), 9.0D, 2.0D
        ));
        assertFalse(DungeonSystem.isNearAnyEntranceTrigger(
            new Vec3(12.5D, 7.5D, 15.5D), Set.of(barrier), 9.0D, 2.0D
        ));
    }
}
