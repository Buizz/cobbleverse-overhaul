package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;

/** Resolves the one runtime entry point enabled by a V5 NPC representation. */
final class EventNpcTriggerMode {
    static final String PROXIMITY_TAG = "cves_trigger/proximity";

    private EventNpcTriggerMode() {}

    static boolean acceptsInteraction(Set<String> entityTags) {
        return !entityTags.contains(PROXIMITY_TAG);
    }

    static boolean acceptsProximity(Set<String> entityTags) {
        return entityTags.contains(PROXIMITY_TAG);
    }
}
