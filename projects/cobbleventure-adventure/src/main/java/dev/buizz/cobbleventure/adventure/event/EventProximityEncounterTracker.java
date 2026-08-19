package dev.buizz.cobbleventure.adventure.event;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Ephemeral per-player/NPC state for staged proximity encounters. */
final class EventProximityEncounterTracker<K> {
    enum Decision { SKIP, FIRE, FIRE_AND_CLEAR, CLEAR }

    private final Map<K, State> states = new HashMap<>();

    Decision observe(
        K key,
        EventTriggerContract.Options options,
        boolean inside,
        EventProximityTracker.Transition transition,
        long gameTime
    ) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (options.after() == null) {
            if (!inside) {
                state.ready = true;
                if (transition == EventProximityTracker.Transition.EXIT) {
                    boolean clear = state.warningActive;
                    state.resetForReentry();
                    return clear ? Decision.CLEAR : Decision.SKIP;
                }
                return Decision.SKIP;
            }
            return transition == EventProximityTracker.Transition.ENTER
                && state.ready && !state.consumed
                ? Decision.FIRE : Decision.SKIP;
        }
        Long armedAt = state.armedStages.get(options.after());
        if (!inside || state.consumed || armedAt == null || armedAt >= gameTime) {
            return Decision.SKIP;
        }
        return state.warningActive ? Decision.FIRE_AND_CLEAR : Decision.FIRE;
    }

    void markFired(K key, EventTriggerContract.Options options, long gameTime) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (options.after() == null) {
            if (options.stage() != null) {
                state.armedStages.put(options.stage(), gameTime);
            }
            state.warningActive = true;
        } else {
            state.consumed = true;
            state.warningActive = false;
        }
    }

    void retainAll(Set<K> observed) {
        states.keySet().retainAll(observed);
    }

    private static final class State {
        private final Map<String, Long> armedStages = new HashMap<>();
        private boolean ready;
        private boolean consumed;
        private boolean warningActive;

        private void resetForReentry() {
            armedStages.clear();
            consumed = false;
            warningActive = false;
        }
    }
}
