package dev.buizz.cobbleventure.adventure.quest;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

final class QuestHookJournalTest {
    private static final QuestDefinition.EventHook HOOK = new QuestDefinition.EventHook(
        "test:event_script/quest/intro", "test:npc/oak");

    @Test void fifoAndIdempotencySurviveReload() {
        QuestHookJournal journal = new QuestHookJournal();
        assertFalse(journal.enqueue("absent", null));
        assertTrue(journal.enqueue("accept", HOOK));
        assertTrue(journal.enqueue("objective", HOOK));
        assertTrue(journal.enqueue("complete", HOOK));
        journal.update("accept", QuestHookJournal.Status.COMPLETED, "npc-uuid", "done");
        journal.update("objective", QuestHookJournal.Status.RUNNING, "npc-uuid", "await");
        QuestHookJournal restored = QuestHookJournal.fromJson(JsonParser.parseString(journal.toJson().toString()).getAsJsonObject());
        assertEquals(journal.entries(), restored.entries());
        assertEquals(List.of("accept", "objective", "complete"), restored.entries().stream().map(QuestHookJournal.Entry::key).toList());
        assertFalse(restored.enqueue("accept", HOOK));
        assertFalse(restored.enqueue("objective", HOOK));
        assertFalse(restored.enqueue("complete", HOOK));
    }

    @Test void failedOrSkippedHooksNeverAutoRetry() {
        for (var status : List.of(QuestHookJournal.Status.FAILED, QuestHookJournal.Status.SKIPPED)) {
            QuestHookJournal journal = new QuestHookJournal();
            journal.enqueue("hook", HOOK);
            journal.update("hook", status, "", "failure");
            assertFalse(QuestHookJournal.fromJson(journal.toJson()).enqueue("hook", HOOK));
        }
    }

    @Test void playersAndHookSnapshotsAreIndependent() {
        QuestHookJournal alice = new QuestHookJournal(), bob = new QuestHookJournal();
        alice.enqueue("accept", HOOK); bob.enqueue("accept", HOOK);
        alice.update("accept", QuestHookJournal.Status.COMPLETED, "npc", "");
        assertEquals(QuestHookJournal.Status.PENDING, bob.entries().getFirst().status());
        assertFalse(bob.enqueue("accept", new QuestDefinition.EventHook("test:event_script/changed", "test:npc/other")));
        assertEquals(HOOK, bob.entries().getFirst().hook());
    }

    @Test void validatesHookIds() {
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition.EventHook("test:event_script/../bad", "test:npc/oak"));
        assertThrows(IllegalArgumentException.class, () -> new QuestDefinition.EventHook(HOOK.scriptId(), "test:other/oak"));
    }
}
