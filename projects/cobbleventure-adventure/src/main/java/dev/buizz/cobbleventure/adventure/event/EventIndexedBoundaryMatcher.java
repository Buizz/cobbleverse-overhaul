package dev.buizz.cobbleventure.adventure.event;

/** Pure routing from indexed world snapshots to CVES boundary edge semantics. */
final class EventIndexedBoundaryMatcher {
    private EventIndexedBoundaryMatcher() {}

    static boolean inside(
        String trigger, String target, EventBoundaryProviderRegistry.Snapshot snapshot
    ) {
        return switch (trigger) {
            case "region_enter", "region_exit" -> snapshot.regions().contains(target);
            case "anchor_step" -> snapshot.anchors().contains(target);
            case "building_enter", "building_exit" ->
                snapshot.buildings().contains(target);
            case "dimension_enter", "dimension_exit" ->
                snapshot.dimensions().contains(target);
            default -> throw new EventRuntimeException(
                "지원하지 않는 indexed boundary trigger입니다: " + trigger
            );
        };
    }

    static boolean matches(
        String trigger, EventProximityTracker.Transition transition
    ) {
        return switch (trigger) {
            case "region_enter", "anchor_step", "building_enter", "dimension_enter" ->
                transition == EventProximityTracker.Transition.ENTER;
            case "region_exit", "building_exit", "dimension_exit" ->
                transition == EventProximityTracker.Transition.EXIT;
            default -> false;
        };
    }
}
