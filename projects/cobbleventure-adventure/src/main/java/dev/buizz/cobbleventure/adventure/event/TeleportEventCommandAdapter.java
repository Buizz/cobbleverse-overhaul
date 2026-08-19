package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Decodes typed CVES teleport IR and opens a common movement await. */
public final class TeleportEventCommandAdapter implements EventCommandAdapter {
    private final EventMovementGateway gateway;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public TeleportEventCommandAdapter(
        EventMovementGateway gateway,
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
        if (!"command".equals(instruction.operation())
            || !("move".equals(command) || "teleport".equals(command)
                || "enter_space".equals(command))) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult()
            || instruction.resumeAddress() == null
            || instruction.resultVariable() == null
            || instruction.operationId() == null) {
            throw new EventRuntimeException(
                command + "에는 안정 ID, await, resume 주소와 결과 변수가 필요합니다."
            );
        }

        JsonArray arguments = array(instruction.rawPayload(), "arguments");
        if (arguments.size() != 2) {
            throw new EventRuntimeException(command + "에는 subject와 destination이 필요합니다.");
        }
        EventMovementGateway.Subject subject = subject(positional(arguments.get(0)));
        Map<String, JsonElement> properties = properties(instruction.rawPayload());
        EventLocationRef destination = destination(
            positional(arguments.get(1)), properties.get("anchor"), context.locals()
        );
        if ("enter_space".equals(command)
            && (!(destination instanceof EventLocationRef.Resource resource)
                || resource.kind() != EventLocationRef.Resource.Kind.SPACE)) {
            throw new EventRuntimeException(
                "enter_space destination은 space(...) 위치여야 합니다."
            );
        }
        rejectUnusedProperties(command, properties);
        EventMovementGateway.Options options = options(command, properties, context.locals());
        if ("move".equals(command)
            && !(destination instanceof EventLocationRef.Relative)) {
            throw new EventRuntimeException("move destination은 relative(...) 위치여야 합니다.");
        }
        EventMovementGateway.OpenResult opened = Objects.requireNonNull(
            gateway.open(new EventMovementGateway.MovementRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId(),
                instruction.operationId(), subject, destination, options
            )),
            "movement gateway 결과"
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    private EventLocationRef destination(
        JsonElement expression,
        JsonElement anchorExpression,
        Map<String, JsonElement> locals
    ) {
        JsonObject call = object(expression, "teleport destination");
        if (!"call".equals(string(call, "kind"))) {
            EventLocationRef evaluated;
            try {
                evaluated = EventLocationRef.fromJson(evaluator.evaluate(expression, locals));
            } catch (IllegalArgumentException error) {
                throw new EventRuntimeException(
                    "teleport destination 변수가 location_ref가 아닙니다.", error
                );
            }
            if (anchorExpression == null) return evaluated;
            if (!(evaluated instanceof EventLocationRef.Resource resource)
                || resource.kind() == EventLocationRef.Resource.Kind.ANCHOR) {
                throw new EventRuntimeException(
                    "하위 anchor 속성은 콘텐츠 리소스 위치에만 사용할 수 있습니다."
                );
            }
            return new EventLocationRef.Resource(
                resource.kind(), resource.resourceId(),
                text(evaluator.evaluate(anchorExpression, locals), "anchor")
            );
        }
        JsonObject callee = object(required(call, "callee"), "location callee");
        if (!"name".equals(string(callee, "kind"))) {
            throw new EventRuntimeException("위치 생성 함수 이름이 필요합니다.");
        }
        String name = string(callee, "name");
        JsonArray arguments = array(call, "arguments");
        return switch (name) {
            case "relative" -> new EventLocationRef.Relative(
                coordinate(arguments, "x", locals),
                coordinate(arguments, "y", locals),
                coordinate(arguments, "z", locals)
            );
            case "position" -> new EventLocationRef.Position(
                resource(named(arguments, "dimension"), locals, "position.dimension"),
                coordinate(arguments, "x", locals),
                coordinate(arguments, "y", locals),
                coordinate(arguments, "z", locals),
                optionalNumber(arguments, "yaw", locals),
                optionalNumber(arguments, "pitch", locals)
            );
            case "anchor", "settlement", "route", "dimension", "space" ->
                new EventLocationRef.Resource(
                    EventLocationRef.Resource.Kind.valueOf(name.toUpperCase(Locale.ROOT)),
                    resource(singlePositional(arguments), locals, name),
                    anchorExpression == null ? null
                        : text(evaluator.evaluate(anchorExpression, locals), "anchor")
                );
            default -> throw new EventRuntimeException(
                "지원하지 않는 location_ref 생성 함수입니다: " + name
            );
        };
    }

