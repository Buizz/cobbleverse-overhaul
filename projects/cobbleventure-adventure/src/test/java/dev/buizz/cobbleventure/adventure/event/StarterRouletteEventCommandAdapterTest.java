package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StarterRouletteEventCommandAdapterTest {
    private static final UUID PLAYER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/starter";
    private static final String DIGEST = "d".repeat(64);

    @Test
    void rouletteSessionCommandTargetsTheGatewayPlayer() {
        assertEquals(
            "cobbleventure_starter_roulette_session roulette-token",
            EventStarterRouletteBridge.starterRouletteSessionCommand("roulette-token")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> EventStarterRouletteBridge.starterRouletteSessionCommand(" ")
        );
    }

    @Test
    void rouletteWaitsAndStoresTypedPokemonSelectionOnCallback() {
        EventScript script = script();
        EventSessionKey key = key(1);
        EventSession session = EventSession.create(key, script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        AtomicReference<EventStarterRouletteGateway.RouletteRequest> opened =
            new AtomicReference<>();
        StarterRouletteEventCommandAdapter adapter = adapter(opened);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        assertEquals(key, opened.get().sessionKey());
        assertEquals("first/starter_roulette", opened.get().instructionId());
        assertEquals("roulette-token", session.awaiting().token());

        JsonObject selected = new JsonObject();
        selected.addProperty("species_id", "cobblemon:bulbasaur");
        selected.add("form", JsonNull.INSTANCE);
        selected.addProperty("level", 5);
        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID,
                key,
                "roulette-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, selected
                ),
                script,
                environment(),
                adapter,
                store,
                10
            );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(EventInterpreter.RunResult.COMPLETED, outcome.runResult());
        assertEquals(
            "cobblemon:bulbasaur",
            session.locals().get("starter").getAsJsonObject()
                .get("species_id").getAsString()
        );
        assertEquals(5, session.locals().get("starter").getAsJsonObject()
            .get("level").getAsInt());
        assertEquals(EventSession.Status.COMPLETED, session.status());
    }

    @Test
    void cancellationTerminatesWithoutRunningSuccessDialogueAndAllowsRestart() {
        EventScript script = script();
        EventSessionKey key = key(1);
        EventSession session = EventSession.create(key, script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        EventInterpreter.run(script, session, environment(), adapter(new AtomicReference<>()), store, 10);

        assertEquals(
            EventAwaitCompletionService.Status.RESUMED,
            EventAwaitCompletionService.terminateWithoutResume(
                PLAYER_ID,
                key,
                "roulette-token",
                EventSession.CompletionKind.CANCELLED,
                script,
                store
            )
        );
        assertEquals(EventSession.Status.CANCELLED, session.status());
        assertEquals(0, session.programCounter());
        assertEquals(
            EventAwaitCompletionService.Status.DUPLICATE,
            EventAwaitCompletionService.terminateWithoutResume(
                PLAYER_ID,
                key,
                "roulette-token",
                EventSession.CompletionKind.CANCELLED,
                script,
                store
            )
        );

        EventSession restarted = EventInterpreter.startSession(
            script, 0, key, environment(), store
        ).orElseThrow();
        assertSame(session, restarted);
        assertEquals(EventSession.Status.RUNNING, restarted.status());
    }

    @Test
    void locatorRejectsAnAmbiguousPlayerToken() {
        EventScript script = script();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        for (int npc = 1; npc <= 2; npc++) {
            EventSession session = EventSession.create(key(npc), script, 0, 0);
            session.start();
            session.beginAwait(
                "starter_roulette", "shared-token", null, 1, "starter", 0
            );
            store.putIfAbsent(session);
        }

        assertThrows(EventRuntimeException.class, () ->
            EventAwaitSessionLocator.find(store, PLAYER_ID, "shared-token")
        );
        assertEquals(
            Optional.empty(), EventAwaitSessionLocator.find(store, PLAYER_ID, "other-token")
        );
    }

    @Test
    void reinteractionResetsOnlyStarterRouletteAwait() {
        EventScript script = script();
        EventSessionKey key = key(1);
        EventSession session = EventSession.create(key, script, 0, 0);
        session.start();
        session.beginAwait(
            "starter_roulette", "disconnected-token", null, 1, "starter", 0
        );
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);

        assertEquals(true, EventRecoverableAwait.resetStarterRoulette(store, key));
        assertEquals(EventSession.Status.CANCELLED, session.status());
        EventSession restarted = EventInterpreter.startSession(
            script, 0, key, environment(), store
        ).orElseThrow();
        assertEquals(EventSession.Status.RUNNING, restarted.status());
        assertEquals(0, restarted.programCounter());
    }

    private static StarterRouletteEventCommandAdapter adapter(
        AtomicReference<EventStarterRouletteGateway.RouletteRequest> opened
    ) {
        return new StarterRouletteEventCommandAdapter(
            request -> {
                opened.set(request);
                return new EventStarterRouletteGateway.OpenResult("roulette-token", 0);
            },
            context -> new EventCommandAdapter.Completed(null)
        );
    }

    private static EventScript script() {
        JsonObject command = new JsonObject();
        command.addProperty("command", "starter_roulette");
        command.add("arguments", new JsonArray());
        command.add("properties", new JsonArray());
        command.addProperty("await", true);
        command.addProperty("await_explicit", true);
        command.addProperty("result", "starter");
        command.addProperty("operation_id", SCRIPT_ID + "/first/starter_roulette");
        command.addProperty("next", 1);
        command.addProperty("resume", 1);
        return new EventScript(
            1,
            SCRIPT_ID,
            DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(
                        0, "first/starter_roulette", "command", command
                    ),
                    new EventScript.Instruction(1, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static EventSessionKey key(int npcSuffix) {
        return new EventSessionKey(
            PLAYER_ID,
            UUID.fromString("20000000-0000-0000-0000-" + String.format("%012d", npcSuffix)),
            SCRIPT_ID,
            "interact"
        );
    }

    private static EventExpressionEnvironment environment() {
        return new EventExpressionEnvironment() {
            @Override
            public Optional<JsonElement> resolveName(String name) {
                return Optional.empty();
            }

            @Override
            public JsonElement call(String function, List<Argument> arguments) {
                return JsonNull.INSTANCE;
            }
        };
    }
}
