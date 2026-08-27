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
}
