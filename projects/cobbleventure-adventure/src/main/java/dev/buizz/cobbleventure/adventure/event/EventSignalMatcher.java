package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;

/** Pure identity matching for callback-driven CVES triggers. */
final class EventSignalMatcher {
    private static final Set<String> SUPPORTED = Set.of(
        "flag_changed", "item_used", "battle_finished"
    );

    private EventSignalMatcher() {}

    static boolean matches(
        String eventTrigger, String eventTarget,
        String signalTrigger, String signalTarget
    ) {
        if (!SUPPORTED.contains(signalTrigger)) {
            throw new EventRuntimeException(
                "지원하지 않는 server signal trigger입니다: " + signalTrigger
            );
        }
        return eventTrigger.equals(signalTrigger) && eventTarget.equals(signalTarget);
    }
}
