package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Decodes fade, wait, sound and effect into the common presentation await. */
public final class PresentationEventCommandAdapter implements EventCommandAdapter {
    private final EventPresentationGateway gateway;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public PresentationEventCommandAdapter(
        EventPresentationGateway gateway,
        EventExpressionEnvironment environment,
        EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.evaluator = new EventExpressionEvaluator(environment);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        String command = instruction.command();
        EventPresentationGateway.Kind kind = kind(command);
        if (!"command".equals(instruction.operation()) || kind == null) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult() || instruction.resumeAddress() == null
            || instruction.operationId() == null) {
            throw new EventRuntimeException(
                command + "에는 안정 ID, await와 resume 주소가 필요합니다."
            );
        }
        JsonArray arguments = array(instruction.rawPayload(), "arguments");
        if (arguments.size() != 1) {
            throw new EventRuntimeException(command + "에는 인자 하나가 필요합니다.");
        }
        JsonElement expression = positional(arguments.get(0));
        String resourceId = null;
        EventPresentationGateway.FadeColor fadeColor = null;
        double duration = kind == EventPresentationGateway.Kind.FADE ? 0.5D : 0D;
        if (kind == EventPresentationGateway.Kind.WAIT) {
            duration = number(evaluator.evaluate(expression, context.locals()), "wait duration");
        } else if (kind == EventPresentationGateway.Kind.FADE) {
            fadeColor = enumName(
                name(expression, "fade color"), EventPresentationGateway.FadeColor.class
            );
        } else {
            resourceId = text(evaluator.evaluate(expression, context.locals()), command);
            if (ResourceLocation.tryParse(resourceId) == null) {
                throw new EventRuntimeException(command + "는 리소스 ID여야 합니다: " + resourceId);
            }
        }
        EventPresentationGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventPresentationGateway.PresentationRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId(),
                instruction.operationId(), kind, resourceId, fadeColor, duration
            )),
            "presentation gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    private static EventPresentationGateway.Kind kind(String command) {
        if (command == null) return null;
        return switch (command) {
            case "fade" -> EventPresentationGateway.Kind.FADE;
            case "wait" -> EventPresentationGateway.Kind.WAIT;
            case "sound" -> EventPresentationGateway.Kind.SOUND;
            case "effect" -> EventPresentationGateway.Kind.EFFECT;
            default -> null;
        };
    }

    private static JsonElement positional(JsonElement value) {
        JsonObject argument = object(value, "presentation argument");
        if (!required(argument, "name").isJsonNull()) {
            throw new EventRuntimeException("연출 인자는 위치 인자여야 합니다.");
        }
        return required(argument, "value");
    }

    private static String name(JsonElement value, String description) {
        JsonObject expression = object(value, description);
        if (!"name".equals(text(required(expression, "kind"), "kind"))) {
            throw new EventRuntimeException(description + "는 이름이어야 합니다.");
        }
        return text(required(expression, "name"), "name");
    }

    private static <E extends Enum<E>> E enumName(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new EventRuntimeException("지원하지 않는 연출 값입니다: " + value, error);
        }
    }

    private static double number(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(name + "은 숫자여야 합니다.");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result < 0 || result > 3600) {
            throw new EventRuntimeException(name + "은 0~3600초여야 합니다.");
        }
        return result;
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
