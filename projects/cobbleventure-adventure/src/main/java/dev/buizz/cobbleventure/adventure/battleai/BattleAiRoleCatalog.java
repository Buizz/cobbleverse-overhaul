package dev.buizz.cobbleventure.adventure.battleai;

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.ai.core.ManualAceInput;
import dev.buizz.cobbleventure.ai.core.TeamRoleMemberInput;
import dev.buizz.cobbleventure.ai.core.TeamRoleStatsInput;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;

/** 웹과 같은 중앙 역할 카탈로그를 Minecraft 전투 관측값으로 변환한다. */
final class BattleAiRoleCatalog {
    private static final JsonObject MOVES = resource("/battle-ai/ai-move-role-classification.json")
            .getAsJsonObject("moves");
    private static final JsonObject SPECIES = resource("/battle-ai/ai-pokemon-role-overrides.json")
            .getAsJsonObject("roles");

    private BattleAiRoleCatalog() {}

    static TeamRoleMemberInput observe(BattlePokemon battlePokemon, int slot) {
        var pokemon = battlePokemon.getEffectedPokemon();
        String species = pokemon.getSpecies().getResourceIdentifier().getPath();
        List<String> moveIds = battlePokemon.getMoveSet().getMoves().stream()
                .map(move -> clean(move.getName())).toList();
        Map<String, Double> roleScores = new LinkedHashMap<>();
        Set<String> tags = new LinkedHashSet<>();
        for (String moveId : moveIds) {
            JsonObject entry = object(MOVES.get(moveId));
            mergeScores(roleScores, object(entry.get("roleScores")));
            JsonElement tagElement = entry.get("tags");
            if (tagElement != null && tagElement.isJsonArray()) {
                tagElement.getAsJsonArray().forEach(tag -> tags.add(clean(tag.getAsString())));
            }
        }
        List<String> reasons = new ArrayList<>();
        JsonObject speciesEntry = object(SPECIES.get(clean(species)));
        mergeScores(roleScores, object(speciesEntry.get("roleScores")));
        JsonElement reasonElement = speciesEntry.get("reasons");
        if (reasonElement != null && reasonElement.isJsonArray()) {
            reasonElement.getAsJsonArray().forEach(reason -> reasons.add(reason.getAsString()));
        }
        boolean hasBatonSetup = moveIds.stream().anyMatch(id -> !id.equals("batonpass")
                && (hasTag(id, "setupboost") || roleScore(id, "setupSweeper") >= 2.5));
        String item = CobblemonBattleSearch.itemId(battlePokemon);
        boolean mega = item.contains("mega") || (item.endsWith("ite") && !item.equals("eviolite"));
        return new TeamRoleMemberInput(
                slot,
                battlePokemon.getUuid().toString(),
                species,
                pokemon.getLevel(),
                clean(pokemon.getAbility().getName()),
                new TeamRoleStatsInput(
                        battlePokemon.getMaxHealth(), pokemon.getAttack(), pokemon.getDefence(),
                        pokemon.getSpecialAttack(), pokemon.getSpecialDefence(), pokemon.getSpeed()),
                moveIds,
                roleScores,
                List.copyOf(tags),
                reasons,
                hasBatonSetup,
                new ManualAceInput(false, false, 0.0, ""),
                mega ? 3.2 : 0.0,
                mega ? List.of("메가진화 자원") : List.of(),
                speciesRoleScore(speciesEntry, "ace"));
    }

    private static boolean hasTag(String moveId, String tag) {
        JsonElement values = object(MOVES.get(moveId)).get("tags");
        if (values == null || !values.isJsonArray()) return false;
        for (JsonElement value : values.getAsJsonArray()) {
            if (clean(value.getAsString()).equals(tag)) return true;
        }
        return false;
    }

    static boolean hasMoveFlag(String moveId, String flag) {
        JsonElement values = object(MOVES.get(clean(moveId))).get("flags");
        if (values == null || !values.isJsonArray()) return false;
        for (JsonElement value : values.getAsJsonArray()) {
            if (clean(value.getAsString()).equals(clean(flag))) return true;
        }
        return false;
    }

    private static double roleScore(String moveId, String role) {
        JsonElement value = object(object(MOVES.get(moveId)).get("roleScores")).get(role);
        return value == null ? 0.0 : value.getAsDouble();
    }

    private static double speciesRoleScore(JsonObject entry, String role) {
        JsonElement value = object(entry.get("roleScores")).get(role);
        return value == null ? 0.0 : value.getAsDouble();
    }

    private static void mergeScores(Map<String, Double> target, JsonObject source) {
        source.entrySet().forEach(entry -> target.merge(entry.getKey(), entry.getValue().getAsDouble(), Double::sum));
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject resource(String path) {
        try (var stream = BattleAiRoleCatalog.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("전투 AI 역할 카탈로그가 없습니다: " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new IllegalStateException("전투 AI 역할 카탈로그를 읽지 못했습니다: " + path, error);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
