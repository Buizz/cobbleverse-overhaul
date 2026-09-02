package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.adventure.quest.QuestDefinition;
import dev.buizz.cobbleventure.adventure.quest.QuestHookJournal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class QuestEventHooksTest {
    private static final String ID = "test:event_script/quest/intro";
    private static EventSession session(UUID player, String trigger) {
        EventScript script = new EventScript(1, ID, "a".repeat(64), List.of(new EventScript.Event(
            0, new EventScript.Trigger("quest", new JsonObject()), List.of(new EventScript.Page(0, null, 0)),
            List.of(new EventScript.Instruction(0, "end", "page_end", new JsonObject())))));
        return EventSession.create(new EventSessionKey(player, UUID.randomUUID(), ID, trigger), script, 0, 0);
    }

    @Test void busyDetectionIsPerPlayerAndIncludesReadySessions() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        EventSession session = session(alice, "interact"); store.putIfAbsent(session);
        assertTrue(QuestEventHooks.hasActiveSession(store, alice));
        assertFalse(QuestEventHooks.hasActiveSession(store, bob));
        session.start(); store.save(session);
        assertTrue(QuestEventHooks.hasActiveSession(store, alice));
        session.terminate(EventSession.CompletionKind.COMPLETED); store.save(session);
        assertFalse(QuestEventHooks.hasActiveSession(store, alice));
    }

    @Test void recoveryFindsEvenTerminalSessionWithoutConfusingPlayersOrTransitions() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        QuestHookJournal journal = new QuestHookJournal();
        journal.enqueue("quest|accept", new QuestDefinition.EventHook(ID, "test:npc/oak"));
        var entry = journal.entries().getFirst();
        EventSession session = session(alice, "quest_hook:quest|accept"); store.putIfAbsent(session);
        assertNotNull(QuestEventHooks.findExisting(store, alice, entry));
        assertNull(QuestEventHooks.findExisting(store, bob, entry));
        session.start(); session.terminate(EventSession.CompletionKind.COMPLETED); store.save(session);
        assertEquals(EventSession.Status.COMPLETED, QuestEventHooks.findExisting(store, alice, entry).status());
        journal.enqueue("quest|complete", entry.hook());
        assertNull(QuestEventHooks.findExisting(store, alice, journal.entries().getLast()));
    }

    @Test void waitsForAwaitAndFailsClosedOnLostCallbackTimeout() {
        UUID player = UUID.randomUUID();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        EventSession session = session(player, "quest_hook:accept");
        session.start(); session.beginAwait("dialogue", "token", null, 0, null, 100);
        store.putIfAbsent(session);
        assertTrue(QuestEventHooks.hasActiveSession(store, player));
        assertFalse(QuestEventHooks.expireOverdue(session, 99));
        assertTrue(QuestEventHooks.expireOverdue(session, 100));
        store.save(session);
        assertEquals(EventSession.Status.FAILED, session.status());
        assertFalse(QuestEventHooks.hasActiveSession(store, player));
        assertFalse(QuestEventHooks.expireOverdue(session, 101));
    }
}
