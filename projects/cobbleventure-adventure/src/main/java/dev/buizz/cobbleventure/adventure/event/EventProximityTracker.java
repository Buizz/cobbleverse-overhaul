package dev.buizz.cobbleventure.adventure.event;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Edge detector that fires enter initially-inside and exit only after observed-inside. */
final class EventProximityTracker<K> {
    enum Transition { NONE, ENTER, EXIT }

    private final Map<K, Boolean> inside = new HashMap<>();

    Transition observe(K key, boolean currentInside) {
        Boolean previous = inside.put(key, currentInside);
        if (currentInside && !Boolean.TRUE.equals(previous)) return Transition.ENTER;
        if (!currentInside && Boolean.TRUE.equals(previous)) return Transition.EXIT;
        return Transition.NONE;
    }

    void retainAll(Set<K> observed) {
        inside.keySet().retainAll(observed);
    }

    void retainAll(Set<K> observed, Predicate<K> preserveWhileSuspended) {
        inside.keySet().removeIf(key ->
            !observed.contains(key) && !preserveWhileSuspended.test(key)
        );
    }

    int size() {
        return inside.size();
    }
}
