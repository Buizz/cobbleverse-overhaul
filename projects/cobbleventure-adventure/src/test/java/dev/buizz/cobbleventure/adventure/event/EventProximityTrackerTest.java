package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EventProximityTrackerTest {
    @Test
    void initiallyInsideFiresEnterAndStableSamplesDoNotRepeat() {
        EventProximityTracker<String> tracker = new EventProximityTracker<>();

        assertEquals(EventProximityTracker.Transition.ENTER, tracker.observe("npc", true));
        assertEquals(EventProximityTracker.Transition.NONE, tracker.observe("npc", true));
        assertEquals(EventProximityTracker.Transition.EXIT, tracker.observe("npc", false));
        assertEquals(EventProximityTracker.Transition.NONE, tracker.observe("npc", false));
        assertEquals(EventProximityTracker.Transition.ENTER, tracker.observe("npc", true));
    }

    @Test
    void initiallyOutsideDoesNotInventExitAndUnloadedKeysAreForgotten() {
        EventProximityTracker<String> tracker = new EventProximityTracker<>();

        assertEquals(EventProximityTracker.Transition.NONE, tracker.observe("npc", false));
        tracker.observe("other", true);
        tracker.retainAll(Set.of("npc"));
        assertEquals(1, tracker.size());
        assertEquals(EventProximityTracker.Transition.ENTER, tracker.observe("other", true));
    }

    @Test
    void suspendedPlayerKeepsItsInsideStateWhileBattleIsRunning() {
        EventProximityTracker<String> tracker = new EventProximityTracker<>();
        tracker.observe("npc", true);

        tracker.retainAll(Set.of(), "npc"::equals);

        assertEquals(EventProximityTracker.Transition.NONE, tracker.observe("npc", true));
    }
}
