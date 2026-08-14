package dev.buizz.cobbleventure.bootstrap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A bounded, read-optimized cache for deterministic world-generation values.
 * Reads never update eviction metadata; a full young generation replaces the
 * previous old generation in one operation.
 */
final class GenerationalCache<K, V> {
    private final int generationSize;
    private volatile ConcurrentHashMap<K, V> young = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<K, V> old = new ConcurrentHashMap<>();

    GenerationalCache(int maximumSize) {
        if (maximumSize < 2) {
            throw new IllegalArgumentException("maximumSize must be at least 2");
        }
        this.generationSize = Math.max(1, maximumSize / 2);
    }

    V getIfPresent(K key) {
        V value = young.get(key);
        return value != null ? value : old.get(key);
    }

    V getOrCompute(K key, Supplier<V> computer) {
        V cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        V computed = computer.get();
        ConcurrentHashMap<K, V> target = young;
        V existing = target.putIfAbsent(key, computed);
        rotateIfFull(target);
        return existing != null ? existing : computed;
    }

    private void rotateIfFull(ConcurrentHashMap<K, V> observedYoung) {
        if (observedYoung.size() < generationSize) {
            return;
        }
        synchronized (this) {
            if (young == observedYoung && observedYoung.size() >= generationSize) {
                old = observedYoung;
                young = new ConcurrentHashMap<>();
            }
        }
    }
}
