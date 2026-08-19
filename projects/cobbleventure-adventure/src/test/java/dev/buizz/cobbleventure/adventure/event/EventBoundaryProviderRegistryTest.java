package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventBoundaryProviderRegistryTest {
    @AfterEach
    void clearProvider() {
        EventBoundaryProviderRegistry.clearForTests();
    }

    @Test
    void missingProviderIsExplicitlyUnavailable() {
        assertTrue(EventBoundaryProviderRegistry.snapshot(null).isEmpty());
    }

    @Test
    void snapshotsAreImmutableAndRejectInvalidIds() {
        EventBoundaryProviderRegistry.Snapshot snapshot =
            new EventBoundaryProviderRegistry.Snapshot(
                Set.of("test:region/start"), Set.of("test:anchor/door"),
                Set.of("test:building/lab"), Set.of("test:world")
            );

        assertEquals(Set.of("test:region/start"), snapshot.regions());
        assertThrows(UnsupportedOperationException.class, () ->
            snapshot.regions().add("test:region/other")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new EventBoundaryProviderRegistry.Snapshot(
                Set.of("bad id"), Set.of(), Set.of(), Set.of()
            )
        );
    }

    @Test
    void duplicateProviderRegistrationIsRejected() {
        EventBoundaryProviderRegistry.register(player ->
            new EventBoundaryProviderRegistry.Snapshot(
                Set.of(), Set.of(), Set.of(), Set.of()
            )
        );

        assertThrows(IllegalStateException.class, () ->
            EventBoundaryProviderRegistry.register(player ->
                new EventBoundaryProviderRegistry.Snapshot(
                    Set.of(), Set.of(), Set.of(), Set.of()
                )
            )
        );
    }
}
