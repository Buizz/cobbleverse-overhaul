package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerPlayer;

/** Runs a trusted authored server command as the interacting player. */
public final class ServerCommandEventCommandAdapter implements EventCommandAdapter {
    private static final Pattern COMMAND = Pattern.compile("^[a-z0-9_:-]+(?: [a-z0-9_./:-]+)*$");
    private final ServerPlayer player;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public ServerCommandEventCommandAdapter(
        ServerPlayer player,
        EventExpressionEnvironment environment,
        EventCommandAdapter fallback
    ) {
        this.player = Objects.requireNonNull(player, "player");
        this.evaluator = new EventExpressionEvaluator(environment);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())
            || !"server_command".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (instruction.operationId() == null) {
            throw new EventRuntimeException("server_command requires a stable operation ID");
        }
        List<JsonElement> arguments = positional(instruction, context.locals());
        if (arguments.size() < 1 || arguments.size() > 2
            || !arguments.getFirst().isJsonPrimitive()
            || !arguments.getFirst().getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException("server_command requires a command and optional integer");
        }
        String command = arguments.getFirst().getAsString();
        if (!COMMAND.matcher(command).matches()) {
            throw new EventRuntimeException("server_command contains unsupported characters");
        }
        if (arguments.size() == 2) {
            int value;
            try {
                value = arguments.get(1).getAsBigDecimal().intValueExact();
            } catch (RuntimeException error) {
                throw new EventRuntimeException("server_command argument must be an integer", error);
            }
            command += " " + value;
        }
        AtomicBoolean success = new AtomicBoolean();
        player.server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack().withCallback(
                (accepted, result) -> success.set(accepted && result > 0)
            ),
            command
        );
        return new Completed(new JsonPrimitive(success.get()));
    }

    private List<JsonElement> positional(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("arguments");
        if (raw == null || !raw.isJsonArray()) throw new EventRuntimeException("server_command arguments must be an array");
        List<JsonElement> values = new ArrayList<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            JsonObject argument = element.getAsJsonObject();
            if (!argument.get("name").isJsonNull()) throw new EventRuntimeException("server_command only accepts positional arguments");
            values.add(evaluator.evaluate(argument.get("value"), locals));
        }
        return List.copyOf(values);
    }
}
