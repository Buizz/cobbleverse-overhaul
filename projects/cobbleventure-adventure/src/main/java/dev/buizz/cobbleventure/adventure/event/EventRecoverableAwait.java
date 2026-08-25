package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;

/** Resets UI awaits whose client-side session is intentionally not persistent. */
public final class EventRecoverableAwait {
    private EventRecoverableAwait() {}

    /** Repairs sessions created by the old number-input cancellation callback. */
    public static boolean resetLegacyCancelledNumberInput(
        EventSessionStore store, EventSessionKey key
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.RUNNING
            || session.awaiting() != null
            || session.locals().values().stream().noneMatch(value ->
                value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString()
                    && "client_cancelled".equals(value.getAsString()))) {
            return false;
        }
        session.terminate(EventSession.CompletionKind.CANCELLED);
        store.save(session);
        return true;
    }

    public static boolean resetStarterRoulette(
        EventSessionStore store, EventSessionKey key
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("starter_roulette")) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetMapSelection(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("map_selection")) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetGiveItem(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("give_item")) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetGiveLoot(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("give_loot")) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetChoice(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("choice")) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetBattle(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("battle")) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetTeleport(EventSessionStore store, EventSessionKey key) {
        return resetMovementKind(store, key, "teleport");
    }

    public static boolean resetMovement(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !(session.awaiting().kind().equals("move")
                || session.awaiting().kind().equals("teleport")
                || session.awaiting().kind().equals("enter_space"))) {
            return false;
        }
        EventMovementBridge.cancel(key);
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetPresentation(
        EventSessionStore store, EventSessionKey key
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !(session.awaiting().kind().equals("fade")
                || session.awaiting().kind().equals("wait")
                || session.awaiting().kind().equals("sound")
                || session.awaiting().kind().equals("effect"))) {
            return false;
        }
        EventPresentationBridge.cancel(key);
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    public static boolean resetHealing(EventSessionStore store, EventSessionKey key) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals("heal_party")) {
            return false;
        }
        EventHealingBridge.cancel(key);
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }

    private static boolean resetMovementKind(
        EventSessionStore store, EventSessionKey key, String kind
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        EventSession session = store.find(key).orElse(null);
        if (session == null
            || session.status() != EventSession.Status.WAITING
            || session.awaiting() == null
            || !session.awaiting().kind().equals(kind)) {
            return false;
        }
        EventSession.CallbackResult result = session.terminateAwait(
            session.awaiting().token(), EventSession.CompletionKind.CANCELLED
        );
        if (result != EventSession.CallbackResult.RESUMED) return false;
        store.save(session);
        return true;
    }
}
