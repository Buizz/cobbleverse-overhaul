package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MapSelectionEventCommandAdapterTest {
    private static final UUID PLAYER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000004"
    );
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/map_selection";
    private static final String DIGEST = "e".repeat(64);

    @Test
    void selectedSettlementBecomesTypedLocationForFollowingTeleport() {
        EventScript script = script();
        EventSession session = EventSession.create(key(), script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        AtomicReference<EventMapSelectionGateway.SelectionRequest> selection =
            new AtomicReference<>();
        AtomicReference<EventMovementGateway.MovementRequest> movement =
            new AtomicReference<>();
        EventCommandAdapter adapter = adapter(selection, movement);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        assertEquals("travel/select", selection.get().instructionId());
        assertEquals("map-token", session.awaiting().token());

        EventLocationRef.Resource selected = new EventLocationRef.Resource(
            EventLocationRef.Resource.Kind.SETTLEMENT,
            "cobbleventure:settlement/pallet_town",
            null
        );
        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID, key(), "map-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.COMPLETED, selected.toJson()
                ),
                script, environment(), adapter, store, 10
            );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertEquals(EventInterpreter.RunResult.WAITING, outcome.runResult());
        assertEquals(selected, movement.get().destination());
        assertEquals(EventMovementGateway.Subject.PLAYER, movement.get().subject());
        assertEquals("movement-token", session.awaiting().token());
        assertEquals(
            "cobbleventure:settlement/pallet_town",
            session.locals().get("destination").getAsJsonObject()
                .get("resource_id").getAsString()
        );
    }

    @Test
    void mapSelectionCancellationCanBeResetForReinteraction() {
        EventScript script = script();
        EventSession session = EventSession.create(key(), script, 0, 0);
        session.start();
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        EventInterpreter.run(
            script, session, environment(),
            adapter(new AtomicReference<>(), new AtomicReference<>()), store, 10
        );

        assertEquals(true, EventRecoverableAwait.resetMapSelection(store, key()));
        assertEquals(EventSession.Status.CANCELLED, session.status());
        EventSession restarted = EventInterpreter.startSession(
            script, 0, key(), environment(), store
        ).orElseThrow();
        assertSame(session, restarted);
        assertEquals(EventSession.Status.RUNNING, restarted.status());
        assertEquals(0, restarted.programCounter());
    }

    private static EventCommandAdapter adapter(
        AtomicReference<EventMapSelectionGateway.SelectionRequest> selection,
        AtomicReference<EventMovementGateway.MovementRequest> movement
    ) {
        EventCommandAdapter teleport = new TeleportEventCommandAdapter(
            request -> {
                movement.set(request);
                return new EventMovementGateway.OpenResult("movement-token", 0);
            },
            environment(),
            context -> new EventCommandAdapter.Completed(null)
        );
        return new MapSelectionEventCommandAdapter(
            request -> {
                selection.set(request);
                return new EventMapSelectionGateway.OpenResult("map-token", 0);
            },
            teleport
        );
    }

    private static EventScript script() {
        JsonObject map = command("map_selection", "destination", 1, 1);
        JsonObject teleport = command("teleport", "movement", 2, 2);
        JsonArray arguments = new JsonArray();
        arguments.add(argument(name("player")));
        arguments.add(argument(name("destination")));
        teleport.add("arguments", arguments);
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(0, "travel/select", "command", map),
                    new EventScript.Instruction(1, "travel/go", "command", teleport),
                    new EventScript.Instruction(2, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static JsonObject command(
        String name, String result, int next, int resume
    ) {
        JsonObject command = new JsonObject();
        command.addProperty("command", name);
        command.add("arguments", new JsonArray());
        command.add("properties", new JsonArray());
        command.addProperty("await", true);
        command.addProperty("await_explicit", true);
        command.addProperty("result", result);
        command.addProperty("operation_id", SCRIPT_ID + "/" + name);
        command.addProperty("next", next);
        command.addProperty("resume", resume);
        return command;
    }

    private static JsonObject argument(JsonObject value) {
        JsonObject argument = new JsonObject();
        argument.add("name", JsonNull.INSTANCE);
        argument.add("value", value);
        return argument;
    }

    private static JsonObject name(String value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "name");
        expression.addProperty("name", value);
        return expression;
    }

    private static EventSessionKey key() {
        return new EventSessionKey(
            PLAYER_ID,
            UUID.fromString("20000000-0000-0000-0000-000000000004"),
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
