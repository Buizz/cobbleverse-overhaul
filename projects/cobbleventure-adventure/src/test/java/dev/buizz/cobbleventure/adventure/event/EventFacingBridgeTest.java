package dev.buizz.cobbleventure.adventure.event;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EventFacingBridgeTest {
    @Test
    void onlyFirstNpcLineInOneDialogueSequenceCanAdjustFacing() {
        var key = new EventSessionKey(
            UUID.randomUUID(), UUID.randomUUID(), "test:event_script/dialogue", "interact"
        );
        EventFacingBridge.beginDialogueSequence(key);
        assertTrue(EventFacingBridge.claimDialogueFacing(key));
        assertFalse(EventFacingBridge.claimDialogueFacing(key));
        EventFacingBridge.beginDialogueSequence(key);
        assertTrue(EventFacingBridge.claimDialogueFacing(key));
    }

    @Test
    void dialogueNpcAlreadyInsideViewDoesNotRequireTurning() {
        assertTrue(EventFacingBridge.isInsideDialogueView(0F, 0F, 0F, 0F));
        assertTrue(EventFacingBridge.isInsideDialogueView(0F, 0F, 60F, 45F));
        assertTrue(EventFacingBridge.isInsideDialogueView(179F, 0F, -179F, 0F));
    }

    @Test
    void dialogueNpcClearlyOutsideViewRequiresTurning() {
        assertFalse(EventFacingBridge.isInsideDialogueView(0F, 0F, 60.01F, 0F));
        assertFalse(EventFacingBridge.isInsideDialogueView(0F, 0F, 0F, 45.01F));
        assertFalse(EventFacingBridge.isInsideDialogueView(0F, 0F, 180F, 0F));
    }

    @Test
    void rotationTargetsNpcEyeWithoutMovingThePlayer() {
        var behind = EventFacingBridge.rotationToward(
            new Vec3(0, 1.6, 0), new Vec3(0, 1.6, -4), 20F
        );
        assertEquals(-180F, behind.yaw(), 0.001F);
        assertEquals(0F, behind.pitch(), 0.001F);

        var above = EventFacingBridge.rotationToward(
            new Vec3(2, 2, 3), new Vec3(2, 5, 3), 35F
        );
        assertEquals(35F, above.yaw(), 0.001F);
        assertEquals(-90F, above.pitch(), 0.001F);
    }
}