    private EventMovementGateway.Options options(
        String command,
        Map<String, JsonElement> properties,
        Map<String, JsonElement> locals
    ) {
        EventMovementGateway.Mode mode = "move".equals(command)
            ? enumName(
                properties.get("mode"), locals,
                EventMovementGateway.Mode.WALK,
                EventMovementGateway.Mode.class,
                "mode"
            )
            : EventMovementGateway.Mode.TELEPORT;
        double speed = properties.containsKey("speed")
            ? number(evaluator.evaluate(properties.get("speed"), locals), "speed")
            : 0.9D;
        boolean lockInput = properties.containsKey("lock_input")
            ? bool(evaluator.evaluate(properties.get("lock_input"), locals), "lock_input")
            : "move".equals(command);
        EventMovementGateway.Collision collision = enumName(
            properties.get("collision"), locals,
            EventMovementGateway.Collision.STOP,
            EventMovementGateway.Collision.class,
            "collision"
        );
        EventMovementGateway.SafeLanding safeLanding = enumName(
            properties.get("safe_landing"), locals,
            EventMovementGateway.SafeLanding.REQUIRED,
            EventMovementGateway.SafeLanding.class,
            "safe_landing"
        );
        EventMovementGateway.Fade fade = enumName(
            properties.get("fade"), locals,
            EventMovementGateway.Fade.NONE,
            EventMovementGateway.Fade.class,
            "fade"
        );
        boolean preload = properties.containsKey("preload_chunks")
            ? bool(evaluator.evaluate(properties.get("preload_chunks"), locals), "preload_chunks")
            : true;
        return new EventMovementGateway.Options(
            mode, speed, lockInput, collision, safeLanding, preload, fade
        );
    }

    private static void rejectUnusedProperties(
        String command, Map<String, JsonElement> properties
    ) {
        for (String name : properties.keySet()) {
            boolean supported = "move".equals(command)
                ? name.equals("mode") || name.equals("speed")
                    || name.equals("lock_input") || name.equals("collision")
                    || name.equals("safe_landing") || name.equals("preload_chunks")
                : name.equals("anchor") || name.equals("safe_landing")
                    || name.equals("preload_chunks") || name.equals("fade");
            if (!supported) {
                throw new EventRuntimeException(
                    command + "에서 지원하지 않는 실행 속성입니다: " + name
                );
            }
        }
    }

    private EventMovementGateway.Subject subject(JsonElement expression) {
        JsonObject value = object(expression, "teleport subject");
        if (!"name".equals(string(value, "kind"))) {
            throw new EventRuntimeException("teleport subject는 player 또는 npc여야 합니다.");
        }
        return switch (string(value, "name")) {
            case "player" -> EventMovementGateway.Subject.PLAYER;
            case "npc" -> EventMovementGateway.Subject.NPC;
            default -> throw new EventRuntimeException("teleport subject는 player 또는 npc여야 합니다.");
        };
    }

