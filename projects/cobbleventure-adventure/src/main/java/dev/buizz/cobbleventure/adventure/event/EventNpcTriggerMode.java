package dev.buizz.cobbleventure.adventure.event;

import java.util.Set;

/** Keeps forced encounters in control until their authored page conditions stop matching. */
final class EventNpcTriggerMode {
    static final String PROXIMITY_TAG = "cves_trigger/proximity";

    private EventNpcTriggerMode() {}

    static boolean acceptsInteraction(
        Set<String> entityTags, EventScript script, EventExpressionEnvironment environment
    ) {
        if (!entityTags.contains(PROXIMITY_TAG)) return true;
        // Proximity is the initial entry mode, not a permanent ban on talking.
        // Evaluate this player's script state; never remove a shared NPC tag on victory.
        for (EventScript.Event event : script.events()) {
            String trigger = event.trigger().name();
            if ((trigger.equals("proximity_enter") || trigger.equals("proximity_exit"))
                && EventInterpreter.selectPage(event, environment).isPresent()) {
                return false;
            }
        }
        return EventNpcInteractionContract.uniqueInteractEvent(script)
            .flatMap(event -> EventInterpreter.selectPage(event, environment)).isPresent();
    }

    static boolean acceptsProximity(Set<String> entityTags) {
        return entityTags.contains(PROXIMITY_TAG);
    }
}
