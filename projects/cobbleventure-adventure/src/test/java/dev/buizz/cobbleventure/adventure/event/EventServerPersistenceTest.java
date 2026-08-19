package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventServerPersistenceTest {
    private static final ResourceLocation RESOURCE_ID = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure", "test/item_reward"
    );

    @Test
    void repositoryRequiresResourcePathAndScriptIdToMatch() {
        EventScriptRepository repository = new EventScriptRepository();
        JsonElement document = JsonParser.parseString(EventScriptRuntimeTest.IR);

        repository.replace(Map.of(RESOURCE_ID, document));

        assertTrue(repository.find(
            "cobbleventure:event_script/test/item_reward"
        ).isPresent());
        assertEquals(1, repository.scripts().size());

        JsonObject mismatch = document.deepCopy().getAsJsonObject();
        mismatch.addProperty("script_id", "cobbleventure:event_script/test/other");
        EventScriptFormatException error = assertThrows(
            EventScriptFormatException.class,
            () -> repository.replace(Map.of(RESOURCE_ID, mismatch))
        );
        assertTrue(error.getMessage().contains("리소스 경로"));
        assertEquals(1, repository.scripts().size(), "실패한 reload는 기존 snapshot을 유지해야 합니다.");
    }

    @Test
    void savedDataRoundTripPreservesWaitingSessionAndCallbackJournal() {
        EventScript script = EventScriptLoader.parse(EventScriptRuntimeTest.IR);
        EventSession session = EventScriptRuntimeTest.session(script);
        session.start();
        EventExecution.dispatch(
            session,
            script.events().getFirst().instruction(0),
            context -> new EventCommandAdapter.Waiting("persisted-token", 50_000L)
        );
        SavedEventSessionStore original = new SavedEventSessionStore();
        original.putIfAbsent(session);

        CompoundTag encoded = original.save(new CompoundTag(), null);
        SavedEventSessionStore restored = SavedEventSessionStore.load(encoded, null);
        EventSession loaded = restored.find(session.key()).orElseThrow();

        assertEquals(EventSession.Status.WAITING, loaded.status());
        assertEquals("persisted-token", loaded.awaiting().token());
        assertEquals(1, loaded.awaiting().resumeAddress());
        JsonObject result = new JsonObject();
        result.addProperty("granted_count", 1);
        assertEquals(
            EventSession.CallbackResult.RESUMED,
            loaded.completeAwait(
                "persisted-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, result
                )
            )
        );
        restored.save(loaded);

        EventSession reloaded = SavedEventSessionStore.load(
            restored.save(new CompoundTag(), null), null
        ).find(session.key()).orElseThrow();
        assertEquals(EventSession.Status.RUNNING, reloaded.status());
        assertEquals(1, reloaded.programCounter());
        assertTrue(reloaded.hasCompletedOperation(
            "cobbleventure:event_script/test/item_reward/reward/give_item"
        ));
        assertEquals(
            1,
            reloaded.completedOperationResult(
                "cobbleventure:event_script/test/item_reward/reward/give_item"
            ).orElseThrow().getAsJsonObject().get("granted_count").getAsInt()
        );
        assertEquals(
            EventSession.CallbackResult.DUPLICATE,
            reloaded.completeAwait(
                "persisted-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, result
                )
            )
        );
    }

    @Test
    void unsupportedSavedDataVersionIsSafelyIgnored() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 99);

        SavedEventSessionStore restored = SavedEventSessionStore.load(tag, null);

        assertTrue(restored.sessions().isEmpty());
        assertFalse(restored.isDirty());
    }
}
