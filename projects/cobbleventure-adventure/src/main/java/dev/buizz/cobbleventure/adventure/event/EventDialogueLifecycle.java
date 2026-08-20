package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerPlayer;

/** Optional cross-module observation of authored dialogue visibility. */
public final class EventDialogueLifecycle {
    @FunctionalInterface
    public interface Listener {
        void onStateChanged(ServerPlayer player, EventSessionKey sessionKey, boolean open);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
        new CopyOnWriteArrayList<>();

    private EventDialogueLifecycle() {}

    public static void register(Listener listener) {
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    static void opened(ServerPlayer player, EventSessionKey sessionKey) {
        notifyListeners(player, sessionKey, true);
    }

    static void closed(ServerPlayer player, EventSessionKey sessionKey) {
        notifyListeners(player, sessionKey, false);
    }

    private static void notifyListeners(
        ServerPlayer player, EventSessionKey sessionKey, boolean open
    ) {
        for (Listener listener : LISTENERS) {
            listener.onStateChanged(player, sessionKey, open);
        }
    }
}
