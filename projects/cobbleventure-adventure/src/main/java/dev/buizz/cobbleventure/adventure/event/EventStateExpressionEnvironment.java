package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Implements the Runtime IR V1 player, flag, money and level-cap expression contract. */
public final class EventStateExpressionEnvironment implements EventExpressionEnvironment {
    private final EventStateAccess state;

    public EventStateExpressionEnvironment(EventStateAccess state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public Optional<JsonElement> resolveName(String name) {
        if (!name.equals("player")) {
            return Optional.empty();
        }
        JsonObject player = new JsonObject();
        player.addProperty("name", state.playerName());
        return Optional.of(player);
    }

    @Override
    public JsonElement call(String function, List<Argument> arguments) {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(arguments, "arguments");
        return switch (function) {
            case "flag" -> new JsonPrimitive(state.flag(singleString(function, arguments)));
            case "has_item" -> new JsonPrimitive(state.hasItem(
                positionalString(function, arguments, 0, 2),
                positiveInt(function, arguments, 1, 2)
            ));
            case "money" -> {
                requireNoArguments(function, arguments);
                yield new JsonPrimitive(state.money());
            }
            case "casino_balance" -> {
                requireNoArguments(function, arguments);
                yield new JsonPrimitive(state.casinoBalance());
            }
            case "gacha_ticket_price" -> new JsonPrimitive(state.gachaTicketPrice(
                singleString(function, arguments)
            ));
            case "gacha_ticket_purchase_min" -> new JsonPrimitive(
                state.gachaTicketPurchaseMin(singleString(function, arguments))
            );
            case "gacha_ticket_purchase_max" -> new JsonPrimitive(
                state.gachaTicketPurchaseMax(singleString(function, arguments))
            );
            case "floor_div" -> new JsonPrimitive(floorDiv(function, arguments));
            case "min_int" -> new JsonPrimitive(minInt(function, arguments));
            case "level_cap" -> {
                requireNoArguments(function, arguments);
                yield new JsonPrimitive(state.levelCap());
            }
            default -> throw new EventRuntimeException("지원하지 않는 내장 함수입니다: " + function);
        };
    }

    EventStateAccess state() {
        return state;
    }

    private static String singleString(String function, List<Argument> arguments) {
        if (arguments.size() != 1 || arguments.getFirst().name() != null) {
            throw new EventRuntimeException(function + " 함수는 위치 문자열 인자 하나가 필요합니다.");
        }
        JsonElement value = arguments.getFirst().value();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException(function + " 함수 인자는 문자열이어야 합니다.");
        }
        return value.getAsString();
    }

    private static String positionalString(
        String function, List<Argument> arguments, int index, int size
    ) {
        requirePositionalArguments(function, arguments, size);
        JsonElement value = arguments.get(index).value();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException(function + " 함수 " + (index + 1) + "번째 인자는 문자열이어야 합니다.");
        }
        return value.getAsString();
    }

    private static int positiveInt(
        String function, List<Argument> arguments, int index, int size
    ) {
        requirePositionalArguments(function, arguments, size);
        JsonElement value = arguments.get(index).value();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(function + " 함수 " + (index + 1) + "번째 인자는 양의 정수여야 합니다.");
        }
        try {
            int result = value.getAsBigDecimal().intValueExact();
            if (result < 1) {
                throw new ArithmeticException();
            }
            return result;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new EventRuntimeException(function + " 함수 " + (index + 1) + "번째 인자는 양의 정수여야 합니다.", error);
        }
    }

    private static long floorDiv(String function, List<Argument> arguments) {
        requirePositionalArguments(function, arguments, 2);
        long dividend = exactLong(function, arguments.get(0).value());
        long divisor = exactLong(function, arguments.get(1).value());
        if (divisor == 0) throw new EventRuntimeException("floor_div 함수의 제수는 0일 수 없습니다.");
        return Math.floorDiv(dividend, divisor);
    }

    private static long minInt(String function, List<Argument> arguments) {
        requirePositionalArguments(function, arguments, 2);
        return Math.min(
            exactLong(function, arguments.get(0).value()),
            exactLong(function, arguments.get(1).value())
        );
    }

    private static long exactLong(String function, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isNumber()) {
            throw new EventRuntimeException(function + " 함수 인자는 정수여야 합니다.");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new EventRuntimeException(function + " 함수 인자는 정수여야 합니다.", error);
        }
    }

    private static void requirePositionalArguments(
        String function, List<Argument> arguments, int size
    ) {
        if (arguments.size() != size || arguments.stream().anyMatch(value -> value.name() != null)) {
            throw new EventRuntimeException(function + " 함수는 위치 인자 " + size + "개가 필요합니다.");
        }
    }

    private static void requireNoArguments(String function, List<Argument> arguments) {
        if (!arguments.isEmpty()) {
            throw new EventRuntimeException(function + " 함수는 인자를 받지 않습니다.");
        }
    }

}
