package dev.buizz.cobbleventure.adventure.event;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Tracks value changes without inventing a change on the first observation. */
final class EventValueChangeTracker<K, V> {
    private final Map<K, V> previous = new HashMap<>();

    boolean changed(K key, V value) {
        boolean known = previous.containsKey(key);
        V old = previous.put(key, value);
        return known && !java.util.Objects.equals(old, value);
    }

    void retainAll(Set<K> observed) {
        previous.keySet().retainAll(observed);
    }

    void clear() {
        previous.clear();
    }
}
