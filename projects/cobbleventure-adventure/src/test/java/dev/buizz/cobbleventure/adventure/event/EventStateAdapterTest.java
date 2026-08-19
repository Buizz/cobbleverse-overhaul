package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class EventStateAdapterTest {
    private static final EventSessionKey KEY = new EventSessionKey(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        "cobbleventure:event_script/test/state",
        "interact"
    );

    @Test
    void environmentExposesPlayerAndTypedBuiltins() {
        FakeState state = new FakeState();
        state.flags.put("cobbleventure:flag/test", true);
        state.balance = BigInteger.valueOf(1250);
        state.levelCap = 35;
        state.items.put("cobblemon:potion", 2);
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(state);
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(environment);

        assertEquals(
            "레드",
            evaluator.evaluate(member(name("player"), "name"), Map.of()).getAsString()
        );
        assertTrue(evaluator.evaluateBoolean(
            call("flag", literal("cobbleventure:flag/test")), Map.of()
        ));
        assertEquals(1250, evaluator.evaluate(call("money"), Map.of()).getAsInt());
        assertEquals(35, evaluator.evaluate(call("level_cap"), Map.of()).getAsInt());
        assertTrue(evaluator.evaluateBoolean(
            call("has_item", literal("cobblemon:potion"), literal(2)), Map.of()
        ));
        assertFalse(evaluator.evaluateBoolean(
            call("has_item", literal("cobblemon:potion"), literal(3)), Map.of()
        ));
        assertThrows(
            EventRuntimeException.class,
            () -> evaluator.evaluate(
                call("has_item", literal("cobblemon:potion"), literal(0)), Map.of()
            )
        );
        assertThrows(
            EventRuntimeException.class,
            () -> evaluator.evaluate(call("money", literal(1)), Map.of())
        );
    }

    @Test
    void moneyOutsideRuntimeIntIsRejected() {
        FakeState state = new FakeState();
        state.balance = BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE);
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(
            new EventStateExpressionEnvironment(state)
        );

        assertThrows(
            EventRuntimeException.class,
            () -> evaluator.evaluate(call("money"), Map.of())
        );
    }

    @Test
    void moneyExpressionPreservesDebt() {
        FakeState state = new FakeState();
        state.balance = BigInteger.valueOf(-125);
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(
            new EventStateExpressionEnvironment(state)
        );

        assertEquals(-125, evaluator.evaluate(call("money"), Map.of()).getAsInt());
    }

    @Test
    void stateAdapterEvaluatesArgumentsAndDelegatesUnknownCommands() {
        FakeState state = new FakeState();
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(state);
        AtomicInteger delegated = new AtomicInteger();
        StateEventCommandAdapter adapter = new StateEventCommandAdapter(
            environment,
            context -> {
                delegated.incrementAndGet();
                return new EventCommandAdapter.Completed(null);
            }
        );
        Map<String, JsonElement> locals = Map.of("enabled", new JsonPrimitive(true));

        adapter.start(context(command(
            "set_flag",
            argument(literal("cobbleventure:flag/story/test")),
            argument(name("enabled"))
        ), locals));
        adapter.start(context(command(
            "set_player_variable",
            argument(literal("cobbleventure:variable/story/visits")),
            argument(literal(3))
        ), locals));
        adapter.start(context(command(
            "unlock_feature",
            argument(literal("cobbleventure:feature/map"))
        ), locals));
        EventCommandAdapter.StartResult capResult = adapter.start(context(command(
            "set_level_cap", argument(literal(25))
        ), locals));
        adapter.start(context(command("give_item"), locals));

        assertTrue(state.flags.get("cobbleventure:flag/story/test"));
        assertEquals(3, state.variables.get("cobbleventure:variable/story/visits").getAsInt());
        assertEquals("cobbleventure:feature/map", state.unlockedFeature);
        assertEquals(25, state.levelCap);
        assertTrue(capResult instanceof EventCommandAdapter.Completed);
        assertTrue(((EventCommandAdapter.Completed) capResult).result().getAsBoolean());
        assertEquals(1, delegated.get());
    }

    @Test
    void stateAdapterRejectsWrongRuntimeTypesBeforeMutation() {
        FakeState state = new FakeState();
        StateEventCommandAdapter adapter = new StateEventCommandAdapter(
            new EventStateExpressionEnvironment(state),
            context -> new EventCommandAdapter.Completed(null)
        );

        assertThrows(EventRuntimeException.class, () -> adapter.start(context(command(
            "set_flag", argument(literal("cobbleventure:flag/test")), argument(literal(1))
        ), Map.of())));
        assertFalse(state.flags.containsKey("cobbleventure:flag/test"));
    }

    @Test
    void moneyCommandsUseStableOperationAndReturnSuccess() {
        FakeState state = new FakeState();
        state.balance = BigInteger.valueOf(500);
        StateEventCommandAdapter adapter = adapter(state);
        EventScript.Instruction give = commandWithOperation(
            "give_money", "reward/money",
            argument(literal(200)), flag("notify")
        );

        EventCommandAdapter.Completed first = (EventCommandAdapter.Completed)
            adapter.start(context(give, Map.of()));
        EventCommandAdapter.Completed replay = (EventCommandAdapter.Completed)
            adapter.start(context(give, Map.of()));
        EventCommandAdapter.Completed insufficient = (EventCommandAdapter.Completed)
            adapter.start(context(commandWithOperation(
                "take_money", "cost/too_much", argument(literal(900))
            ), Map.of()));
        EventScript.Instruction debtCommand = commandWithOperation(
            "take_money", "cost/debt", argument(literal(900)), flag("allow_debt")
        );
        EventCommandAdapter.Completed debt = (EventCommandAdapter.Completed)
            adapter.start(context(debtCommand, Map.of()));
        EventCommandAdapter.Completed debtReplay = (EventCommandAdapter.Completed)
            adapter.start(context(debtCommand, Map.of()));

        assertTrue(first.result().getAsBoolean());
        assertTrue(replay.result().getAsBoolean());
        assertFalse(insufficient.result().getAsBoolean());
        assertTrue(debt.result().getAsBoolean());
        assertTrue(debtReplay.result().getAsBoolean());
        assertEquals(BigInteger.valueOf(-200), state.balance);
        assertTrue(state.moneyNotification);
    }

    @Test
    void badgeAndFieldMoveRewardsAreIdempotentStateAssignments() {
        FakeState state = new FakeState();
        StateEventCommandAdapter adapter = adapter(state);

        adapter.start(context(command(
            "grant_badge", argument(literal("cobbleventure:badge/kanto/boulder"))
        ), Map.of()));
        adapter.start(context(command(
            "grant_field_move", argument(literal("rock_smash"))
        ), Map.of()));

        assertEquals("cobbleventure:badge/kanto/boulder", state.badge);
        assertEquals("rock_smash", state.fieldMove);
    }

    @Test
    void serverFlagObjectiveMatchesTheV4CompatibilityMapping() {
        assertEquals(
            "cvf_0a2dac5ae4da",
            ServerPlayerEventState.flagObjective(
                "cobbleventure:flag/story/pokedex_received"
            )
        );
        assertEquals(
            "cv_starter_recv",
            ServerPlayerEventState.flagObjective(
                "cobbleventure:flag/story/starter_received"
            )
        );
        assertThrows(
            EventRuntimeException.class,
            () -> ServerPlayerEventState.flagObjective("not a resource id")
        );
    }

    private static EventCommandAdapter.CommandContext context(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        return new EventCommandAdapter.CommandContext(KEY, "digest", instruction, locals);
    }

    private static StateEventCommandAdapter adapter(FakeState state) {
        return new StateEventCommandAdapter(
            new EventStateExpressionEnvironment(state),
            context -> new EventCommandAdapter.Completed(null)
        );
    }

    private static EventScript.Instruction command(String command, JsonObject... arguments) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", command);
        JsonArray values = new JsonArray();
        for (JsonObject argument : arguments) {
            values.add(argument);
        }
        payload.add("arguments", values);
        return new EventScript.Instruction(0, "test/" + command, "command", payload);
    }

    private static EventScript.Instruction commandWithOperation(
        String command, String operationId, JsonObject... arguments
    ) {
        EventScript.Instruction base = command(command, arguments);
        JsonObject payload = base.payload();
        payload.addProperty("operation_id", operationId);
        return new EventScript.Instruction(0, "test/" + command, "command", payload);
    }

    private static JsonObject argument(JsonObject value) {
        JsonObject argument = new JsonObject();
        argument.add("name", null);
        argument.add("value", value);
        return argument;
    }

    private static JsonObject flag(String name) {
        JsonObject argument = new JsonObject();
        argument.addProperty("name", name);
        argument.add("value", null);
        return argument;
    }

    private static JsonObject literal(String value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "literal");
        expression.addProperty("type", "string");
        expression.addProperty("value", value);
        return expression;
    }

    private static JsonObject literal(int value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "literal");
        expression.addProperty("type", "int");
        expression.addProperty("value", value);
        return expression;
    }

    private static JsonObject name(String value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "name");
        expression.addProperty("name", value);
        return expression;
    }

    private static JsonObject member(JsonObject target, String value) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "member");
        expression.add("target", target);
        expression.addProperty("member", value);
        return expression;
    }

    private static JsonObject call(String function, JsonObject... values) {
        JsonObject expression = new JsonObject();
        expression.addProperty("kind", "call");
        expression.add("callee", name(function));
        JsonArray arguments = new JsonArray();
        for (JsonObject value : values) {
            arguments.add(argument(value));
        }
        expression.add("arguments", arguments);
        return expression;
    }

    private static final class FakeState implements EventStateAccess {
        private final Map<String, Boolean> flags = new LinkedHashMap<>();
        private final Map<String, JsonElement> variables = new LinkedHashMap<>();
        private final Map<String, Integer> items = new LinkedHashMap<>();
        private BigInteger balance = BigInteger.ZERO;
        private int levelCap = 5;
        private String unlockedFeature;
        private final Map<String, Boolean> moneyResults = new LinkedHashMap<>();
        private boolean moneyNotification;
        private String badge;
        private String fieldMove;

        @Override public String playerName() { return "레드"; }
        @Override public boolean flag(String resourceId) {
            return flags.getOrDefault(resourceId, false);
        }
        @Override public boolean hasItem(String resourceId, int count) {
            return items.getOrDefault(resourceId, 0) >= count;
        }
        @Override public BigInteger money() { return balance; }
        @Override public int levelCap() { return levelCap; }
        @Override public void setFlag(String resourceId, boolean value) {
            flags.put(resourceId, value);
        }
        @Override public void setPlayerVariable(String resourceId, JsonElement value) {
            variables.put(resourceId, value.deepCopy());
        }
        @Override public void unlockFeature(String resourceId) {
            unlockedFeature = resourceId;
        }
        @Override public void setLevelCap(int level) { levelCap = level; }
        @Override public boolean changeMoney(
            String operationId, BigInteger delta, boolean notify, boolean allowDebt
        ) {
            Boolean previous = moneyResults.get(operationId);
            if (previous != null) return previous;
            boolean success = delta.signum() > 0 || allowDebt
                || balance.compareTo(delta.negate()) >= 0;
            if (success) balance = balance.add(delta);
            moneyResults.put(operationId, success);
            moneyNotification |= success && notify;
            return success;
        }
        @Override public void grantBadge(String resourceId) { badge = resourceId; }
        @Override public void grantFieldMove(String move) { fieldMove = move; }
    }
}
