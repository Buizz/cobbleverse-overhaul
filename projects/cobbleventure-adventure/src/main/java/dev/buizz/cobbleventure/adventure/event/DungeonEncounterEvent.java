package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Runs data-owned dungeon dialogue through one reusable CVES battle program. */
public final class DungeonEncounterEvent {
    public static final String SCRIPT_ID =
        "cobbleventure:event_script/system/dungeon_encounter";
    private static final String SOURCE_DIGEST =
        "686cf61c70a7867df0f03b73be0b8410ccfba1a8f72da94067b65a62b825f321";
    private static final EventScript SCRIPT = createScript();

    private DungeonEncounterEvent() {}

    public static boolean start(
        ServerPlayer player,
        Entity npc,
        String triggerInstance,
        String battleId,
        String startText,
        String winText,
        String lossText
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(npc, "npc");
        EventScriptRepository.instance().installRuntime(SCRIPT);
        EventNpcBinding binding = new EventNpcBinding(
            "runtime:dungeon_encounter", "runtime:dungeon_encounter", SCRIPT_ID
        );
        return EventTriggerExecutor.execute(
            player, npc, binding, SCRIPT, SCRIPT.events().getFirst(), triggerInstance,
            Map.of(
                "dungeon_battle_id", new JsonPrimitive(battleId),
                "dungeon_start_text", new JsonPrimitive(startText),
                "dungeon_win_text", new JsonPrimitive(winText),
                "dungeon_loss_text", new JsonPrimitive(lossText)
            )
        );
    }

    static EventScript script() {
        return SCRIPT;
    }

    private static EventScript createScript() {
        List<EventScript.Instruction> instructions = List.of(
            instruction(0, "dungeon/start", "say", dialogue("dungeon_start_text", 1)),
            instruction(1, "dungeon/battle", "command", battle(2)),
            instruction(2, "dungeon/result", "branch", resultBranch()),
            instruction(3, "dungeon/win", "say", dialogue("dungeon_win_text", 4)),
            instruction(4, "dungeon/win_end", "page_end", new JsonObject()),
            instruction(5, "dungeon/loss", "say", dialogue("dungeon_loss_text", 6)),
            instruction(6, "dungeon/loss_end", "page_end", new JsonObject())
        );
        EventScript.Event event = new EventScript.Event(
            0,
            new EventScript.Trigger("dungeon_encounter", new JsonObject()),
            List.of(new EventScript.Page(0, null, 0)),
            instructions
        );
        return new EventScript(1, SCRIPT_ID, SOURCE_DIGEST, List.of(event));
    }

    private static EventScript.Instruction instruction(
        int address, String id, String operation, JsonObject payload
    ) {
        return new EventScript.Instruction(address, id, operation, payload);
    }

    private static JsonObject dialogue(String localName, int next) {
        JsonObject payload = new JsonObject();
        payload.addProperty("speaker", "npc");
        JsonObject text = new JsonObject();
        text.addProperty("kind", "literal");
        text.addProperty("value", "${" + localName + "}");
        payload.add("text", text);
        payload.addProperty("next", next);
        payload.addProperty("await", true);
        payload.addProperty("resume", next);
        return payload;
    }

    private static JsonObject battle(int next) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", "battle");
        JsonArray arguments = new JsonArray();
        JsonObject argument = new JsonObject();
        argument.add("name", JsonNull.INSTANCE);
        JsonObject value = new JsonObject();
        value.addProperty("kind", "name");
        value.addProperty("name", "dungeon_battle_id");
        argument.add("value", value);
        arguments.add(argument);
        payload.add("arguments", arguments);
        payload.add("properties", new JsonArray());
        payload.addProperty("await", true);
        payload.addProperty("await_explicit", true);
        payload.addProperty("result", "battle_result");
        payload.addProperty("operation_id", SCRIPT_ID + "/battle");
        payload.addProperty("next", next);
        payload.addProperty("resume", next);
        return payload;
    }

    private static JsonObject resultBranch() {
        JsonObject payload = new JsonObject();
        JsonObject condition = new JsonObject();
        condition.addProperty("kind", "binary");
        condition.addProperty("operator", "==");
        JsonObject left = new JsonObject();
        left.addProperty("kind", "member");
        JsonObject target = new JsonObject();
        target.addProperty("kind", "name");
        target.addProperty("name", "battle_result");
        left.add("target", target);
        left.addProperty("member", "outcome");
        JsonObject right = new JsonObject();
        right.addProperty("kind", "literal");
        right.addProperty("type", "string");
        right.addProperty("value", "win");
        condition.add("left", left);
        condition.add("right", right);
        payload.add("condition", condition);
        payload.addProperty("then", 3);
        payload.addProperty("else", 5);
        return payload;
    }
}
