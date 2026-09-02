package dev.buizz.cobbleventure.adventure.quest;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One player's durable, insertion-ordered transition journal. No process-global state. */
public final class QuestHookJournal {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }
    public record Entry(String key, QuestDefinition.EventHook hook, Status status, String npcUuid, String detail) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public boolean enqueue(String key, QuestDefinition.EventHook hook) {
        if (hook == null || entries.containsKey(key)) return false;
        entries.put(key, new Entry(key, hook, Status.PENDING, "", ""));
        return true;
    }
    public List<Entry> entries() { return List.copyOf(entries.values()); }
    public void update(String key, Status status, String npcUuid, String detail) {
        Entry entry = entries.get(key);
        if (entry == null) throw new IllegalArgumentException("Unknown quest hook: " + key);
        entries.put(key, new Entry(key, entry.hook(), status, npcUuid, detail));
    }
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        entries.forEach((key, entry) -> {
            JsonObject value = new JsonObject();
            value.addProperty("script_id", entry.hook().scriptId());
            value.addProperty("npc_id", entry.hook().npcId());
            value.addProperty("status", entry.status().name());
            value.addProperty("npc_uuid", entry.npcUuid());
            value.addProperty("detail", entry.detail());
            root.add(key, value);
        });
        return root;
    }
    public static QuestHookJournal fromJson(JsonObject root) {
        QuestHookJournal journal = new QuestHookJournal();
        root.entrySet().forEach(item -> {
            JsonObject value = item.getValue().getAsJsonObject();
            journal.enqueue(item.getKey(), new QuestDefinition.EventHook(
                value.get("script_id").getAsString(), value.get("npc_id").getAsString()));
            journal.update(item.getKey(), Status.valueOf(value.get("status").getAsString()),
                value.get("npc_uuid").getAsString(), value.get("detail").getAsString());
        });
        return journal;
    }
}
