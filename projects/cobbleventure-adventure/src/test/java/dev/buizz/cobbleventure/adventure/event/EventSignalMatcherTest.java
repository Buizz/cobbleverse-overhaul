package dev.buizz.cobbleventure.adventure.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventSignalMatcherTest {
    @Test
    void requiresBothSignalKindAndTypedTargetIdentity() {
        assertTrue(EventSignalMatcher.matches(
            "flag_changed", "test:flag/story", "flag_changed", "test:flag/story"
        ));
        assertFalse(EventSignalMatcher.matches(
            "item_used", "test:item/key", "flag_changed", "test:item/key"
        ));
        assertFalse(EventSignalMatcher.matches(
            "battle_finished", "test:battle/one",
            "battle_finished", "test:battle/two"
        ));
        assertThrows(EventRuntimeException.class, () -> EventSignalMatcher.matches(
            "unknown", "test:any", "unknown", "test:any"
        ));
    }
}
