package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.UUID;

/** Authenticates one callback, persists its resume transition, and continues execution. */
public final class EventAwaitCompletionService {
    public enum Status {
        NOT_FOUND,
        PLAYER_MISMATCH,
        SCRIPT_MISMATCH,
        EXPIRED,
        DUPLICATE,
        STALE,
        RESUMED
    }

    public record Outcome(Status status, EventInterpreter.RunResult runResult) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if ((status == Status.RESUMED) != (runResult != null)) {
                throw new IllegalArgumentException("RESUMED 결과에만 runResult가 필요합니다.");
            }
        }
    }

    private EventAwaitCompletionService() {}

    public static Outcome completeAndRun(
        UUID authenticatedPlayerId,
        EventSessionKey key,
        String token,
        EventSession.AwaitCompletion completion,
        EventScript script,
        EventExpressionEnvironment environment,
        EventCommandAdapter adapter,
        EventSessionStore store,
        int maxSteps
    ) {
        Objects.requireNonNull(authenticatedPlayerId, "authenticatedPlayerId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(store, "store");
        EventSession session = store.find(key).orElse(null);
        if (session == null) {
            return new Outcome(Status.NOT_FOUND, null);
        }
        if (!key.playerId().equals(authenticatedPlayerId)) {
            return new Outcome(Status.PLAYER_MISMATCH, null);
        }
        if (!script.scriptId().equals(key.scriptId())) {
            return new Outcome(Status.SCRIPT_MISMATCH, null);
        }
        try {
            if (session.relocate(script, null)) store.save(session);
        } catch (EventRuntimeException error) {
            return new Outcome(Status.SCRIPT_MISMATCH, null);
        }
        EventSession.AwaitState awaiting = session.awaiting();
        if (awaiting != null && awaiting.token().equals(token)
            && awaiting.expiresAtEpochMilli() > 0
            && System.currentTimeMillis() >= awaiting.expiresAtEpochMilli()) {
            session.expireAwait(System.currentTimeMillis());
            store.save(session);
            return new Outcome(Status.EXPIRED, null);
        }
        EventSession.CallbackResult callback = session.completeAwait(token, completion);
        if (callback == EventSession.CallbackResult.DUPLICATE) {
            return new Outcome(Status.DUPLICATE, null);
        }
        if (callback == EventSession.CallbackResult.STALE) {
            return new Outcome(Status.STALE, null);
        }
        store.save(session);
        EventInterpreter.RunResult runResult = EventInterpreter.run(
            script, session, environment, adapter, store, maxSteps
        );
        return new Outcome(Status.RESUMED, runResult);
    }

    public static Status terminateWithoutResume(
        UUID authenticatedPlayerId,
        EventSessionKey key,
        String token,
        EventSession.CompletionKind kind,
        EventScript script,
        EventSessionStore store
    ) {
        Objects.requireNonNull(authenticatedPlayerId, "authenticatedPlayerId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(store, "store");
        EventSession session = store.find(key).orElse(null);
        if (session == null) return Status.NOT_FOUND;
        if (!key.playerId().equals(authenticatedPlayerId)) return Status.PLAYER_MISMATCH;
        if (!script.scriptId().equals(key.scriptId())) {
            return Status.SCRIPT_MISMATCH;
        }
        try {
            if (session.relocate(script, null)) store.save(session);
        } catch (EventRuntimeException error) {
            return Status.SCRIPT_MISMATCH;
        }
        EventSession.AwaitState awaiting = session.awaiting();
        if (awaiting != null && awaiting.token().equals(token)
            && awaiting.expiresAtEpochMilli() > 0
            && System.currentTimeMillis() >= awaiting.expiresAtEpochMilli()) {
            session.terminateAwait(token, EventSession.CompletionKind.FAILED);
            store.save(session);
            return Status.EXPIRED;
        }
        EventSession.CallbackResult result = session.terminateAwait(token, kind);
        if (result == EventSession.CallbackResult.DUPLICATE) return Status.DUPLICATE;
        if (result == EventSession.CallbackResult.STALE) return Status.STALE;
        store.save(session);
        return Status.RESUMED;
    }
}
