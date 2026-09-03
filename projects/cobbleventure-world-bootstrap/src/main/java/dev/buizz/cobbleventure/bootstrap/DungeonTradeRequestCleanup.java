package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import java.util.function.Consumer;

/** Keeps request cleanup testable without starting Minecraft or Cobblemon. */
final class DungeonTradeRequestCleanup {
    private DungeonTradeRequestCleanup() {}

    static <T> void cancelInboundRequests(List<T> requests, Consumer<T> cancel) {
        // Cobblemon returns null when the player has no inbound request entry.
        if (requests == null) return;
        // Cancellation mutates Cobblemon's backing list; iterate a snapshot.
        for (T request : List.copyOf(requests)) {
            cancel.accept(request);
        }
    }
}
