package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/** Atomically reloads the launch projection of data/cobbleventure/battles presets. */
public final class EventBattlePresetRepository extends SimplePreparableReloadListener<
    Map<ResourceLocation, JsonElement>
> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EventBattlePresetRepository INSTANCE =
        new EventBattlePresetRepository();
    private static final String DIRECTORY = "battles";

    private volatile Map<String, EventBattlePreset> presets = Map.of();

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventBattlePresetRepository::onAddReloadListeners);
    }

    public static EventBattlePresetRepository instance() { return INSTANCE; }

    public Optional<EventBattlePreset> find(String battleId) {
        return Optional.ofNullable(presets.get(battleId));
    }

    public Map<String, EventBattlePreset> presets() { return presets; }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(
        ResourceManager resources, ProfilerFiller profiler
    ) {
        FileToIdConverter converter = FileToIdConverter.json(DIRECTORY);
        Map<ResourceLocation, JsonElement> documents = new LinkedHashMap<>();
        converter.listMatchingResources(resources).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                ResourceLocation id = converter.fileToId(entry.getKey());
                try (Reader reader = entry.getValue().openAsReader()) {
                    documents.put(id, JsonParser.parseReader(reader));
                } catch (IOException | JsonParseException error) {
                    throw new EventScriptFormatException(
                        DIRECTORY + "/" + id + ": JSON을 읽을 수 없습니다.", error
                    );
                }
            });
        return Map.copyOf(documents);
    }

    @Override
    protected void apply(
        Map<ResourceLocation, JsonElement> documents,
        ResourceManager resources,
        ProfilerFiller profiler
    ) {
        replace(documents);
        LOGGER.info("Loaded {} CVES battle presets", presets.size());
    }

    void replace(Map<ResourceLocation, JsonElement> documents) {
        Map<String, EventBattlePreset> loaded = new LinkedHashMap<>();
        documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String path = DIRECTORY + "/" + entry.getKey();
            JsonObject root = object(entry.getValue(), path);
            if (root.has("enabled") && !bool(root, "enabled", path)) return;
            int version = integer(root, "schema_version", path);
            if (version != 1) {
                throw invalid(path, "지원하지 않는 schema_version입니다: " + version);
            }
            String battleId = string(root, "id", path);
            if (ResourceLocation.tryParse(battleId) == null || !battleId.contains(":battle/")) {
                throw invalid(path, "battle ID 형식이 올바르지 않습니다: " + battleId);
            }
            JsonObject battle = object(root.get("battle"), path + ".battle");
            String trainerId = string(battle, "trainer_id", path + ".battle");
            String format = string(battle, "format", path + ".battle");
            String levelMode = battle.has("level_mode")
                ? string(battle, "level_mode", path + ".battle") : "fixed";
            if (!levelMode.equals("fixed") && !levelMode.equals("map_scaling")) {
                throw invalid(path, "지원하지 않는 level_mode입니다: " + levelMode);
            }
            int levelOffset = battle.has("level_offset")
                ? integer(battle, "level_offset", path + ".battle") : 0;
            int fallbackLevel = fallbackLevel(battle, path);
            Integer maxItemUses = null;
            if (battle.has("rules")) {
                JsonObject rules = object(battle.get("rules"), path + ".battle.rules");
                if (rules.has("max_item_uses")) {
                    maxItemUses = integer(rules, "max_item_uses", path + ".battle.rules");
                }
            }
            EventBattlePreset.MoneyReward moneyReward = null;
            if (battle.has("money_reward")) {
                moneyReward = moneyReward(
                    object(battle.get("money_reward"), path + ".battle.money_reward"),
                    path + ".battle.money_reward"
                );
            }
            EventBattlePreset preset;
            try {
                preset = new EventBattlePreset(
                    battleId, trainerId, format, levelMode,
                    levelOffset, fallbackLevel, maxItemUses, moneyReward
                );
            } catch (IllegalArgumentException error) {
                throw invalid(path, error.getMessage());
            }
            if (loaded.putIfAbsent(battleId, preset) != null) {
                throw invalid(path, "중복 battle ID입니다: " + battleId);
            }
        });
        presets = Map.copyOf(loaded);
    }

    private static EventBattlePreset.MoneyReward moneyReward(JsonObject value, String path) {
        if (value.has("conditions")) {
            JsonElement conditions = value.get("conditions");
            if (!conditions.isJsonArray()) {
                throw invalid(path, "money_reward conditions 배열이 필요합니다.");
            }
            if (!conditions.getAsJsonArray().isEmpty()) {
                throw invalid(path, "CVES battle money_reward conditions는 아직 지원하지 않습니다.");
            }
        }
        String mode = string(value, "mode", path);
        return new EventBattlePreset.MoneyReward(
            bool(value, "enabled", path),
            mode,
            mode.equals("fixed") ? integer(value, "amount", path) : 0,
            mode.equals("regional_level") ? integer(value, "fallback_region_level", path) : 1,
            mode.equals("regional_level") ? integer(value, "per_level", path) : 0,
            mode.equals("regional_level") ? integer(value, "offset", path) : 0,
            bool(value, "held_item_bonus", path),
            value.has("held_item") ? string(value, "held_item", path) : "minecraft:air",
            value.has("held_item_multiplier") ? integer(value, "held_item_multiplier", path) : 1
        );
    }

    private static int fallbackLevel(JsonObject battle, String path) {
        JsonElement value = battle.get("team");
        if (value == null || !value.isJsonArray()) {
            throw invalid(path, "battle.team 배열이 필요합니다.");
        }
        JsonArray team = value.getAsJsonArray();
        int maximum = 1;
        for (int index = 0; index < team.size(); index++) {
            JsonObject pokemon = object(team.get(index), path + ".battle.team[" + index + "]");
            if (pokemon.has("level")) {
                maximum = Math.max(
                    maximum,
                    integer(pokemon, "level", path + ".battle.team[" + index + "]")
                );
            }
        }
        return maximum;
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) throw invalid(path, "object가 필요합니다.");
        return value.getAsJsonObject();
    }

    private static String string(JsonObject value, String field, String path) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString()) {
            throw invalid(path, field + " 문자열이 필요합니다.");
        }
        return element.getAsString();
    }

    private static int integer(JsonObject value, String field, String path) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(path, field + " 정수가 필요합니다.");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid(path, field + " 정수가 필요합니다.");
        }
    }

    private static boolean bool(JsonObject value, String field, String path) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isBoolean()) {
            throw invalid(path, field + " 불리언이 필요합니다.");
        }
        return element.getAsBoolean();
    }

    private static EventScriptFormatException invalid(String path, String message) {
        return new EventScriptFormatException(path + ": " + message);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }
}
