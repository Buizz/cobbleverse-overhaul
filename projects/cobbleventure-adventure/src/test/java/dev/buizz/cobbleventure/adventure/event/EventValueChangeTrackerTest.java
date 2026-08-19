package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventValueChangeTrackerTest {
    @Test
    void firstObservationIsBaselineAndOnlyRealChangesFire() {
        EventValueChangeTracker<String, Boolean> tracker = new EventValueChangeTracker<>();

        assertFalse(tracker.changed("story", false));
        assertFalse(tracker.changed("story", false));
        assertTrue(tracker.changed("story", true));
        assertFalse(tracker.changed("story", true));
    }

    @Test
    void forgottenSubscriptionsStartWithANewBaseline() {
        EventValueChangeTracker<String, Boolean> tracker = new EventValueChangeTracker<>();
        tracker.changed("story", false);
        tracker.retainAll(Set.of());

        assertFalse(tracker.changed("story", true));

        tracker.clear();
        assertFalse(tracker.changed("story", false));
    }
}
