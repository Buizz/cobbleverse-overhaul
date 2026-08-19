package dev.buizz.cobbleventure.adventure.event;

import java.util.Collection;
import java.util.Optional;

/** Persistence boundary implemented by NeoForge SavedData in the server adapter. */
public interface EventSessionStore {
    Optional<EventSession> find(EventSessionKey key);

    /** Atomically keeps the existing session when the same trigger is already active. */
    EventSession putIfAbsent(EventSession session);

    void save(EventSession session);

    boolean remove(EventSessionKey key);

    Collection<EventSession> sessions();
}