    private double coordinate(
        JsonArray arguments, String name, Map<String, JsonElement> locals
    ) {
        JsonElement value = evaluator.evaluate(named(arguments, name), locals);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(name + " 좌표는 숫자여야 합니다.");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new EventRuntimeException(name + " 좌표는 유한한 숫자여야 합니다.");
        }
        return result;
    }

    private Float optionalNumber(
        JsonArray arguments, String name, Map<String, JsonElement> locals
    ) {
        JsonElement expression = optionalNamed(arguments, name);
        return expression == null ? null : (float) coordinateValue(expression, name, locals);
    }

    private double coordinateValue(
        JsonElement expression, String name, Map<String, JsonElement> locals
    ) {
        JsonElement value = evaluator.evaluate(expression, locals);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(name + " 좌표는 숫자여야 합니다.");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new EventRuntimeException(name + " 좌표는 유한한 숫자여야 합니다.");
        }
        return result;
    }

    private String resource(
        JsonElement expression, Map<String, JsonElement> locals, String name
    ) {
        String value = text(evaluator.evaluate(expression, locals), name);
        if (net.minecraft.resources.ResourceLocation.tryParse(value) == null) {
            throw new EventRuntimeException(name + "는 리소스 ID여야 합니다: " + value);
        }
        return value;
    }

    private static JsonElement singlePositional(JsonArray arguments) {
        if (arguments.size() != 1) {
            throw new EventRuntimeException("리소스 위치 함수에는 위치 인자 하나가 필요합니다.");
        }
        return positional(arguments.get(0));
    }

    private static JsonElement named(JsonArray arguments, String name) {
        JsonElement value = optionalNamed(arguments, name);
        if (value == null) throw new EventRuntimeException("위치 인자가 없습니다: " + name);
        return value;
    }

    private static JsonElement optionalNamed(JsonArray arguments, String name) {
        for (JsonElement element : arguments) {
            JsonObject argument = object(element, "location argument");
            JsonElement argumentName = required(argument, "name");
            if (!argumentName.isJsonNull() && name.equals(argumentName.getAsString())) {
                return required(argument, "value");
            }
        }
        return null;
    }

    private static JsonElement positional(JsonElement element) {
        JsonObject argument = object(element, "command argument");
        if (!required(argument, "name").isJsonNull()) {
            throw new EventRuntimeException("명령 위치 인자가 필요합니다.");
        }
        JsonElement value = required(argument, "value");
        if (value.isJsonNull()) throw new EventRuntimeException("명령 인자 값이 필요합니다.");
        return value;
    }

    private static Map<String, JsonElement> properties(JsonObject payload) {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        for (JsonElement element : array(payload, "properties")) {
            JsonObject property = object(element, "command property");
            String name = string(property, "name");
            if (result.putIfAbsent(name, required(property, "value")) != null) {
                throw new EventRuntimeException("중복 teleport 속성입니다: " + name);
            }
        }
        return Map.copyOf(result);
    }

    private <E extends Enum<E>> E enumName(
        JsonElement expression,
        Map<String, JsonElement> locals,
        E fallback,
        Class<E> type,
        String name
    ) {
        if (expression == null) return fallback;
        JsonObject encoded = object(expression, name);
        String raw;
        if ("name".equals(string(encoded, "kind"))) {
            raw = string(encoded, "name");
        } else {
            raw = text(evaluator.evaluate(expression, locals), name);
        }
        raw = raw.toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException error) {
            throw new EventRuntimeException("지원하지 않는 " + name + " 값입니다: " + raw, error);
        }
    }

    private static boolean bool(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new EventRuntimeException(name + "은 bool이어야 합니다.");
        }
        return value.getAsBoolean();
    }

    private static double number(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(name + "은 숫자여야 합니다.");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || result <= 0 || result > 20) {
            throw new EventRuntimeException(name + "은 0보다 크고 20 이하여야 합니다.");
        }
        return result;
    }

    private static String text(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException(name + "은 문자열이어야 합니다.");
        }
        return value.getAsString();
    }

    private static JsonElement required(JsonObject value, String name) {
        if (!value.has(name)) throw new EventRuntimeException("필드가 없습니다: " + name);
        return value.get(name);
    }

    private static String string(JsonObject value, String name) {
        return text(required(value, name), name);
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new EventRuntimeException(name + "은 객체여야 합니다.");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject value, String name) {
        JsonElement element = required(value, name);
        if (!element.isJsonArray()) throw new EventRuntimeException(name + "은 배열이어야 합니다.");
        return element.getAsJsonArray();
    }
}
