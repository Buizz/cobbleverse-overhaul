package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class EventNpcTriggerModeTest {
    @Test
    void defaultV5RepresentationAcceptsOnlyInteraction() {
        Set<String> tags = Set.of("cves_binding/cobbleventure/test");

        assertTrue(EventNpcTriggerMode.acceptsInteraction(tags));
        assertFalse(EventNpcTriggerMode.acceptsProximity(tags));
    }

    @Test
    void proximityV5RepresentationAcceptsOnlyProximity() {
        Set<String> tags = Set.of(
            "cves_binding/cobbleventure/test",
            EventNpcTriggerMode.PROXIMITY_TAG
        );

        assertFalse(EventNpcTriggerMode.acceptsInteraction(tags));
        assertTrue(EventNpcTriggerMode.acceptsProximity(tags));
    }
}
