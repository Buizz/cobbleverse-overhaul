package dev.buizz.cobbleventure.adventure.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventLocationResolverRegistryTest {
    @AfterEach
    void clearRegistry() {
        EventLocationResolverRegistry.clearForTests();
    }

    @Test
    void missingProviderReturnsStableFailureInsteadOfInventingCoordinates() {
        EventLocationResolverRegistry.Resolution result =
            EventLocationResolverRegistry.resolve(null, resource());

        assertFalse(result.isResolved());
        assertEquals("location_provider_unavailable", result.failureReason());
    }

    @Test
    void providerReturnsValidatedTypedPosition() {
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.SETTLEMENT,
            (server, destination) -> {
                assertEquals("town_square", destination.anchor());
                return EventLocationResolverRegistry.Resolution.resolved(
                    new EventLocationResolverRegistry.ResolvedLocation(
                        "cobbleventure:generation_1", 100.5, 70, -20.5, 180F, 0F
                    )
                );
            }
        );

        EventLocationResolverRegistry.Resolution result =
            EventLocationResolverRegistry.resolve(null, resource());

        assertTrue(result.isResolved());
        assertEquals(
            new EventLocationRef.Position(
                "cobbleventure:generation_1", 100.5, 70, -20.5, 180F, 0F
            ),
            result.location().toPosition()
        );
    }

    @Test
    void providerFailureReasonIsPreservedForMovementResult() {
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.SETTLEMENT,
            (server, destination) -> EventLocationResolverRegistry.Resolution.failed("anchor_not_found")
        );

        EventLocationResolverRegistry.Resolution result =
            EventLocationResolverRegistry.resolve(null, resource());

        assertFalse(result.isResolved());
        assertEquals("anchor_not_found", result.failureReason());
    }

    @Test
    void providerFailureReasonMustBeAStableSnakeCaseCode() {
        assertThrows(IllegalArgumentException.class, () ->
            EventLocationResolverRegistry.Resolution.failed("Anchor Missing")
        );
        assertThrows(IllegalArgumentException.class, () ->
            EventLocationResolverRegistry.Resolution.failed("")
        );
    }

    @Test
    void duplicateProviderRegistrationIsRejected() {
        EventLocationResolverRegistry.register(
            EventLocationRef.Resource.Kind.SETTLEMENT,
            (server, destination) -> EventLocationResolverRegistry.Resolution.failed("first")
        );

        assertThrows(IllegalStateException.class, () ->
            EventLocationResolverRegistry.register(
                EventLocationRef.Resource.Kind.SETTLEMENT,
                (server, destination) -> EventLocationResolverRegistry.Resolution.failed("second")
            )
        );
    }

    private static EventLocationRef.Resource resource() {
        return new EventLocationRef.Resource(
            EventLocationRef.Resource.Kind.SETTLEMENT,
            "cobbleventure:settlement/starter_town",
            "town_square"
        );
    }
}
