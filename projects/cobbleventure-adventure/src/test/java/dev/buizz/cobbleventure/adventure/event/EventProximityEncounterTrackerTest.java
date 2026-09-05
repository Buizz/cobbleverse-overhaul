package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EventProximityEncounterTrackerTest {
    private static final EventTriggerContract.Options WARNING =
        new EventTriggerContract.Options(
            9.0D, false, 0.0D, "player",
            "trainer_battle", "warning", null
        );
    private static final EventTriggerContract.Options CHALLENGE =
        new EventTriggerContract.Options(
            6.0D, false, 0.0D, "player",
            "trainer_battle", null, "warning"
        );

    @Test
    void requiresAnOutsideObservationAndASeparateWarningTick() {
        EventProximityEncounterTracker<String> tracker = new EventProximityEncounterTracker<>();
        assertEquals(
            EventProximityEncounterTracker.Decision.SKIP,
            tracker.observe("npc", WARNING, true, EventProximityTracker.Transition.ENTER, 10)
        );
        tracker.observe("npc", WARNING, false, EventProximityTracker.Transition.EXIT, 11);
        assertEquals(
            EventProximityEncounterTracker.Decision.FIRE,
            tracker.observe("npc", WARNING, true, EventProximityTracker.Transition.ENTER, 12)
        );
        tracker.markFired("npc", WARNING, 12);
        assertEquals(
            EventProximityEncounterTracker.Decision.SKIP,
            tracker.observe("npc", CHALLENGE, true, EventProximityTracker.Transition.ENTER, 12)
        );
        assertEquals(
            EventProximityEncounterTracker.Decision.FIRE_AND_CLEAR,
            tracker.observe("npc", CHALLENGE, true, EventProximityTracker.Transition.NONE, 13)
        );
    }

    @Test
    void consumesTheChallengeUntilTheOuterRangeIsExited() {
        EventProximityEncounterTracker<String> tracker = new EventProximityEncounterTracker<>();
        tracker.observe("npc", WARNING, false, EventProximityTracker.Transition.NONE, 1);
        tracker.markFired("npc", WARNING, 2);
        tracker.markFired("npc", CHALLENGE, 3);
        assertEquals(
            EventProximityEncounterTracker.Decision.SKIP,
            tracker.observe("npc", CHALLENGE, true, EventProximityTracker.Transition.ENTER, 4)
        );
        assertEquals(
            EventProximityEncounterTracker.Decision.SKIP,
            tracker.observe("npc", WARNING, false, EventProximityTracker.Transition.EXIT, 5)
        );
        assertEquals(
            EventProximityEncounterTracker.Decision.FIRE,
            tracker.observe("npc", WARNING, true, EventProximityTracker.Transition.ENTER, 6)
        );
    }

    @Test
    void suspendedPlayerKeepsEncounterArmingStateWhileBattleIsRunning() {
        EventProximityEncounterTracker<String> tracker = new EventProximityEncounterTracker<>();
        tracker.observe("npc", WARNING, false, EventProximityTracker.Transition.NONE, 1);

        tracker.retainAll(Set.of(), "npc"::equals);

        assertEquals(
            EventProximityEncounterTracker.Decision.FIRE,
            tracker.observe("npc", WARNING, true, EventProximityTracker.Transition.ENTER, 2)
        );
    }
}
