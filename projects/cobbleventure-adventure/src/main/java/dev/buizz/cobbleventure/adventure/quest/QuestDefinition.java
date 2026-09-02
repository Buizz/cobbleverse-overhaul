package dev.buizz.cobbleventure.adventure.quest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Immutable server view of one validated web-authored quest. */
public record QuestDefinition(
    String id,
    boolean enabled,
    Category category,
    String displayName,
    String summary,
    ConditionGroup acceptConditions,
    GlobalActivation globalActivation,
    List<Objective> objectives,
    CompletionMode completionMode,
    EventHook onAccept,
    EventHook onComplete
) {
    public enum Category { MAIN, SIDE, TUTORIAL }
    public enum CompletionMode { NPC_TURN_IN, AUTOMATIC }

    public QuestDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(acceptConditions, "acceptConditions");
        Objects.requireNonNull(globalActivation, "globalActivation");
        objectives = List.copyOf(objectives);
        Objects.requireNonNull(completionMode, "completionMode");
    }

    public static QuestDefinition parse(String expectedId, JsonObject root) {
        if (requiredInt(root, "schema_version") != 1) {
            throw new IllegalArgumentException("지원하지 않는 퀘스트 스키마 버전입니다.");
        }
        String id = requiredString(root, "id");
        if (ResourceLocation.tryParse(id) == null || !id.equals(expectedId)) {
            throw new IllegalArgumentException("퀘스트 ID가 리소스 경로와 일치하지 않습니다: " + id);
        }
        JsonArray objectiveValues = requiredArray(root, "objectives");
        List<Objective> objectives = new ArrayList<>();
        for (JsonElement value : objectiveValues) {
            JsonObject objective = value.getAsJsonObject();
            String objectiveId = requiredString(objective, "id");
            objectives.add(new Objective(
                objectiveId,
                optionalLocalizedText(objective, "text", objectiveId),
                ConditionGroup.parse(requiredObject(objective, "conditions")),
                EventHook.optional(objective, "on_complete")
            ));
        }
        if (objectives.isEmpty()) {
            throw new IllegalArgumentException("퀘스트 목표가 비어 있습니다: " + id);
        }
        String completion = requiredString(requiredObject(root, "completion"), "mode");
        CompletionMode completionMode = switch (completion) {
            case "npc_turn_in" -> CompletionMode.NPC_TURN_IN;
            case "automatic" -> CompletionMode.AUTOMATIC;
            default -> throw new IllegalArgumentException("지원하지 않는 완료 방식입니다: " + completion);
        };
        Category category = switch (requiredString(root, "category")) {
            case "main" -> Category.MAIN;
            case "side" -> Category.SIDE;
            case "tutorial" -> Category.TUTORIAL;
            default -> throw new IllegalArgumentException("지원하지 않는 퀘스트 종류입니다.");
        };
        GlobalActivation globalActivation = root.has("global_activation")
            ? GlobalActivation.parse(requiredObject(root, "global_activation"))
            : GlobalActivation.disabled();
        if (globalActivation.enabled() && category != Category.MAIN) {
            throw new IllegalArgumentException("전역 발동은 메인 퀘스트에만 사용할 수 있습니다: " + id);
        }
        String displayName = localizedText(requiredObject(root, "display_name"));
        JsonObject hooks = root.has("event_hooks") ? requiredObject(root, "event_hooks") : new JsonObject();
        if (!java.util.Set.of("on_accept", "on_complete").containsAll(hooks.keySet()))
            throw new IllegalArgumentException("지원하지 않는 퀘스트 훅 종류입니다.");
        return new QuestDefinition(
            id,
            root.has("enabled") && root.get("enabled").getAsBoolean(),
            category,
            displayName,
            optionalLocalizedText(root, "summary", displayName),
            ConditionGroup.parse(requiredObject(root, "accept_conditions")),
            globalActivation,
            objectives,
            completionMode,
            EventHook.optional(hooks, "on_accept"),
            EventHook.optional(hooks, "on_complete")
        );
    }

    public record GlobalActivation(boolean enabled, ConditionGroup conditions) {
        public GlobalActivation {
            Objects.requireNonNull(conditions, "conditions");
            if (enabled && conditions.conditions().isEmpty()) {
                throw new IllegalArgumentException("전역 발동 조건이 하나 이상 필요합니다.");
            }
        }

        static GlobalActivation disabled() {
            return new GlobalActivation(false, new ConditionGroup("all", List.of()));
        }

        static GlobalActivation parse(JsonObject root) {
            if (!root.has("enabled") || !root.get("enabled").isJsonPrimitive()) {
                throw new IllegalArgumentException("전역 발동 enabled 필드가 필요합니다.");
            }
            return new GlobalActivation(
                root.get("enabled").getAsBoolean(),
                ConditionGroup.parse(requiredObject(root, "conditions"))
            );
        }
    }

    public record EventHook(String scriptId, String npcId) {
        public EventHook {
            if (scriptId == null || !scriptId.matches("[a-z0-9_.-]+:event_script/[a-z0-9_./-]+")
                || npcId == null || !npcId.matches("[a-z0-9_.-]+:npc/[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("퀘스트 훅에는 V5 이벤트 ID와 NPC ID가 필요합니다.");
            }
            for (String id : List.of(scriptId, npcId)) {
                for (String part : id.split("/", -1)) {
                    if (part.isEmpty() || part.equals(".") || part.equals(".."))
                        throw new IllegalArgumentException("퀘스트 훅의 리소스 경로가 올바르지 않습니다.");
                }
            }
        }
        static EventHook optional(JsonObject root, String field) {
            if (!root.has(field)) return null;
            JsonObject value = requiredObject(root, field);
            if (!value.keySet().equals(java.util.Set.of("script_id", "npc_id")))
                throw new IllegalArgumentException("퀘스트 훅 필드가 올바르지 않습니다.");
            return new EventHook(requiredString(value, "script_id"), requiredString(value, "npc_id"));
        }
    }

    public record Objective(String id, String text, ConditionGroup conditions, EventHook onComplete) {
        public Objective {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(conditions, "conditions");
        }
    }

    public record ConditionGroup(String mode, List<JsonObject> conditions) {
        public ConditionGroup {
            if (!mode.equals("all") && !mode.equals("any")) {
                throw new IllegalArgumentException("조건 방식은 all 또는 any여야 합니다: " + mode);
            }
            conditions = conditions.stream().map(JsonObject::deepCopy).toList();
        }

        @Override public List<JsonObject> conditions() {
            return conditions.stream().map(JsonObject::deepCopy).toList();
        }

        static ConditionGroup parse(JsonObject root) {
            JsonArray values = requiredArray(root, "conditions");
            List<JsonObject> conditions = new ArrayList<>();
            for (JsonElement value : values) conditions.add(value.getAsJsonObject().deepCopy());
            return new ConditionGroup(requiredString(root, "condition_mode"), conditions);
        }
    }

    private static JsonObject requiredObject(JsonObject root, String field) {
        if (!root.has(field) || !root.get(field).isJsonObject()) {
            throw new IllegalArgumentException("퀘스트 객체 필드가 필요합니다: " + field);
        }
        return root.getAsJsonObject(field);
    }

    private static JsonArray requiredArray(JsonObject root, String field) {
        if (!root.has(field) || !root.get(field).isJsonArray()) {
            throw new IllegalArgumentException("퀘스트 배열 필드가 필요합니다: " + field);
        }
        return root.getAsJsonArray(field);
    }

    private static String requiredString(JsonObject root, String field) {
        if (!root.has(field) || !root.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("퀘스트 문자열 필드가 필요합니다: " + field);
        }
        return root.get(field).getAsString();
    }

    private static int requiredInt(JsonObject root, String field) {
        if (!root.has(field) || !root.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("퀘스트 정수 필드가 필요합니다: " + field);
        }
        return root.get(field).getAsInt();
    }

    private static String localizedText(JsonObject root) {
        if (root.has("ko_kr") && root.get("ko_kr").isJsonPrimitive()) {
            return root.get("ko_kr").getAsString();
        }
        if (root.has("en_us") && root.get("en_us").isJsonPrimitive()) {
            return root.get("en_us").getAsString();
        }
        return root.entrySet().stream().filter(entry -> entry.getValue().isJsonPrimitive())
            .map(entry -> entry.getValue().getAsString()).findFirst().orElse("메인 퀘스트");
    }

    private static String optionalLocalizedText(
        JsonObject root, String field, String fallback
    ) {
        if (!root.has(field) || !root.get(field).isJsonObject()) return fallback;
        return localizedText(root.getAsJsonObject(field));
    }
}
