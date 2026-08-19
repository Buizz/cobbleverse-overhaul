package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Bridges the typed CVES encounter warning to the existing battle overlay. */
public final class EncounterWarningEventCommandAdapter implements EventCommandAdapter {
    private final ServerPlayer player;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public EncounterWarningEventCommandAdapter(
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
            || !"encounter_warning".equals(instruction.command())) {
            return fallback.start(context);
        }
        if (instruction.awaitsResult() || instruction.operationId() != null) {
            throw new EventRuntimeException(
                "encounter_warning은 await 또는 operation ID를 사용할 수 없습니다."
            );
        }
        JsonArray arguments = array(instruction.rawPayload(), "arguments");
        if (arguments.size() != 1) {
            throw new EventRuntimeException("encounter_warning에는 음악 트랙 하나가 필요합니다.");
        }
        JsonObject argument = object(arguments.get(0), "encounter_warning argument");
        if (!required(argument, "name").isJsonNull()) {
            throw new EventRuntimeException("encounter_warning 트랙은 위치 인자여야 합니다.");
        }
        JsonElement trackValue = evaluator.evaluate(
            required(argument, "value"), context.locals()
        );
        if (!trackValue.isJsonPrimitive()
            || !trackValue.getAsJsonPrimitive().isString()
            || !trackValue.getAsString().matches("[A-Za-z0-9._-]+")) {
            throw new EventRuntimeException("encounter_warning 트랙 형식이 올바르지 않습니다.");
        }
        String command = warningCommand(
            context.sessionKey().npcId(), trackValue.getAsString()
        );
        try {
            int result = player.getServer().getCommands().getDispatcher().execute(
                command,
                player.createCommandSourceStack().withPermission(4).withSuppressedOutput()
            );
            if (result <= 0) {
                throw new EventRuntimeException("조우 경고 오버레이 명령이 거부됐습니다.");
            }
        } catch (CommandSyntaxException error) {
            throw new EventRuntimeException("조우 경고 오버레이를 열지 못했습니다.", error);
        }
        return new Completed(null);
    }

    static String warningCommand(UUID npcId, String track) {
        return "cobbleventure_battle_warning @s " + npcId + " " + track;
    }

    private static JsonArray array(JsonObject value, String name) {
        JsonElement element = required(value, name);
        if (!element.isJsonArray()) {
            throw new EventRuntimeException(name + "은 배열이어야 합니다.");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            throw new EventRuntimeException(name + "은 객체여야 합니다.");
        }
        return value.getAsJsonObject();
    }

    private static JsonElement required(JsonObject value, String name) {
        if (!value.has(name)) {
            throw new EventRuntimeException("필드가 없습니다: " + name);
        }
        return value.get(name);
    }
}
