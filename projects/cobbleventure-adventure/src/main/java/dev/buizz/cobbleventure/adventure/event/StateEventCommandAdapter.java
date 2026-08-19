package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes immediate, idempotent player-state commands and delegates all other commands. */
public final class StateEventCommandAdapter implements EventCommandAdapter {
    private final EventStateExpressionEnvironment environment;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public StateEventCommandAdapter(
        EventStateExpressionEnvironment environment,
        EventCommandAdapter fallback
    ) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.evaluator = new EventExpressionEvaluator(environment);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())) {
            return fallback.start(context);
        }
        return switch (instruction.command()) {
            case "set_flag" -> setFlag(instruction, context.locals());
            case "set_player_variable" -> setPlayerVariable(instruction, context.locals());
            case "unlock_feature" -> unlockFeature(instruction, context.locals());
            case "set_level_cap" -> setLevelCap(instruction, context.locals());
            case "give_money" -> changeMoney(instruction, context.locals(), false);
            case "take_money" -> changeMoney(instruction, context.locals(), true);
            case "grant_badge" -> grantBadge(instruction, context.locals());
            case "grant_field_move" -> grantFieldMove(instruction, context.locals());
            default -> fallback.start(context);
        };
    }

    private StartResult setFlag(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(2);
        environment.state().setFlag(arguments.string(0), arguments.bool(1));
        return new Completed(null);
    }

    private StartResult setPlayerVariable(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(2);
        environment.state().setPlayerVariable(arguments.string(0), arguments.positional().get(1));
        return new Completed(null);
    }

    private StartResult unlockFeature(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(1);
        environment.state().unlockFeature(arguments.string(0));
        return new Completed(null);
    }

    private StartResult setLevelCap(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(1);
        environment.state().setLevelCap(evaluatorInt(arguments.positional().getFirst()));
        return new Completed(new JsonPrimitive(true));
    }

    private StartResult changeMoney(
        EventScript.Instruction instruction, Map<String, JsonElement> locals,
        boolean take
    ) {
        if (instruction.operationId() == null) {
            throw new EventRuntimeException(instruction.command() + "에는 operation ID가 필요합니다.");
        }
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(1, take ? Set.of("allow_debt") : Set.of("notify"));
        int amount = evaluatorInt(arguments.positional().getFirst());
        if (amount <= 0) {
            throw new EventRuntimeException(instruction.command() + " 금액은 양수여야 합니다: " + amount);
        }
        boolean success = environment.state().changeMoney(
            instruction.operationId(),
            java.math.BigInteger.valueOf(take ? -amount : amount),
            arguments.flags().contains("notify"),
            arguments.flags().contains("allow_debt")
        );
        return new Completed(new JsonPrimitive(success));
    }

    private StartResult grantBadge(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(1);
        environment.state().grantBadge(arguments.string(0));
        return new Completed(new JsonPrimitive(true));
    }

    private StartResult grantFieldMove(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        Arguments arguments = arguments(instruction, locals);
        arguments.requireShape(1);
        environment.state().grantFieldMove(arguments.string(0));
        return new Completed(new JsonPrimitive(true));
    }

    private Arguments arguments(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("arguments");
        if (raw == null || !raw.isJsonArray()) {
            throw new EventRuntimeException("명령 arguments는 배열이어야 합니다: " + instruction.command());
        }
        List<JsonElement> positional = new ArrayList<>();
        Map<String, JsonElement> named = new LinkedHashMap<>();
        Set<String> flags = new java.util.LinkedHashSet<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new EventRuntimeException("명령 argument는 객체여야 합니다.");
            }
            JsonObject argument = element.getAsJsonObject();
            JsonElement nameValue = argument.get("name");
            String name = nameValue == null || nameValue.isJsonNull()
                ? null
                : nameValue.getAsString();
            JsonElement value = argument.get("value");
            if (value == null || value.isJsonNull()) {
                if (name == null || !flags.add(name)) {
                    throw new EventRuntimeException("올바르지 않거나 중복된 명령 flag입니다: " + name);
                }
                continue;
            }
            JsonElement evaluated = evaluator.evaluate(value, locals);
            if (name == null) {
                positional.add(evaluated);
            } else if (named.putIfAbsent(name, evaluated) != null) {
                throw new EventRuntimeException("중복 명령 인자입니다: " + name);
            }
        }
        return new Arguments(List.copyOf(positional), Map.copyOf(named), Set.copyOf(flags));
    }

    private int evaluatorInt(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException("int 명령 인자가 필요합니다: " + value);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) {
            throw new EventRuntimeException("정수가 아니거나 범위를 벗어난 명령 인자입니다: " + value, error);
        }
    }

    private record Arguments(
        List<JsonElement> positional, Map<String, JsonElement> named, Set<String> flags
    ) {
        void requireShape(int positionalCount) {
            requireShape(positionalCount, Set.of());
        }

        void requireShape(int positionalCount, Set<String> allowedFlags) {
            if (positional.size() != positionalCount || !named.isEmpty()
                || !allowedFlags.containsAll(flags)) {
                throw new EventRuntimeException(
                    "명령 인자 형태가 올바르지 않습니다. positional=" + positional.size()
                        + ", named=" + named.keySet() + ", flags=" + flags
                );
            }
        }

        String string(int index) {
            JsonElement value = positional.get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new EventRuntimeException("문자열 명령 인자가 필요합니다: " + value);
            }
            return value.getAsString();
        }

        boolean bool(int index) {
            JsonElement value = positional.get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw new EventRuntimeException("bool 명령 인자가 필요합니다: " + value);
            }
            return value.getAsBoolean();
        }
    }
}
