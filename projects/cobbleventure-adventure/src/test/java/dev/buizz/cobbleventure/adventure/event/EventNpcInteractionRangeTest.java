package dev.buizz.cobbleventure.adventure.event;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventNpcInteractionRangeTest {
    private static final AABB NPC_BOUNDS = new AABB(
        4.5D, 0.0D, -0.5D,
        5.5D, 2.0D, 0.5D
    );

    @Test
    void acceptsAPlayerWithinRangeOfTheInteractableBounds() {
        assertTrue(EventNpcInteractionRange.contains(
            new Vec3(0.75D, 0.0D, 0.0D), NPC_BOUNDS, 4.0D
        ));
    }

    @Test
    void rejectsAPlayerOutsideRangeOfTheInteractableBounds() {
        assertFalse(EventNpcInteractionRange.contains(
            new Vec3(0.49D, 0.0D, 0.0D), NPC_BOUNDS, 4.0D
        ));
    }

    @Test
    void doesNotChargeDistanceAlongAxesAlreadyInsideTheBounds() {
        assertTrue(EventNpcInteractionRange.contains(
            new Vec3(4.75D, 1.0D, 0.0D), NPC_BOUNDS, 0.0D
        ));
    }

    @Test
    void directClicksHonorCreativeOrExtendedPlayerReach() {
        assertEquals(5.25D, EventNpcInteractionRange.directClickRange(4.0D, 5.0D));
    }

    @Test
    void directClicksRetainAHelpfullyLargerScriptedRange() {
        assertEquals(6.25D, EventNpcInteractionRange.directClickRange(6.0D, 3.0D));
    }
}
