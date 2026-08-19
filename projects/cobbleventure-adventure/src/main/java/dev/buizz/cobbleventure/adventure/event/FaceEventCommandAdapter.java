package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;

/** Decodes the immediate typed CVES face command. */
public final class FaceEventCommandAdapter implements EventCommandAdapter {
    private final EventFacingGateway gateway;
    private final EventCommandAdapter fallback;

    public FaceEventCommandAdapter(EventFacingGateway gateway, EventCommandAdapter fallback) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"face".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (instruction.awaitsResult() || instruction.operationId() != null) {
            throw new EventRuntimeException("face는 await 또는 operation ID를 사용할 수 없습니다.");
        }
        JsonArray arguments = array(instruction.rawPayload(), "arguments");
        if (arguments.size() != 2) {
            throw new EventRuntimeException("face에는 subject와 direction이 필요합니다.");
        }
        EventFacingGateway.Subject subject = enumName(
            positionalName(arguments.get(0), "face subject"),
            EventFacingGateway.Subject.class,
            "face subject"
        );
        EventFacingGateway.Direction direction = enumName(
            positionalName(arguments.get(1), "face direction"),
            EventFacingGateway.Direction.class,
            "face direction"
        );
        gateway.face(new EventFacingGateway.FacingRequest(
            context.sessionKey(), instruction.instructionId(), subject, direction
        ));
        return new Completed(null);
    }

    private static String positionalName(JsonElement value, String description) {
        JsonObject argument = object(value, "face argument");
        if (!required(argument, "name").isJsonNull()) {
            throw new EventRuntimeException("face 인자는 위치 인자여야 합니다.");
        }
        JsonObject expression = object(required(argument, "value"), description);
        if (!"name".equals(text(required(expression, "kind"), "kind"))) {
            throw new EventRuntimeException(description + "는 이름이어야 합니다.");
        }
        return text(required(expression, "name"), "name");
    }

    private static <E extends Enum<E>> E enumName(
        String value, Class<E> type, String description
    ) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new EventRuntimeException(
                "지원하지 않는 " + description + "입니다: " + value, error
            );
        }
    }

    private static JsonArray array(JsonObject value, String name) {
        JsonElement element = required(value, name);
        if (!element.isJsonArray()) throw new EventRuntimeException(name + "은 배열이어야 합니다.");
        return element.getAsJsonArray();
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new EventRuntimeException(name + "은 객체여야 합니다.");
        }
        return value.getAsJsonObject();
    }

    private static JsonElement required(JsonObject value, String name) {
        if (!value.has(name)) throw new EventRuntimeException("필드가 없습니다: " + name);
        return value.get(name);
    }

    private static String text(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException(name + "은 문자열이어야 합니다.");
        }
        return value.getAsString();
    }
}
