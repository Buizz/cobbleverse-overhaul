package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class DungeonTradeRequestCleanupTest {
    @Test
    void acceptsMissingInboundRequestList() {
        DungeonTradeRequestCleanup.cancelInboundRequests(null, request -> fail(
            "A player with no inbound request entry has nothing to cancel"
        ));
    }

    @Test
    void acceptsEmptyInboundRequestList() {
        DungeonTradeRequestCleanup.cancelInboundRequests(List.of(), request -> fail(
            "An empty request list has nothing to cancel"
        ));
    }

    @Test
    void cancelsEveryPendingRequestOnce() {
        List<String> cancelled = new ArrayList<>();

        DungeonTradeRequestCleanup.cancelInboundRequests(
            List.of("first", "second", "third"), cancelled::add
        );

        assertEquals(List.of("first", "second", "third"), cancelled);
    }

    @Test
    void cancellationCanRemoveRequestsFromTheBackingList() {
        List<String> requests = new ArrayList<>(List.of("first", "second", "third"));
        List<String> cancelled = new ArrayList<>();

        DungeonTradeRequestCleanup.cancelInboundRequests(requests, request -> {
            cancelled.add(request);
            requests.remove(request);
        });

        assertEquals(List.of("first", "second", "third"), cancelled);
        assertTrue(requests.isEmpty());
    }
}
