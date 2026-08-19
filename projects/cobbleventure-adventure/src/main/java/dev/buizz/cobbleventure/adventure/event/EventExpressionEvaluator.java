package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Evaluates the closed Runtime IR V1 expression union without executing arbitrary code. */
public final class EventExpressionEvaluator {
    private final EventExpressionEnvironment environment;

    public EventExpressionEvaluator(EventExpressionEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public JsonElement evaluate(JsonElement expression, Map<String, JsonElement> locals) {
        JsonObject value = object(expression, "expression");
        String kind = string(value, "kind");
        return switch (kind) {
            case "literal" -> required(value, "value").deepCopy();
            case "name" -> name(string(value, "name"), locals);
            case "member" -> environment.member(
                evaluate(required(value, "target"), locals), string(value, "member")
            );
            case "call" -> call(value, locals);
            case "unary" -> unary(
                string(value, "operator"),
                evaluate(required(value, "operand"), locals)
            );
            case "binary" -> binary(value, locals);
            default -> throw new EventRuntimeException("알 수 없는 expression kind입니다: " + kind);
        };
    }

    public boolean evaluateBoolean(JsonElement expression, Map<String, JsonElement> locals) {
        return bool(evaluate(expression, locals));
    }

    public int evaluateInt(JsonElement expression, Map<String, JsonElement> locals) {
        BigDecimal value = number(evaluate(expression, locals));
        try {
            return value.intValueExact();
        } catch (ArithmeticException error) {
            throw new EventRuntimeException("int 범위를 벗어나거나 정수가 아닌 값입니다: " + value, error);
        }
    }

    private JsonElement name(String name, Map<String, JsonElement> locals) {
        JsonElement local = locals.get(name);
        if (local != null) {
            return local.deepCopy();
        }
        return environment.resolveName(name)
            .map(JsonElement::deepCopy)
            .orElseThrow(() -> new EventRuntimeException("정의되지 않은 런타임 변수입니다: " + name));
    }

    private JsonElement call(JsonObject expression, Map<String, JsonElement> locals) {
        JsonObject callee = object(required(expression, "callee"), "callee");
        if (!"name".equals(string(callee, "kind"))) {
            throw new EventRuntimeException("호출 대상은 내장 함수 이름이어야 합니다.");
        }
        String function = string(callee, "name");
        JsonArray arguments = array(expression, "arguments");
        List<EventExpressionEnvironment.Argument> values = new ArrayList<>();
        for (JsonElement element : arguments) {
            JsonObject argument = object(element, "argument");
            String argumentName = nullableString(argument, "name");
            JsonElement argumentValue = required(argument, "value");
            if (argumentValue.isJsonNull()) {
                throw new EventRuntimeException("함수 인자 값은 null일 수 없습니다: " + function);
            }
            values.add(new EventExpressionEnvironment.Argument(
                argumentName, evaluate(argumentValue, locals)
            ));
        }
        JsonElement result = environment.call(function, List.copyOf(values));
        if (result == null) {
            throw new EventRuntimeException("내장 함수가 null을 반환했습니다: " + function);
        }
        return result.deepCopy();
    }

    private JsonElement unary(String operator, JsonElement operand) {
        return switch (operator) {
            case "!" -> new JsonPrimitive(!bool(operand));
            case "-" -> numeric(number(operand).negate(), integral(operand));
            default -> throw new EventRuntimeException("지원하지 않는 단항 연산자입니다: " + operator);
        };
    }

    private JsonElement binary(JsonObject expression, Map<String, JsonElement> locals) {
        String operator = string(expression, "operator");
        JsonElement left = evaluate(required(expression, "left"), locals);
        if (operator.equals("&&") && !bool(left)) {
            return new JsonPrimitive(false);
        }
        if (operator.equals("||") && bool(left)) {
            return new JsonPrimitive(true);
        }
        JsonElement right = evaluate(required(expression, "right"), locals);
        return switch (operator) {
            case "&&" -> new JsonPrimitive(bool(right));
            case "||" -> new JsonPrimitive(bool(right));
            case "+" -> numeric(number(left).add(number(right)), integral(left) && integral(right));
            case "-" -> numeric(number(left).subtract(number(right)), integral(left) && integral(right));
            case "*" -> numeric(number(left).multiply(number(right)), integral(left) && integral(right));
            case "/" -> divide(number(left), number(right));
            case "%" -> remainder(left, right);
            case "<" -> new JsonPrimitive(number(left).compareTo(number(right)) < 0);
            case "<=" -> new JsonPrimitive(number(left).compareTo(number(right)) <= 0);
            case ">" -> new JsonPrimitive(number(left).compareTo(number(right)) > 0);
            case ">=" -> new JsonPrimitive(number(left).compareTo(number(right)) >= 0);
            case "==" -> new JsonPrimitive(equal(left, right));
            case "!=" -> new JsonPrimitive(!equal(left, right));
            default -> throw new EventRuntimeException("지원하지 않는 이항 연산자입니다: " + operator);
        };
    }

    private JsonElement divide(BigDecimal left, BigDecimal right) {
        if (right.signum() == 0) {
            throw new EventRuntimeException("0으로 나눌 수 없습니다.");
        }
        return new JsonPrimitive(left.divide(right, MathContext.DECIMAL128).stripTrailingZeros());
    }

    private JsonElement remainder(JsonElement left, JsonElement right) {
        BigDecimal divisor = number(right);
        if (divisor.signum() == 0) {
            throw new EventRuntimeException("0으로 나머지 연산을 할 수 없습니다.");
        }
        return numeric(number(left).remainder(divisor), integral(left) && integral(right));
    }

    private boolean equal(JsonElement left, JsonElement right) {
        if (isNumber(left) && isNumber(right)) {
            return number(left).compareTo(number(right)) == 0;
        }
        return left.equals(right);
    }

    private static boolean bool(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new EventRuntimeException("bool 값이 필요합니다: " + value);
        }
        return value.getAsBoolean();
    }

    private static BigDecimal number(JsonElement value) {
        if (!isNumber(value)) {
            throw new EventRuntimeException("숫자 값이 필요합니다: " + value);
        }
        try {
            return value.getAsBigDecimal();
        } catch (NumberFormatException error) {
            throw new EventRuntimeException("올바르지 않은 숫자입니다: " + value, error);
        }
    }

    private static boolean isNumber(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static boolean integral(JsonElement value) {
        return isNumber(value) && number(value).stripTrailingZeros().scale() <= 0;
    }

    private static JsonElement numeric(BigDecimal value, boolean integral) {
        try {
            return integral
                ? new JsonPrimitive(value.longValueExact())
                : new JsonPrimitive(value.stripTrailingZeros());
        } catch (ArithmeticException error) {
            throw new EventRuntimeException("정수 범위를 벗어났습니다: " + value, error);
        }
    }

    private static JsonElement required(JsonObject object, String name) {
        if (!object.has(name)) {
            throw new EventRuntimeException("expression 필드가 없습니다: " + name);
        }
        return object.get(name);
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException("문자열 필드가 필요합니다: " + name);
        }
        return value.getAsString();
    }

    private static String nullableString(JsonObject object, String name) {
        JsonElement value = required(object, name);
        return value.isJsonNull() ? null : value.getAsString();
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new EventRuntimeException(label + "은 객체여야 합니다.");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonArray()) {
            throw new EventRuntimeException(name + "은 배열이어야 합니다.");
        }
        return value.getAsJsonArray();
    }
}
