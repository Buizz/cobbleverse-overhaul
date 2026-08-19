package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import java.util.List;
import java.util.Optional;

/** Supplies player state, flags, money and location references to IR expressions. */
public interface EventExpressionEnvironment {
    record Argument(String name, JsonElement value) {}

    default Optional<JsonElement> resolveName(String name) {
        return Optional.empty();
    }

    JsonElement call(String function, List<Argument> arguments);

    default JsonElement member(JsonElement target, String name) {
        if (target != null && target.isJsonObject() && target.getAsJsonObject().has(name)) {
            return target.getAsJsonObject().get(name).deepCopy();
        }
        throw new EventRuntimeException("값에 필드가 없습니다: " + name);
    }
}
