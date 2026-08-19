package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Runtime view of common CVES trigger options, independent of boundary detection. */
final class EventTriggerContract {
    static final double DEFAULT_PROXIMITY_RANGE = 4.0D;

    record Options(double range, boolean once, double cooldownSeconds, String scope) {}
    record TargetOptions(String target, boolean once, double cooldownSeconds, String scope) {}

    private EventTriggerContract() {}

    static Options proximity(
        EventScript.Event event, EventExpressionEnvironment environment
    ) {
        String name = event.trigger().name();
        if (!name.equals("proximity_enter") && !name.equals("proximity_exit")) {
            throw new EventRuntimeException("proximity 트리거가 아닙니다: " + name);
        }
        JsonObject trigger = event.trigger().payload();
        JsonElement argumentsValue = trigger.get("arguments");
        if (argumentsValue == null) {
            return new Options(DEFAULT_PROXIMITY_RANGE, false, 0.0D, "player");
        }
        if (!argumentsValue.isJsonArray()) {
            throw new EventRuntimeException(name + " trigger arguments가 배열이 아닙니다.");
        }
        double range = DEFAULT_PROXIMITY_RANGE;
        boolean once = false;
        double cooldown = 0.0D;
        String scope = "player";
        Set<String> seen = new HashSet<>();
        JsonArray arguments = argumentsValue.getAsJsonArray();
        for (JsonElement value : arguments) {
            if (!value.isJsonObject()) {
                throw new EventRuntimeException(name + " trigger argument가 object가 아닙니다.");
            }
            JsonObject argument = value.getAsJsonObject();
            String argumentName = namedArgument(argument, name);
            if (!seen.add(argumentName)) {
                throw new EventRuntimeException(name + " trigger 인수가 중복됐습니다: " + argumentName);
            }
            JsonElement expression = argument.get("value");
            switch (argumentName) {
                case "range" -> range = positiveNumber(expression, environment, "range");
                case "once" -> once = bool(expression, environment, "once");
                case "cooldown" -> cooldown = nonNegativeNumber(
                    expression, environment, "cooldown"
                );
                case "scope" -> scope = scope(expression);
                default -> throw new EventRuntimeException(
                    name + " trigger에서 지원하지 않는 인수입니다: " + argumentName
                );
            }
        }
        if (!scope.equals("player")) {
            throw new EventRuntimeException(
                "현재 proximity trigger scope는 player만 지원합니다: " + scope
            );
        }
        return new Options(range, once, cooldown, scope);
    }

    static TargetOptions targeted(
        EventScript.Event event, EventExpressionEnvironment environment
    ) {
        String trigger = event.trigger().name();
        if (!Set.of(
            "region_enter", "region_exit", "anchor_step",
            "building_enter", "building_exit", "dimension_enter", "dimension_exit",
            "flag_changed", "item_used", "battle_finished"
        ).contains(trigger)) {
            throw new EventRuntimeException("target 기반 트리거가 아닙니다: " + trigger);
        }
        JsonElement argumentsValue = event.trigger().payload().get("arguments");
        if (argumentsValue == null || !argumentsValue.isJsonArray()) {
            throw new EventRuntimeException(trigger + " trigger arguments 배열이 필요합니다.");
        }
        String target = null;
        boolean once = false;
        double cooldown = 0.0D;
        String scope = "player";
        Set<String> seen = new HashSet<>();
        for (JsonElement value : argumentsValue.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                throw new EventRuntimeException(trigger + " trigger argument가 object가 아닙니다.");
            }
            JsonObject argument = value.getAsJsonObject();
            String argumentName = namedArgument(argument, trigger);
            if (!seen.add(argumentName)) {
                throw new EventRuntimeException(trigger + " trigger 인수가 중복됐습니다: " + argumentName);
            }
            JsonElement expression = argument.get("value");
            switch (argumentName) {
                case "target" -> target = resource(expression, environment, "target");
                case "once" -> once = bool(expression, environment, "once");
                case "cooldown" -> cooldown = nonNegativeNumber(
                    expression, environment, "cooldown"
                );
                case "scope" -> scope = scope(expression);
                default -> throw new EventRuntimeException(
                    trigger + " trigger에서 지원하지 않는 인수입니다: " + argumentName
                );
            }
        }
        if (target == null) {
            throw new EventRuntimeException(trigger + " trigger에는 target이 필요합니다.");
        }
        if (!scope.equals("player")) {
            throw new EventRuntimeException(
                "현재 공간 trigger scope는 player만 지원합니다: " + scope
            );
        }
        return new TargetOptions(target, once, cooldown, scope);
    }

    private static String namedArgument(JsonObject argument, String trigger) {
        JsonElement name = argument.get("name");
        if (name == null || name.isJsonNull() || !name.isJsonPrimitive()
            || !name.getAsJsonPrimitive().isString() || name.getAsString().isBlank()) {
            throw new EventRuntimeException(trigger + " trigger에는 이름 있는 인수만 허용됩니다.");
        }
        if (!argument.has("value") || argument.get("value").isJsonNull()) {
            throw new EventRuntimeException(trigger + " trigger 인수 값이 없습니다: " + name.getAsString());
        }
        return name.getAsString();
    }

    private static double positiveNumber(
        JsonElement expression, EventExpressionEnvironment environment, String label
    ) {
        double value = number(expression, environment, label);
        if (value <= 0.0D) {
            throw new EventRuntimeException(label + "는 0보다 커야 합니다.");
        }
        return value;
    }

    private static double nonNegativeNumber(
        JsonElement expression, EventExpressionEnvironment environment, String label
    ) {
        double value = number(expression, environment, label);
        if (value < 0.0D) {
            throw new EventRuntimeException(label + "는 0 이상이어야 합니다.");
        }
        return value;
    }

    private static double number(
        JsonElement expression, EventExpressionEnvironment environment, String label
    ) {
        JsonElement evaluated = new EventExpressionEvaluator(environment)
            .evaluate(expression, Map.of());
        if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(label + " 표현식 결과는 숫자여야 합니다.");
        }
        double value = evaluated.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new EventRuntimeException(label + "는 유한수여야 합니다.");
        }
        return value;
    }

    private static boolean bool(
        JsonElement expression, EventExpressionEnvironment environment, String label
    ) {
        JsonElement evaluated = new EventExpressionEvaluator(environment)
            .evaluate(expression, Map.of());
        if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isBoolean()) {
            throw new EventRuntimeException(label + " 표현식 결과는 bool이어야 합니다.");
        }
        return evaluated.getAsBoolean();
    }

    private static String resource(
        JsonElement expression, EventExpressionEnvironment environment, String label
    ) {
        JsonElement evaluated = new EventExpressionEvaluator(environment)
            .evaluate(expression, Map.of());
        if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isString()
            || ResourceLocation.tryParse(evaluated.getAsString()) == null) {
            throw new EventRuntimeException(label + "은 리소스 ID여야 합니다.");
        }
        return evaluated.getAsString();
    }

    private static String scope(JsonElement expression) {
        if (!expression.isJsonObject()) {
            throw new EventRuntimeException("scope는 이름이어야 합니다.");
        }
        JsonObject value = expression.getAsJsonObject();
        if (!"name".equals(value.has("kind") ? value.get("kind").getAsString() : null)
            || !value.has("name")) {
            throw new EventRuntimeException("scope는 이름이어야 합니다.");
        }
        return value.get("name").getAsString();
    }
}
