package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEntryQueueTest {
    @Test
    void matchesTwoRequestsFromTheSameEntranceInFifoOrder() {
        DungeonEntryQueue queue = new DungeonEntryQueue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(queue.enqueue(first, "entrance:a", 10L, 3010L));
        assertEquals(List.of(), queue.poll("entrance:a", 2));
        assertTrue(queue.enqueue(second, "entrance:a", 20L, 3020L));

        assertEquals(
            List.of(first, second),
            queue.poll("entrance:a", 2).stream()
                .map(DungeonEntryQueue.Request::playerId).toList()
        );
        assertEquals(0, queue.size("entrance:a"));
    }

    @Test
    void keepsEntrancePoolsSeparateAndRejectsDuplicatePlayerRequests() {
        DungeonEntryQueue queue = new DungeonEntryQueue();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(queue.enqueue(player, "entrance:a", 10L, 3010L));
        assertFalse(queue.enqueue(player, "entrance:b", 20L, 3020L));
        assertTrue(queue.enqueue(other, "entrance:b", 30L, 3030L));

        assertEquals(List.of(), queue.poll("entrance:a", 2));
        assertEquals(List.of(), queue.poll("entrance:b", 2));
    }

    @Test
    void cancellationRemovesTheRequestFromItsPool() {
        DungeonEntryQueue queue = new DungeonEntryQueue();
        UUID player = UUID.randomUUID();

        queue.enqueue(player, "entrance:a", 10L, 3010L);

        assertEquals(player, queue.remove(player).playerId());
        assertNull(queue.request(player));
        assertEquals(0, queue.size("entrance:a"));
    }
}
