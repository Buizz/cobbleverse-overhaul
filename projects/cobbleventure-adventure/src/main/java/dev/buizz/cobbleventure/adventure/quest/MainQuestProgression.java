package dev.buizz.cobbleventure.adventure.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Ordered authored-NPC steps that take priority over the default Gym objective. */
public record MainQuestProgression(boolean enabled, List<Step> steps) {
    public MainQuestProgression {
        steps = List.copyOf(steps);
    }

    static MainQuestProgression disabled() {
        return new MainQuestProgression(false, List.of());
    }

    static MainQuestProgression parse(JsonObject root) {
        if (!root.has("schema_version") || root.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("지원하지 않는 메인 퀘스트 진행 스키마입니다.");
        }
        if (!root.has("enabled") || !root.get("enabled").isJsonPrimitive()) {
            throw new IllegalArgumentException("메인 퀘스트 진행 enabled 필드가 필요합니다.");
        }
        if (!root.has("steps") || !root.get("steps").isJsonArray()) {
            throw new IllegalArgumentException("메인 퀘스트 진행 steps 배열이 필요합니다.");
        }
        List<Step> steps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> quests = new HashSet<>();
        for (JsonElement value : root.getAsJsonArray("steps")) {
            JsonObject step = value.getAsJsonObject();
            String id = requiredString(step, "id");
            String quest = requiredResource(step, "quest", "quest/");
            String npc = requiredResource(step, "npc", "npc/");
            if (!ids.add(id)) throw new IllegalArgumentException("중복 진행 단계 ID입니다: " + id);
            if (!quests.add(quest)) throw new IllegalArgumentException("중복 메인 퀘스트입니다: " + quest);
            steps.add(new Step(id, quest, npc));
        }
        return new MainQuestProgression(root.get("enabled").getAsBoolean(), steps);
    }

    public record Step(String id, String questId, String npcId) {}

    private static String requiredString(JsonObject root, String field) {
        if (!root.has(field) || !root.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("메인 퀘스트 진행 문자열 필드가 필요합니다: " + field);
        }
        String value = root.get(field).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException("빈 진행 필드입니다: " + field);
        return value;
    }

    private static String requiredResource(JsonObject root, String field, String prefix) {
        String value = requiredString(root, field);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.getPath().startsWith(prefix)) {
            throw new IllegalArgumentException("잘못된 " + field + " 리소스 ID입니다: " + value);
        }
        return value;
    }
}
