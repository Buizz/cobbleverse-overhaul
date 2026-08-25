package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative FIFO requests grouped by a dungeon match pool. */
final class DungeonEntryQueue {
    private final Map<String, ArrayDeque<Request>> pools = new HashMap<>();
    private final Map<UUID, Request> requests = new HashMap<>();

    boolean enqueue(UUID playerId, String poolKey, long queuedAt, long expiresAt) {
        if (requests.containsKey(playerId)) {
            return false;
        }
        Request request = new Request(playerId, poolKey, queuedAt, expiresAt);
        requests.put(playerId, request);
        pools.computeIfAbsent(poolKey, ignored -> new ArrayDeque<>()).addLast(request);
        return true;
    }

    List<Request> poll(String poolKey, int requiredPlayers) {
        ArrayDeque<Request> pool = pools.get(poolKey);
        if (pool == null || pool.size() < requiredPlayers) {
            return List.of();
        }
        List<Request> matched = new ArrayList<>(requiredPlayers);
        for (int index = 0; index < requiredPlayers; index++) {
            Request request = pool.removeFirst();
            requests.remove(request.playerId());
            matched.add(request);
        }
        if (pool.isEmpty()) {
            pools.remove(poolKey);
        }
        return List.copyOf(matched);
    }

    Request remove(UUID playerId) {
        Request removed = requests.remove(playerId);
        if (removed == null) {
            return null;
        }
        ArrayDeque<Request> pool = pools.get(removed.poolKey());
        if (pool != null) {
            pool.remove(removed);
            if (pool.isEmpty()) {
                pools.remove(removed.poolKey());
            }
        }
        return removed;
    }

    Request request(UUID playerId) {
        return requests.get(playerId);
    }

    int size(String poolKey) {
        ArrayDeque<Request> pool = pools.get(poolKey);
        return pool == null ? 0 : pool.size();
    }

    void clear() {
        pools.clear();
        requests.clear();
    }

    record Request(UUID playerId, String poolKey, long queuedAt, long expiresAt) {}
}
