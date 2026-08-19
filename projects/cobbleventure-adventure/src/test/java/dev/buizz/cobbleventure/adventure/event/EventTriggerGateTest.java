package dev.buizz.cobbleventure.adventure.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventTriggerGateTest {
    @Test
    void onceCompletionPreventsEveryLaterFire() {
        EventTriggerContract.Options options = options(true, 0.0D);

        assertTrue(EventTriggerGate.canFire(false, null, options, 100L));
        assertFalse(EventTriggerGate.canFire(true, 100L, options, 10_000L));
    }

    @Test
    void cooldownRoundsUpToTicksAndAllowsAfterElapsedWindow() {
        EventTriggerContract.Options options = options(false, 1.01D);

        assertFalse(EventTriggerGate.canFire(false, 100L, options, 120L));
        assertTrue(EventTriggerGate.canFire(false, 100L, options, 121L));
    }

    @Test
    void clockRollbackReleasesStaleCooldownAndOverflowIsRejected() {
        EventTriggerContract.Options options = options(false, 30.0D);

        assertTrue(EventTriggerGate.canFire(false, 1_000L, options, 10L));
        assertThrows(
            EventRuntimeException.class,
            () -> EventTriggerGate.cooldownTicks(Double.MAX_VALUE)
        );
    }

    private static EventTriggerContract.Options options(boolean once, double cooldown) {
        return new EventTriggerContract.Options(4.0D, once, cooldown, "player");
    }
}
