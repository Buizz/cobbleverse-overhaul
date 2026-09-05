package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerPlayer;

/** Optional cross-module observation of authored dialogue visibility. */
public final class EventDialogueLifecycle {
    @FunctionalInterface
    public interface Listener {
        void onStateChanged(ServerPlayer player, EventSessionKey sessionKey, boolean open);
    }

    @FunctionalInterface
    public interface CompletionListener {
        void onCompleted(ServerPlayer player, EventSessionKey sessionKey);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
        new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<CompletionListener> COMPLETION_LISTENERS =
        new CopyOnWriteArrayList<>();
    private static final Set<String> DIALOGUE_AWAIT_KINDS = Set.of(
        "say", "narrate", "choice", "number_input"
    );

    private EventDialogueLifecycle() {}

    public static void register(Listener listener) {
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Runs after a dialogue response has resumed or terminated its server session. */
    public static void registerCompletion(CompletionListener listener) {
        COMPLETION_LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Reads the persisted server session instead of trusting the current client screen.
     * This remains authoritative while completion packets are delayed by server lag.
     */
    public static boolean isActive(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return isActive(
            SavedEventSessionStore.get(player.getServer()), player.getUUID()
        );
    }

    static boolean isActive(EventSessionStore store, UUID playerId) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(playerId, "playerId");
        for (EventSession session : store.sessions()) {
            EventSession.AwaitState awaiting = session.awaiting();
            if (session.key().playerId().equals(playerId)
                && session.status() == EventSession.Status.WAITING
                && awaiting != null
                && DIALOGUE_AWAIT_KINDS.contains(awaiting.kind())) {
                return true;
            }
        }
        return false;
    }

    static void opened(ServerPlayer player, EventSessionKey sessionKey) {
        notifyListeners(player, sessionKey, true);
    }

    static void closed(ServerPlayer player, EventSessionKey sessionKey) {
        notifyListeners(player, sessionKey, false);
    }

    static void completed(ServerPlayer player, EventSessionKey sessionKey) {
        for (CompletionListener listener : COMPLETION_LISTENERS) {
            listener.onCompleted(player, sessionKey);
        }
    }

    private static void notifyListeners(
        ServerPlayer player, EventSessionKey sessionKey, boolean open
    ) {
        for (Listener listener : LISTENERS) {
            listener.onStateChanged(player, sessionKey, open);
        }
    }
}
