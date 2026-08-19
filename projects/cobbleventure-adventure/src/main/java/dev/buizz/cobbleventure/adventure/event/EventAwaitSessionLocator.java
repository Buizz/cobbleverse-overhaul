package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Finds the single persisted session owning an opaque callback token. */
public final class EventAwaitSessionLocator {
    private EventAwaitSessionLocator() {}

    public static Optional<EventSessionKey> find(
        EventSessionStore store, UUID playerId, String token
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");
        EventSessionKey match = null;
        for (EventSession session : store.sessions()) {
            EventSession.AwaitState awaiting = session.awaiting();
            if (!session.key().playerId().equals(playerId)
                || session.status() != EventSession.Status.WAITING
                || awaiting == null
                || !awaiting.token().equals(token)) {
                continue;
            }
            if (match != null && !match.equals(session.key())) {
                throw new EventRuntimeException(
                    "같은 player와 token을 기다리는 CVES 세션이 여러 개입니다."
                );
            }
            match = session.key();
        }
        return Optional.ofNullable(match);
    }
}
