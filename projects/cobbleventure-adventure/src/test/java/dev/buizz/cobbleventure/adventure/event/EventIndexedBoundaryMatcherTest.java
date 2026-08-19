package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventIndexedBoundaryMatcherTest {
    private static final EventBoundaryProviderRegistry.Snapshot SNAPSHOT =
        new EventBoundaryProviderRegistry.Snapshot(
            Set.of("test:region/start"), Set.of("test:anchor/plate"),
            Set.of("test:building/lab"), Set.of("test:world")
        );

    @Test
    void regionAndAnchorReadOnlyTheirOwnIndex() {
        assertTrue(EventIndexedBoundaryMatcher.inside(
            "region_enter", "test:region/start", SNAPSHOT
        ));
        assertFalse(EventIndexedBoundaryMatcher.inside(
            "region_enter", "test:anchor/plate", SNAPSHOT
        ));
        assertTrue(EventIndexedBoundaryMatcher.inside(
            "anchor_step", "test:anchor/plate", SNAPSHOT
        ));
        assertTrue(EventIndexedBoundaryMatcher.inside(
            "building_enter", "test:building/lab", SNAPSHOT
        ));
        assertTrue(EventIndexedBoundaryMatcher.inside(
            "dimension_exit", "test:world", SNAPSHOT
        ));
        assertFalse(EventIndexedBoundaryMatcher.inside(
            "building_enter", "test:world", SNAPSHOT
        ));
        assertThrows(EventRuntimeException.class, () ->
            EventIndexedBoundaryMatcher.inside("unknown", "test:any", SNAPSHOT)
        );
    }

    @Test
    void enterAndStepUseEnterEdgeWhileRegionExitUsesExitEdge() {
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "region_enter", EventProximityTracker.Transition.ENTER
        ));
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "anchor_step", EventProximityTracker.Transition.ENTER
        ));
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "region_exit", EventProximityTracker.Transition.EXIT
        ));
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "building_enter", EventProximityTracker.Transition.ENTER
        ));
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "building_exit", EventProximityTracker.Transition.EXIT
        ));
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "dimension_enter", EventProximityTracker.Transition.ENTER
        ));
        assertTrue(EventIndexedBoundaryMatcher.matches(
            "dimension_exit", EventProximityTracker.Transition.EXIT
        ));
        assertFalse(EventIndexedBoundaryMatcher.matches(
            "region_exit", EventProximityTracker.Transition.ENTER
        ));
    }
}
