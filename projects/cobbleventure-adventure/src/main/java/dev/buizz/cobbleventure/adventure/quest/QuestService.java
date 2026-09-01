package dev.buizz.cobbleventure.adventure.quest;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Server-owned player quest state shared by every NPC and dimension. */
public final class QuestService {
    private static final String PROGRESS_KEY = "cobbleventureQuestProgress";
    private static final String STATE_KEY = "state";
    private static final String ACCEPTED_AT_KEY = "acceptedAt";
    private static final String COMPLETED_AT_KEY = "completedAt";

    public enum State {
        NOT_STARTED("not_started"), ACTIVE("active"), READY("ready"), COMPLETED("completed");
        private final String token;
        State(String token) { this.token = token; }
        public String token() { return token; }
        static State parse(String value) {
            for (State state : values()) if (state.token.equals(value)) return state;
            return NOT_STARTED;
        }
    }

    public record Result(
        State state, boolean granted, boolean ready, boolean completed,
        String failureReason
    ) {
        public JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("state", state.token());
            value.addProperty("granted", granted);
            value.addProperty("ready", ready);
            value.addProperty("completed", completed);
            value.addProperty("failure_reason", failureReason);
            return value;
        }
    }

    /** Read-only primary authored objective consumed by world and HUD integrations. */
    public record PrimaryMainQuest(
        String questId,
        String npcId,
        String displayName,
        String summary,
        String objectiveText,
        String state
    ) {}

    /** Read-only entry used by player-facing quest log integrations. */
    public record QuestLogEntry(
        String questId,
        String category,
        String displayName,
        String summary,
        String state,
        String completionMode,
        String target,
        List<QuestObjectiveProgress> objectives
    ) {
        public QuestLogEntry {
            objectives = List.copyOf(objectives);
        }
    }

    public record QuestObjectiveProgress(String id, String text, boolean completed) {}

    private QuestService() {}

    public static State state(ServerPlayer player, String questId) {
        CompoundTag progress = persisted(player).getCompound(PROGRESS_KEY);
        if (!progress.contains(questId, Tag.TAG_COMPOUND)) return State.NOT_STARTED;
        return State.parse(progress.getCompound(questId).getString(STATE_KEY));
    }

    public static Result grant(ServerPlayer player, String questId) {
        QuestDefinition definition = QuestDefinitionRepository.instance().find(questId).orElse(null);
        if (definition == null) return failure(State.NOT_STARTED, "unknown_quest");
        if (!definition.enabled()) return failure(State.NOT_STARTED, "disabled");
        State current = state(player, questId);
        if (current != State.NOT_STARTED) return result(current, false, "");
        if (!matches(player, definition.acceptConditions())) {
            return failure(State.NOT_STARTED, "accept_conditions_not_met");
        }
        writeState(player, questId, State.ACTIVE, ACCEPTED_AT_KEY);
        return new Result(State.ACTIVE, true, false, false, "");
    }

    public static Result check(ServerPlayer player, String questId) {
        QuestDefinition definition = QuestDefinitionRepository.instance().find(questId).orElse(null);
        if (definition == null) return failure(State.NOT_STARTED, "unknown_quest");
        State current = state(player, questId);
        if (current == State.NOT_STARTED) return failure(current, "not_started");
        if (current == State.COMPLETED) return result(current, false, "");
        boolean ready = definition.objectives().stream().allMatch(
            objective -> matches(player, objective.conditions())
        );
        State next = ready ? State.READY : State.ACTIVE;
        if (next != current) writeState(player, questId, next, null);
        if (ready && definition.completionMode() == QuestDefinition.CompletionMode.AUTOMATIC) {
            return complete(player, questId);
        }
        return result(next, false, "");
    }

    public static Result complete(ServerPlayer player, String questId) {
        QuestDefinition definition = QuestDefinitionRepository.instance().find(questId).orElse(null);
        if (definition == null) return failure(State.NOT_STARTED, "unknown_quest");
        State current = state(player, questId);
        if (current == State.COMPLETED) return result(current, false, "");
        if (current == State.NOT_STARTED) return failure(current, "not_started");
        boolean ready = definition.objectives().stream().allMatch(
            objective -> matches(player, objective.conditions())
        );
        if (!ready) {
            writeState(player, questId, State.ACTIVE, null);
            return failure(State.ACTIVE, "conditions_not_met");
        }
        writeState(player, questId, State.COMPLETED, COMPLETED_AT_KEY);
        return new Result(State.COMPLETED, false, true, true, "");
    }

    /** Re-evaluates active quests so conditions changed anywhere in the world are reflected. */
    static void refreshActive(ServerPlayer player) {
        for (String questId : QuestDefinitionRepository.instance().definitions().keySet()) {
            State current = state(player, questId);
            if (current == State.ACTIVE || current == State.READY) check(player, questId);
        }
    }

    /** Grants globally activated main quests once their player conditions become true. */
    static void refreshGlobalActivations(ServerPlayer player) {
        MainQuestProgression progression = MainQuestProgressionRepository.progression();
        Set<String> orderedQuests = new HashSet<>();
        progression.steps().forEach(step -> orderedQuests.add(step.questId()));
        String currentOrderedQuest = currentProgressionStep(player)
            .map(MainQuestProgression.Step::questId).orElse(null);
        for (QuestDefinition definition : QuestDefinitionRepository.instance().definitions().values()) {
            if (!definition.enabled()
                || definition.category() != QuestDefinition.Category.MAIN
                || !definition.globalActivation().enabled()
                || state(player, definition.id()) != State.NOT_STARTED) {
                continue;
            }
            if (orderedQuests.contains(definition.id())
                && !definition.id().equals(currentOrderedQuest)) {
                continue;
            }
            if (matches(player, definition.globalActivation().conditions())) {
                grant(player, definition.id());
            }
        }
    }

    /** Returns the active authored NPC step; callers fall back to the next Gym when empty. */
    public static Optional<PrimaryMainQuest> primaryMainQuest(ServerPlayer player) {
        Optional<MainQuestProgression.Step> current = currentProgressionStep(player);
        if (current.isEmpty()) return Optional.empty();
        MainQuestProgression.Step step = current.get();
        State questState = state(player, step.questId());
        if (questState != State.ACTIVE && questState != State.READY) return Optional.empty();
        return QuestDefinitionRepository.instance().find(step.questId())
            .filter(QuestDefinition::enabled)
            .filter(definition -> definition.category() == QuestDefinition.Category.MAIN)
            .map(definition -> new PrimaryMainQuest(
                definition.id(),
                step.npcId(),
                definition.displayName(),
                definition.summary(),
                currentObjectiveText(player, definition),
                questState.token()
            ));
    }

    /** Returns every accepted or completed quest, ordered for the in-game quest log. */
    public static List<QuestLogEntry> questLog(ServerPlayer player) {
        MainQuestProgression progression = MainQuestProgressionRepository.progression();
        return QuestDefinitionRepository.instance().definitions().values().stream()
            .filter(QuestDefinition::enabled)
            .filter(definition -> state(player, definition.id()) != State.NOT_STARTED)
            .map(definition -> questLogEntry(player, definition, progression))
            .sorted(Comparator
                .comparingInt((QuestLogEntry entry) -> stateRank(entry.state()))
                .thenComparingInt(entry -> categoryRank(entry.category()))
                .thenComparing(QuestLogEntry::displayName)
                .thenComparing(QuestLogEntry::questId))
            .toList();
    }

    private static QuestLogEntry questLogEntry(
        ServerPlayer player,
        QuestDefinition definition,
        MainQuestProgression progression
    ) {
        State questState = state(player, definition.id());
        String target = progression.steps().stream()
            .filter(step -> step.questId().equals(definition.id()))
            .map(MainQuestProgression.Step::npcId)
            .findFirst().orElse("");
        List<QuestObjectiveProgress> objectives = definition.objectives().stream()
            .map(objective -> new QuestObjectiveProgress(
                objective.id(), objective.text(),
                questState == State.COMPLETED || matches(player, objective.conditions())
            ))
            .toList();
        return new QuestLogEntry(
            definition.id(),
            definition.category().name().toLowerCase(java.util.Locale.ROOT),
            definition.displayName(),
            definition.summary(),
            questState.token(),
            definition.completionMode().name().toLowerCase(java.util.Locale.ROOT),
            target,
            objectives
        );
    }

    private static int stateRank(String state) {
        return "completed".equals(state) ? 1 : 0;
    }

    private static int categoryRank(String category) {
        return switch (category) {
            case "main" -> 0;
            case "side" -> 1;
            default -> 2;
        };
    }

    private static String currentObjectiveText(
        ServerPlayer player, QuestDefinition definition
    ) {
        return definition.objectives().stream()
            .filter(objective -> !matches(player, objective.conditions()))
            .findFirst()
            .orElseGet(() -> definition.objectives().getLast())
            .text();
    }

    private static Optional<MainQuestProgression.Step> currentProgressionStep(
        ServerPlayer player
    ) {
        return currentProgressionStep(
            MainQuestProgressionRepository.progression(), questId -> state(player, questId)
        );
    }

    static Optional<MainQuestProgression.Step> currentProgressionStep(
        MainQuestProgression progression, Function<String, State> stateLookup
    ) {
        if (!progression.enabled()) return Optional.empty();
        for (MainQuestProgression.Step step : progression.steps()) {
            if (stateLookup.apply(step.questId()) != State.COMPLETED) return Optional.of(step);
        }
        return Optional.empty();
    }

    private static Result result(State state, boolean granted, String failure) {
        return new Result(state, granted, state == State.READY || state == State.COMPLETED,
            state == State.COMPLETED, failure);
    }

    private static Result failure(State state, String reason) {
        return result(state, false, reason);
    }

    private static void writeState(
        ServerPlayer player, String questId, State state, String timestampKey
    ) {
        CompoundTag persisted = persisted(player);
        CompoundTag progress = persisted.getCompound(PROGRESS_KEY);
        CompoundTag quest = progress.getCompound(questId);
        quest.putString(STATE_KEY, state.token());
        if (timestampKey != null) quest.putLong(timestampKey, System.currentTimeMillis());
        progress.put(questId, quest);
        persisted.put(PROGRESS_KEY, progress);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static CompoundTag persisted(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
    }

    /** Calls the existing shared PlayerConditions implementation without duplicating rules. */
    private static boolean matches(ServerPlayer player, QuestDefinition.ConditionGroup group) {
        try {
            Class<?> conditionsType = Class.forName(
                "dev.buizz.cobbleventure.playermenu.PlayerConditions"
            );
            Method parse = conditionsType.getMethod("parse", JsonObject.class);
            List<Object> conditions = new ArrayList<>();
            for (JsonObject value : group.conditions()) conditions.add(parse.invoke(null, value));
            Method matches = conditionsType.getMethod(
                "matches", ServerPlayer.class, String.class, List.class
            );
            return (boolean) matches.invoke(null, player, group.mode(), conditions);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("공용 플레이어 조건 판정기를 호출할 수 없습니다.", error);
        }
    }
}
