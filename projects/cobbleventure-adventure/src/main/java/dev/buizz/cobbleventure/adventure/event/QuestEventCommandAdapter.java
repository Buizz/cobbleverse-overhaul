package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.adventure.quest.QuestService;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Connects the three V5 quest commands to the shared server quest state. */
public final class QuestEventCommandAdapter implements EventCommandAdapter {
    private final ServerPlayer player;
    private final EventExpressionEvaluator evaluator;
    private final EventCommandAdapter fallback;

    public QuestEventCommandAdapter(
        ServerPlayer player,
        EventExpressionEnvironment environment,
        EventCommandAdapter fallback
    ) {
        this.player = Objects.requireNonNull(player, "player");
        this.evaluator = new EventExpressionEvaluator(environment);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override public StartResult start(CommandContext context) {
        EventScript.Instruction instruction = context.instruction();
        if (!"command".equals(instruction.operation())) return fallback.start(context);
        String command = instruction.command();
        if (!command.equals("quest_grant") && !command.equals("quest_check")
            && !command.equals("quest_complete")) {
            return fallback.start(context);
        }
        String questId = questId(instruction, context.locals());
        QuestService.Result result = switch (command) {
            case "quest_grant" -> QuestService.grant(player, questId);
            case "quest_check" -> QuestService.check(player, questId);
            default -> QuestService.complete(player, questId);
        };
        return new Completed(result.toJson());
    }

    private String questId(
        EventScript.Instruction instruction, Map<String, JsonElement> locals
    ) {
        JsonElement raw = instruction.rawPayload().get("arguments");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 1) {
            throw new EventRuntimeException(instruction.command() + "에는 퀘스트 ID 하나가 필요합니다.");
        }
        JsonObject argument = raw.getAsJsonArray().get(0).getAsJsonObject();
        if (argument.has("name") && !argument.get("name").isJsonNull()) {
            throw new EventRuntimeException("퀘스트 명령은 위치 인자를 사용해야 합니다.");
        }
        JsonElement value = evaluator.evaluate(argument.get("value"), locals);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new EventRuntimeException("퀘스트 ID는 문자열이어야 합니다.");
        }
        return value.getAsString();
    }
}
