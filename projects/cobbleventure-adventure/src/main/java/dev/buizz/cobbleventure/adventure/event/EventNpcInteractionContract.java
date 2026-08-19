package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Validates the runtime portion of the V5 NPC-interaction contract. */
public final class EventNpcInteractionContract {
    public static final double DEFAULT_RANGE = 4.0;

    private EventNpcInteractionContract() {}

    public static Optional<EventScript.Event> uniqueInteractEvent(EventScript script) {
        List<EventScript.Event> matches = new ArrayList<>();
        for (EventScript.Event event : script.events()) {
            if (event.trigger().name().equals("interact")) matches.add(event);
        }
        if (matches.size() > 1) {
            throw new EventRuntimeException(
                "NPC 스크립트에는 interact 이벤트가 하나만 있어야 합니다: " + script.scriptId()
            );
        }
        return matches.stream().findFirst();
    }

    public static double interactionRange(
        EventScript.Event event, EventExpressionEnvironment environment
    ) {
        JsonObject trigger = event.trigger().payload();
        JsonElement argumentsValue = trigger.get("arguments");
        if (argumentsValue == null) return DEFAULT_RANGE;
        if (!argumentsValue.isJsonArray()) {
            throw new EventRuntimeException("interact trigger arguments가 배열이 아닙니다.");
        }
        Double range = null;
        JsonArray arguments = argumentsValue.getAsJsonArray();
        for (JsonElement value : arguments) {
            if (!value.isJsonObject()) {
                throw new EventRuntimeException("interact trigger argument가 object가 아닙니다.");
            }
            JsonObject argument = value.getAsJsonObject();
            if (!argument.has("name") || argument.get("name").isJsonNull()
                || !"range".equals(argument.get("name").getAsString())) continue;
            if (range != null) {
                throw new EventRuntimeException("interact range 인수가 중복됐습니다.");
            }
            range = positiveNumber(argument.get("value"), environment);
        }
        return range == null ? DEFAULT_RANGE : range;
    }

    private static double positiveNumber(
        JsonElement expression, EventExpressionEnvironment environment
    ) {
        JsonElement evaluated = new EventExpressionEvaluator(environment)
            .evaluate(expression, Map.of());
        if (!evaluated.isJsonPrimitive() || !evaluated.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException("interact range 표현식 결과는 숫자여야 합니다.");
        }
        double result = evaluated.getAsDouble();
        if (!Double.isFinite(result) || result <= 0) {
            throw new EventRuntimeException("interact range는 0보다 큰 유한수여야 합니다.");
        }
        return result;
    }
}
