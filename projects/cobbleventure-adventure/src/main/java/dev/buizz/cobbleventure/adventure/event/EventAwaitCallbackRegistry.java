package dev.buizz.cobbleventure.adventure.event;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Retains the exact session key for non-persistent cross-mod UI callbacks. */
final class EventAwaitCallbackRegistry {
    private static final Map<String, EventSessionKey> PENDING = new ConcurrentHashMap<>();

    private EventAwaitCallbackRegistry() {}

    static void register(String token, EventSessionKey key) {
        EventSessionKey previous = PENDING.putIfAbsent(token, key);
        if (previous != null && !previous.equals(key)) {
            throw new EventRuntimeException("중복 await callback token입니다.");
        }
    }

    static Optional<EventSessionKey> find(
        EventSessionStore store, UUID playerId, String token
    ) {
        EventSessionKey registered = PENDING.get(token);
        if (registered != null && registered.playerId().equals(playerId)) {
            return Optional.of(registered);
        }
        return EventAwaitSessionLocator.find(store, playerId, token);
    }

    static void forget(String token) {
        PENDING.remove(token);
    }
}
