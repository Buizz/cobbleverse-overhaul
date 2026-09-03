package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RegionalNpcPairPlacementTest {
    @Test
    void partnerStaysBesideOwnerForEveryFacing() {
        BlockPos origin = new BlockPos(10, 70, -20);
        assertEquals(origin.offset(2, 0, 0), RegionalNpcPairPlacement.partnerPosition(origin, 0));
        assertEquals(origin.offset(0, 0, 2), RegionalNpcPairPlacement.partnerPosition(origin, 90));
        assertEquals(origin.offset(-2, 0, 0), RegionalNpcPairPlacement.partnerPosition(origin, 180));
        assertEquals(origin.offset(0, 0, -2), RegionalNpcPairPlacement.partnerPosition(origin, 270));
        for (int yaw = 0; yaw < 360; yaw++) {
            BlockPos partner = RegionalNpcPairPlacement.partnerPosition(origin, yaw);
            assertEquals(70, partner.getY());
            assertTrue(partner.distSqr(origin) >= 2);
        }
    }
}
