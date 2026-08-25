package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonRunBoundsTest {
    private static final BlockPos ORIGIN = new BlockPos(32768, 80, 0);
    private static final BlockPos SIZE = new BlockPos(48, 8, 48);

    @Test
    void acceptsTheTemplateAndSmallEdgeTolerance() {
        assertTrue(DungeonSystem.insideRunBounds(
            new Vec3(32768.5D, 81.0D, 0.5D), ORIGIN, SIZE
        ));
        assertTrue(DungeonSystem.insideRunBounds(
            new Vec3(32766.5D, 80.0D, 20.0D), ORIGIN, SIZE
        ));
    }

    @Test
    void rejectsTeleportsOutsideTheAllocatedSlot() {
        assertFalse(DungeonSystem.insideRunBounds(
            new Vec3(33000.0D, 81.0D, 20.0D), ORIGIN, SIZE
        ));
        assertFalse(DungeonSystem.insideRunBounds(
            new Vec3(32780.0D, 60.0D, 20.0D), ORIGIN, SIZE
        ));
    }
}
