package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Objects;

/** Opens a numeric editor and suspends CVES until a validated integer is submitted. */
public final class NumberInputEventCommandAdapter implements EventCommandAdapter {
    private final EventNumberInputGateway gateway;
    private final EventExpressionEnvironment environment;
    private final EventCommandAdapter fallback;

    public NumberInputEventCommandAdapter(
        EventNumberInputGateway gateway,
        EventExpressionEnvironment environment,
        EventCommandAdapter fallback
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"number_input".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (!instruction.awaitsResult() || instruction.resumeAddress() == null
            || instruction.resultVariable() == null) {
            throw new EventRuntimeException("number_input requires await, resume, and a result variable");
        }
        InputSpec spec = spec(instruction, environment, context.locals());
        EventNumberInputGateway.OpenResult opened = gateway.open(
            new EventNumberInputGateway.NumberInputRequest(
                context.sessionKey(), context.sourceDigest(), instruction.instructionId(),
                spec.bounds().minimum(), spec.bounds().maximum(),
                spec.currentBalance(), spec.unitPrice()
            )
        );
        return new Waiting(opened.token(), opened.expiresAtEpochMilli());
    }

    public static Bounds bounds(
        EventScript.Instruction instruction,
        EventExpressionEnvironment environment,
        Map<String, JsonElement> locals
    ) {
        return spec(instruction, environment, locals).bounds();
    }

    public static InputSpec spec(
        EventScript.Instruction instruction,
        EventExpressionEnvironment environment,
        Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("properties");
        if (raw == null || !raw.isJsonArray()) {
            throw new EventRuntimeException("number_input properties must be an array");
        }
        EventExpressionEvaluator evaluator = new EventExpressionEvaluator(environment);
        Long minimum = null;
        Long maximum = null;
        Long currentBalance = null;
        Long unitPrice = null;
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new EventRuntimeException("number_input property must be an object");
            JsonObject property = element.getAsJsonObject();
            String name = property.get("name").getAsString();
            long value = evaluator.evaluateLong(property.get("value"), locals);
            if ("min".equals(name) && minimum == null) minimum = value;
            else if ("max".equals(name) && maximum == null) maximum = value;
            else if ("current".equals(name) && currentBalance == null) currentBalance = value;
            else if ("unit_price".equals(name) && unitPrice == null) unitPrice = value;
            else throw new EventRuntimeException("invalid or duplicate number_input property: " + name);
        }
        if (minimum == null || maximum == null || minimum > maximum) {
            throw new EventRuntimeException("number_input requires min <= max");
        }
        if ((currentBalance == null) != (unitPrice == null)
            || currentBalance != null && (currentBalance < 0 || unitPrice < 1)) {
            throw new EventRuntimeException(
                "number_input current and unit_price must be valid and provided together"
            );
        }
        return new InputSpec(new Bounds(minimum, maximum), currentBalance, unitPrice);
    }

    public record Bounds(long minimum, long maximum) {
        public boolean contains(long value) {
            return value >= minimum && value <= maximum;
        }
    }

    public record InputSpec(Bounds bounds, Long currentBalance, Long unitPrice) {}
}
