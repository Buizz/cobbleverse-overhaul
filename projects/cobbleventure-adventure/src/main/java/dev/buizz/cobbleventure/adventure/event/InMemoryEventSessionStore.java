package dev.buizz.cobbleventure.adventure.event;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe store used by tests and as the reference behavior for SavedData. */
public final class InMemoryEventSessionStore implements EventSessionStore {
    private final ConcurrentMap<EventSessionKey, EventSession> sessions =
        new ConcurrentHashMap<>();

    @Override
    public Optional<EventSession> find(EventSessionKey key) {
        return Optional.ofNullable(sessions.get(key));
    }

    @Override
    public EventSession putIfAbsent(EventSession session) {
        EventSession existing = sessions.putIfAbsent(session.key(), session);
        return existing == null ? session : existing;
    }

    @Override
    public void save(EventSession session) {
        sessions.put(session.key(), session);
    }

    @Override
    public boolean remove(EventSessionKey key) {
        return sessions.remove(key) != null;
    }

    @Override
    public Collection<EventSession> sessions() {
        return List.copyOf(sessions.values());
    }
}
