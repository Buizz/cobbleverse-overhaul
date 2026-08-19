package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildingEventSpaceBoundsTest {
    @Test
    void includesOriginAndLastTemplateBlockButNotAdjacentSlots() {
        BlockPos origin = new BlockPos(100, 64, -20);
        Vec3i size = new Vec3i(10, 6, 8);

        assertTrue(BuildingEventSpaceBounds.contains(origin, size, origin));
        assertTrue(BuildingEventSpaceBounds.contains(
            origin, size, new BlockPos(109, 69, -13)
        ));
        assertFalse(BuildingEventSpaceBounds.contains(
            origin, size, new BlockPos(110, 69, -13)
        ));
        assertFalse(BuildingEventSpaceBounds.contains(
            origin, size, new BlockPos(109, 70, -13)
        ));
        assertFalse(BuildingEventSpaceBounds.contains(origin, null, origin));
    }
}
