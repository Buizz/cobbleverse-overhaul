package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class FacilityVendorOwnershipTest {
    private static final BlockPos ORIGIN = new BlockPos(100, 60, 100);
    private static final BlockPos COUNTER = new BlockPos(107, 61, 104);
    private static final AABB BOUNDS = new AABB(88, 56, 88, 135, 80, 135);

    @Test
    void removesOldMerchantEvenWhenItLoadsAfterTheReplacement() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);
        UUID current = UUID.randomUUID();
        ownership.recordSpawn(COUNTER, current);

        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), current));
        assertTrue(ownership.isObsolete(new Vec3(112.5, 61, 108.5), UUID.randomUUID()));
        // Reloading the canonical vendor keeps the same UUID and must not delete it.
        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), current));
    }

    @Test
    void repeatingASlotKeepsOnlyItsLatestOwner() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ownership.recordSpawn(COUNTER, first);
        ownership.recordSpawn(COUNTER, second);

        assertTrue(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), first));
        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), second));
    }

    @Test
    void preservesMultipleAuthoredSlots() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BlockPos otherCounter = COUNTER.offset(4, 0, 0);
        ownership.recordSpawn(COUNTER, first);
        ownership.recordSpawn(otherCounter, second);

        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), first));
        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(otherCounter), second));
    }

    @Test
    void doesNotRemoveAnythingWhenNoReplacementWasSpawned() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);

        assertTrue(ownership.activeBounds().isEmpty());
        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), UUID.randomUUID()));
    }

    @Test
    void customVendorActivatesCleanupWithoutOwningABaseMerchant() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);
        ownership.activateCustomVendor(ORIGIN);

        assertFalse(ownership.activeBounds().isEmpty());
        assertTrue(ownership.isObsolete(
            Vec3.atBottomCenterOf(COUNTER), UUID.randomUUID()
        ));
    }

    @Test
    void refreshInvalidatesOldOwnersButLeavesOutsideMerchantsAlone() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);
        UUID old = UUID.randomUUID();
        ownership.recordSpawn(COUNTER, old);
        ownership.begin(ORIGIN, BOUNDS);
        ownership.recordSpawn(COUNTER, UUID.randomUUID());

        assertTrue(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), old));
        assertFalse(ownership.isObsolete(new Vec3(200, 61, 200), UUID.randomUUID()));
    }

    @Test
    void overlappingCleanupMarginsDoNotDeleteAnotherFacilityOwner() {
        FacilityVendorOwnership ownership = new FacilityVendorOwnership();
        ownership.begin(ORIGIN, BOUNDS);
        UUID current = UUID.randomUUID();
        ownership.recordSpawn(COUNTER, current);
        ownership.begin(ORIGIN.offset(10, 0, 0), BOUNDS.move(10, 0, 0));
        ownership.recordSpawn(new BlockPos(132, 61, 104), UUID.randomUUID());

        assertFalse(ownership.isObsolete(Vec3.atBottomCenterOf(COUNTER), current));
    }
}
