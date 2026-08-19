package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TeleportEventCommandAdapterTest {
    private static final String SCRIPT_ID = "cobbleventure:event_script/test/teleport";
    private static final String OPERATION_ID = SCRIPT_ID + "/travel/leave_gate";
    private static final String DIGEST = "b".repeat(64);
    private static final UUID PLAYER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000002"
    );

    @Test
    void relativeTeleportUsesCommonAwaitAndReplayRestoresTypedResult() {
        EventScript script = script(relative(1.5, 0, -4), new JsonArray());
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<EventMovementGateway.MovementRequest> opened = new AtomicReference<>();
        TeleportEventCommandAdapter adapter = adapter(opens, opened);

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 10)
        );
        EventMovementGateway.MovementRequest request = opened.get();
        assertEquals(EventMovementGateway.Subject.PLAYER, request.subject());
        assertEquals(EventMovementGateway.Mode.TELEPORT, request.options().mode());
        assertEquals(new EventLocationRef.Relative(1.5, 0, -4), request.destination());
        assertEquals(EventMovementGateway.SafeLanding.REQUIRED, request.options().safeLanding());
        assertTrue(request.options().preloadChunks());
        assertEquals(EventMovementGateway.Fade.NONE, request.options().fade());

        EventAwaitCompletionService.completeAndRun(
            PLAYER_ID, session.key(), "movement-token",
            new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED,
                result(true, null, request.destination())
            ),
            script, environment(), adapter, store, 10
        );
        assertTrue(session.hasCompletedOperation(OPERATION_ID));
        assertTrue(session.locals().get("travel").getAsJsonObject()
            .get("arrived").getAsBoolean());
        assertEquals("", session.locals().get("travel").getAsJsonObject()
            .get("failure_reason").getAsString());

        EventSession restored = EventSession.fromJson(session.toJson());
        InMemoryEventSessionStore restoredStore = store(restored);
        EventSession restarted = EventInterpreter.startSession(
            script, 0, restored.key(), environment(), restoredStore
        ).orElseThrow();
        assertEquals(
            EventInterpreter.RunResult.COMPLETED,
            EventInterpreter.run(script, restarted, environment(), adapter, restoredStore, 10)
        );
        assertEquals(1, opens.get());
        assertTrue(restarted.locals().get("travel").getAsJsonObject()
            .get("arrived").getAsBoolean());
    }

    @Test
    void relativeNpcWalkDecodesServerAuthoritativeMovementOptions() {
        JsonArray properties = new JsonArray();
        properties.add(property("mode", name("walk")));
        properties.add(property("speed", literal(1.25)));
        properties.add(property("lock_input", literal(true)));
        properties.add(property("collision", name("stop")));
        EventScript script = script(
            "move", "npc", relative(2, 0, -1), properties
        );
        EventSession session = session(script);
        AtomicReference<EventMovementGateway.MovementRequest> opened = new AtomicReference<>();

        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), opened),
            store(session), 10
        );

        EventMovementGateway.MovementRequest request = opened.get();
        assertEquals(EventMovementGateway.Subject.NPC, request.subject());
        assertEquals(EventMovementGateway.Mode.WALK, request.options().mode());
        assertEquals(1.25, request.options().speed());
        assertTrue(request.options().lockInput());
        assertEquals(EventMovementGateway.Collision.STOP, request.options().collision());
        assertEquals(new EventLocationRef.Relative(2, 0, -1), request.destination());
        assertEquals("move", session.awaiting().kind());
    }

    @Test
    void allMovementDestinationKindsResumeSequentiallyExactlyOnce() {
        EventScript script = sequentialMovementScript();
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        List<EventMovementGateway.MovementRequest> opened = new ArrayList<>();
        TeleportEventCommandAdapter adapter = new TeleportEventCommandAdapter(
            request -> {
                opened.add(request);
                return new EventMovementGateway.OpenResult(
                    "movement-token-" + opened.size(), 0
                );
            },
            environment(),
            context -> new EventCommandAdapter.Completed(null)
        );

        assertEquals(
            EventInterpreter.RunResult.WAITING,
            EventInterpreter.run(script, session, environment(), adapter, store, 20)
        );
        for (int index = 0; index < 7; index++) {
            EventMovementGateway.MovementRequest request = opened.get(index);
            EventAwaitCompletionService.Outcome outcome =
                EventAwaitCompletionService.completeAndRun(
                    PLAYER_ID, session.key(), "movement-token-" + (index + 1),
                    new EventSession.AwaitCompletion(
                        EventSession.CompletionKind.COMPLETED,
                        result(true, null, request.destination())
                    ),
                    script, environment(), adapter, store, 20
                );
            assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        }

        assertEquals(EventSession.Status.COMPLETED, session.status());
        assertEquals(7, opened.size());
        assertEquals(EventMovementGateway.Subject.NPC, opened.get(0).subject());
        assertEquals(new EventLocationRef.Relative(2, 0, -1), opened.get(0).destination());
        assertEquals(
            new EventLocationRef.Position(
                "cobbleventure:generation_1", 600, 69, -390, 0F, 0F
            ),
            opened.get(1).destination()
        );
        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.SETTLEMENT,
                "cobbleventure:settlement/starter_town", "town_square"
            ),
            opened.get(2).destination()
        );
        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.ROUTE,
                "cobbleventure:route/route_custom_01", "middle"
            ),
            opened.get(3).destination()
        );
        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.DIMENSION,
                "cobbleventure:generation_1", "world/spawn"
            ),
            opened.get(4).destination()
        );
        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.SPACE,
                "cobbleventure:cave/mt_moon", "west_gallery"
            ),
            opened.get(5).destination()
        );
        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.ANCHOR,
                "cobbleventure:event_anchor/world_spawn", null
            ),
            opened.get(6).destination()
        );
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/npc_relative"));
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/player_absolute"));
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/starter_town"));
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/route_middle"));
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/world_spawn"));
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/mt_moon"));
        assertTrue(session.hasCompletedOperation(SCRIPT_ID + "/movement/global_anchor"));
    }

    @Test
    void positionAndOptionsAreDecodedWithoutTextFormatDependency() {
        JsonArray properties = new JsonArray();
        properties.add(property("safe_landing", name("preferred")));
        properties.add(property("preload_chunks", literal(false)));
        properties.add(property("fade", name("none")));
        EventScript script = script(
            position("cobbleventure:generation_1", 10.5, 72, -3.25, 90, 5),
            properties
        );
        AtomicReference<EventMovementGateway.MovementRequest> opened = new AtomicReference<>();
        EventSession session = session(script);

        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), opened),
            store(session), 10
        );

        assertEquals(
            new EventLocationRef.Position(
                "cobbleventure:generation_1", 10.5, 72, -3.25, 90F, 5F
            ),
            opened.get().destination()
        );
        assertEquals(
            EventMovementGateway.SafeLanding.PREFERRED,
            opened.get().options().safeLanding()
        );
        assertFalse(opened.get().options().preloadChunks());
    }

    @Test
    void resourceDestinationAndAnchorRemainTypedForFutureResolver() {
        JsonArray properties = new JsonArray();
        properties.add(property("anchor", literal("pokemon_center/interior")));
        EventScript script = script(
            resourceCall("settlement", "cobbleventure:settlement/starter_town"),
            properties
        );
        AtomicReference<EventMovementGateway.MovementRequest> opened = new AtomicReference<>();
        EventSession session = session(script);

        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), opened),
            store(session), 10
        );

        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.SETTLEMENT,
                "cobbleventure:settlement/starter_town",
                "pokemon_center/interior"
            ),
            opened.get().destination()
        );
    }

    @Test
    void enterSpaceUsesSpaceLocationAndItsOwnRecoverableAwaitKind() {
        JsonArray properties = new JsonArray();
        properties.add(property("anchor", literal("west")));
        EventScript script = script(
            "enter_space",
            resourceCall("space", "cobbleventure:cave/mt_moon"),
            properties
        );
        EventSession session = session(script);
        AtomicReference<EventMovementGateway.MovementRequest> opened = new AtomicReference<>();

        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), opened),
            store(session), 10
        );

        assertEquals(EventSession.Status.WAITING, session.status());
        assertEquals("enter_space", session.awaiting().kind());
        assertEquals(
            new EventLocationRef.Resource(
                EventLocationRef.Resource.Kind.SPACE,
                "cobbleventure:cave/mt_moon",
                "west"
            ),
            opened.get().destination()
        );
    }

    @Test
    void enterSpaceRejectsNonSpaceDestination() {
        EventScript script = script(
            "enter_space", relative(0, 0, -4), new JsonArray()
        );
        EventSession session = session(script);
        AtomicInteger opens = new AtomicInteger();

        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            script, session, environment(), adapter(opens, new AtomicReference<>()),
            store(session), 10
        ));
        assertEquals(0, opens.get());
    }

    @Test
    void failedTeleportReturnsTypedResultWithoutConsumingRetryableOperation() {
        EventScript script = script(relative(0, 0, -4), new JsonArray());
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        TeleportEventCommandAdapter adapter = adapter(
            new AtomicInteger(), new AtomicReference<>()
        );
        EventInterpreter.run(script, session, environment(), adapter, store, 10);

        EventAwaitCompletionService.Outcome outcome =
            EventAwaitCompletionService.completeAndRun(
                PLAYER_ID, session.key(), "movement-token",
                new EventSession.AwaitCompletion(
                    EventSession.CompletionKind.FAILED,
                    result(
                        false, "unsafe_landing",
                        new EventLocationRef.Relative(0, 0, -4)
                    )
                ),
                script, environment(), adapter, store, 10
            );

        assertEquals(EventAwaitCompletionService.Status.RESUMED, outcome.status());
        assertFalse(session.hasCompletedOperation(OPERATION_ID));
        JsonObject movement = session.locals().get("travel").getAsJsonObject();
        assertFalse(movement.get("arrived").getAsBoolean());
        assertEquals("unsafe_landing", movement.get("failure_reason").getAsString());
    }

    @Test
    void unsupportedTeleportPropertyIsRejectedBeforeGateway() {
        JsonArray properties = new JsonArray();
        properties.add(property("speed", literal(0.9)));
        EventScript script = script(relative(0, 0, 1), properties);
        EventSession session = session(script);
        AtomicInteger opens = new AtomicInteger();

        assertThrows(EventRuntimeException.class, () -> EventInterpreter.run(
            script, session, environment(), adapter(opens, new AtomicReference<>()),
            store(session), 10
        ));
        assertEquals(0, opens.get());
    }

    @Test
    void lostTeleportCallbackCanBeResetOnReinteraction() {
        EventScript script = script(relative(0, 0, 1), new JsonArray());
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), new AtomicReference<>()),
            store, 10
        );

        assertTrue(EventRecoverableAwait.resetTeleport(store, session.key()));
        assertEquals(EventSession.Status.CANCELLED, session.status());
    }

    @Test
    void lostWalkCallbackCanBeResetOnReinteraction() {
        EventScript script = script("move", relative(0, 0, 2), new JsonArray());
        EventSession session = session(script);
        InMemoryEventSessionStore store = store(session);
        EventInterpreter.run(
            script, session, environment(), adapter(new AtomicInteger(), new AtomicReference<>()),
            store, 10
        );

        assertEquals("move", session.awaiting().kind());
        assertTrue(EventRecoverableAwait.resetMovement(store, session.key()));
        assertEquals(EventSession.Status.CANCELLED, session.status());
    }

    private static TeleportEventCommandAdapter adapter(
        AtomicInteger opens,
        AtomicReference<EventMovementGateway.MovementRequest> opened
    ) {
        return new TeleportEventCommandAdapter(
            request -> {
                opens.incrementAndGet();
                opened.set(request);
                return new EventMovementGateway.OpenResult("movement-token", 0);
            },
            environment(),
            context -> new EventCommandAdapter.Completed(null)
        );
    }

    private static EventScript script(JsonObject destination, JsonArray properties) {
        return script("teleport", destination, properties);
    }

    private static EventScript script(
        String commandName, JsonObject destination, JsonArray properties
    ) {
        return script(commandName, "player", destination, properties);
    }

    private static EventScript script(
        String commandName,
        String subject,
        JsonObject destination,
        JsonArray properties
    ) {
        JsonArray arguments = new JsonArray();
        arguments.add(argument(name(subject)));
        arguments.add(argument(destination));
        JsonObject command = new JsonObject();
        command.addProperty("command", commandName);
        command.add("arguments", arguments);
        command.add("properties", properties);
        command.addProperty("await", true);
        command.addProperty("await_explicit", true);
        command.addProperty("result", "travel");
        command.addProperty("operation_id", OPERATION_ID);
        command.addProperty("next", 1);
        command.addProperty("resume", 1);
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    new EventScript.Instruction(0, "travel/leave_gate", "command", command),
                    new EventScript.Instruction(1, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static EventScript sequentialMovementScript() {
        JsonArray walkProperties = new JsonArray();
        walkProperties.add(property("mode", name("walk")));
        walkProperties.add(property("speed", literal(1.25)));
        walkProperties.add(property("lock_input", literal(true)));
        walkProperties.add(property("collision", name("stop")));
        JsonArray absoluteProperties = new JsonArray();
        absoluteProperties.add(property("safe_landing", name("preferred")));
        absoluteProperties.add(property("preload_chunks", literal(true)));
        absoluteProperties.add(property("fade", name("black")));
        JsonArray anchorProperties = new JsonArray();
        anchorProperties.add(property("anchor", literal("town_square")));
        anchorProperties.add(property("safe_landing", name("required")));
        anchorProperties.add(property("preload_chunks", literal(true)));
        anchorProperties.add(property("fade", name("white")));
        JsonArray routeProperties = new JsonArray();
        routeProperties.add(property("anchor", literal("middle")));
        routeProperties.add(property("safe_landing", name("required")));
        routeProperties.add(property("preload_chunks", literal(true)));
        routeProperties.add(property("fade", name("none")));
        JsonArray dimensionProperties = new JsonArray();
        dimensionProperties.add(property("anchor", literal("world/spawn")));
        dimensionProperties.add(property("safe_landing", name("required")));
        dimensionProperties.add(property("preload_chunks", literal(true)));
        dimensionProperties.add(property("fade", name("none")));
        JsonArray spaceProperties = new JsonArray();
        spaceProperties.add(property("anchor", literal("west_gallery")));
        spaceProperties.add(property("safe_landing", name("required")));
        spaceProperties.add(property("preload_chunks", literal(true)));
        spaceProperties.add(property("fade", name("none")));
        return new EventScript(
            1, SCRIPT_ID, DIGEST,
            List.of(new EventScript.Event(
                0,
                new EventScript.Trigger("interact", new JsonObject()),
                List.of(new EventScript.Page(0, null, 0)),
                List.of(
                    movementInstruction(
                        0, "movement/npc_relative", "move", "npc",
                        relative(2, 0, -1), walkProperties, "npc_movement", 1
                    ),
                    movementInstruction(
                        1, "movement/player_absolute", "teleport", "player",
                        position("cobbleventure:generation_1", 600, 69, -390, 0, 0),
                        absoluteProperties, "absolute_movement", 2
                    ),
                    movementInstruction(
                        2, "movement/starter_town", "teleport", "player",
                        resourceCall("settlement", "cobbleventure:settlement/starter_town"),
                        anchorProperties, "anchor_movement", 3
                    ),
                    movementInstruction(
                        3, "movement/route_middle", "teleport", "player",
                        resourceCall("route", "cobbleventure:route/route_custom_01"),
                        routeProperties, "route_movement", 4
                    ),
                    movementInstruction(
                        4, "movement/world_spawn", "teleport", "player",
                        resourceCall("dimension", "cobbleventure:generation_1"),
                        dimensionProperties, "dimension_movement", 5
                    ),
                    movementInstruction(
                        5, "movement/mt_moon", "enter_space", "player",
                        resourceCall("space", "cobbleventure:cave/mt_moon"),
                        spaceProperties, "space_movement", 6
                    ),
                    movementInstruction(
                        6, "movement/global_anchor", "teleport", "player",
                        resourceCall("anchor", "cobbleventure:event_anchor/world_spawn"),
                        new JsonArray(), "global_anchor_movement", 7
                    ),
                    new EventScript.Instruction(7, "page/end", "page_end", new JsonObject())
                )
            ))
        );
    }

    private static EventScript.Instruction movementInstruction(
        int address,
        String stableId,
        String commandName,
        String subject,
        JsonObject destination,
        JsonArray properties,
        String result,
        int next
    ) {
        JsonArray arguments = new JsonArray();
        arguments.add(argument(name(subject)));
        arguments.add(argument(destination));
        JsonObject command = new JsonObject();
        command.addProperty("command", commandName);
        command.add("arguments", arguments);
        command.add("properties", properties);
        command.addProperty("await", true);
        command.addProperty("await_explicit", true);
        command.addProperty("result", result);
        command.addProperty("operation_id", SCRIPT_ID + "/" + stableId);
        command.addProperty("next", next);
        command.addProperty("resume", next);
        return new EventScript.Instruction(address, stableId, "command", command);
    }

    private static JsonObject relative(double x, double y, double z) {
        JsonArray arguments = new JsonArray();
        arguments.add(namedArgument("x", literal(x)));
        arguments.add(namedArgument("y", literal(y)));
        arguments.add(namedArgument("z", literal(z)));
        return call("relative", arguments);
    }

    private static JsonObject position(
        String dimension, double x, double y, double z, double yaw, double pitch
    ) {
        JsonArray arguments = new JsonArray();
        arguments.add(namedArgument("dimension", literal(dimension)));
        arguments.add(namedArgument("x", literal(x)));
        arguments.add(namedArgument("y", literal(y)));
        arguments.add(namedArgument("z", literal(z)));
        arguments.add(namedArgument("yaw", literal(yaw)));
        arguments.add(namedArgument("pitch", literal(pitch)));
        return call("position", arguments);
    }

    private static JsonObject resourceCall(String function, String id) {
        JsonArray arguments = new JsonArray();
        arguments.add(argument(literal(id)));
        return call(function, arguments);
    }

    private static JsonObject call(String function, JsonArray arguments) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "call");
        value.add("callee", name(function));
        value.add("arguments", arguments);
        return value;
    }

    private static JsonObject argument(JsonElement value) {
        JsonObject argument = new JsonObject();
        argument.add("name", JsonNull.INSTANCE);
        argument.add("value", value);
        return argument;
    }

    private static JsonObject namedArgument(String name, JsonElement value) {
        JsonObject argument = new JsonObject();
        argument.addProperty("name", name);
        argument.add("value", value);
        return argument;
    }

    private static JsonObject property(String name, JsonElement value) {
        JsonObject property = new JsonObject();
        property.addProperty("name", name);
        property.add("value", value);
        return property;
    }

    private static JsonObject name(String name) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "name");
        value.addProperty("name", name);
        return value;
    }

    private static JsonObject literal(Object raw) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "literal");
        if (raw instanceof Boolean bool) value.addProperty("value", bool);
        else if (raw instanceof Number number) value.add("value", new JsonPrimitive(number));
        else value.addProperty("value", String.valueOf(raw));
        return value;
    }

    private static JsonObject result(
        boolean arrived, String failureReason, EventLocationRef destination
    ) {
        JsonObject value = new JsonObject();
        value.addProperty("arrived", arrived);
        value.addProperty("failure_reason", failureReason == null ? "" : failureReason);
        value.add("destination", destination.toJson());
        return value;
    }

    private static EventSession session(EventScript script) {
        EventSession session = EventSession.create(
            new EventSessionKey(
                PLAYER_ID,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                SCRIPT_ID,
                "interact"
            ),
            script, 0, 0
        );
        session.start();
        return session;
    }

    private static InMemoryEventSessionStore store(EventSession session) {
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        store.putIfAbsent(session);
        return store;
    }

    private static EventExpressionEnvironment environment() {
        return new EventExpressionEnvironment() {
            @Override public Optional<JsonElement> resolveName(String name) {
                return Optional.empty();
            }

            @Override public JsonElement call(String function, List<Argument> arguments) {
                return JsonNull.INSTANCE;
            }
        };
    }
}
