package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonParser;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventBattlePresetRepositoryTest {
    private static final UUID NPC = UUID.fromString(
        "20000000-0000-0000-0000-000000000001"
    );

    @Test
    void loadsFixedPresetAndBuildsExistingIntroCommand() {
        EventBattlePresetRepository repository = new EventBattlePresetRepository();
        repository.replace(Map.of(
            ResourceLocation.parse("cobbleventure:examples/ai_test"), document(
                "cobbleventure:battle/ai_test", "fixed", 0, 5, 2
            )
        ));

        EventBattlePreset preset = repository.find("cobbleventure:battle/ai_test")
            .orElseThrow();
        assertEquals("cobbleventure:trainer/ai_test", preset.trainerId());
        assertEquals(
            "cobbleventure_battle_intro Red " + NPC
                + " cobbleventure:battle/ai_test tbcs battle GEN_9_SINGLES Red"
                + " vs @s as rctmod:ai_test rules {maxItemUses:2}",
            preset.launchCommand("Red", NPC)
        );
    }

    @Test
    void mapScalingUsesExistingScalingWrapperAndTeamMaximumFallback() {
        EventBattlePresetRepository repository = new EventBattlePresetRepository();
        repository.replace(Map.of(
            ResourceLocation.parse("cobbleventure:samples/scaled"), document(
                "cobbleventure:battle/scaled", "map_scaling", 3, 42, null
            )
        ));

        EventBattlePreset preset = repository.find("cobbleventure:battle/scaled")
            .orElseThrow();
        assertEquals(42, preset.fallbackLevel());
        assertEquals(
            "cobbleventure_scaled_trainer_battle Red " + NPC
                + " cobbleventure:battle/scaled 3 42 rctmod:ai_test"
                + " tbcs battle GEN_9_SINGLES Red vs @s as rctmod:ai_test",
            preset.launchCommand("Red", NPC)
        );
    }

    @Test
    void loadsRegionalMoneyRewardForTheBattleAwaitAdapter() {
        EventBattlePresetRepository repository = new EventBattlePresetRepository();
        var value = JsonParser.parseString(document(
            "cobbleventure:battle/reward", "fixed", 0, 5, null
        ).toString().replace(
            "\"trainer_id\":\"cobbleventure:trainer/ai_test\"",
            "\"trainer_id\":\"cobbleventure:trainer/ai_test\"," +
                "\"money_reward\":{" +
                "\"enabled\":true,\"mode\":\"regional_level\"," +
                "\"fallback_region_level\":20,\"per_level\":20,\"offset\":100," +
                "\"held_item_bonus\":true,\"held_item\":\"cobblemon:amulet_coin\"," +
                "\"held_item_multiplier\":2,\"conditions\":[]}"));
        repository.replace(Map.of(ResourceLocation.parse("cobbleventure:reward"), value));

        EventBattlePreset.MoneyReward reward = repository.find("cobbleventure:battle/reward")
            .orElseThrow().moneyReward();

        assertEquals(
            "cobbleventure_reward prepare Red regional 20 20 100 true cobblemon:amulet_coin 2",
            reward.prepareCommand("Red")
        );
    }

    @Test
    void duplicateBattleIdsRejectWholeSnapshot() {
        EventBattlePresetRepository repository = new EventBattlePresetRepository();
        assertThrows(EventScriptFormatException.class, () -> repository.replace(Map.of(
            ResourceLocation.parse("cobbleventure:first"), document(
                "cobbleventure:battle/same", "fixed", 0, 5, null
            ),
            ResourceLocation.parse("cobbleventure:second"), document(
                "cobbleventure:battle/same", "fixed", 0, 5, null
            )
        )));
    }

    @Test
    void fractionalIntegerRejectsWholeSnapshotAndPreservesPreviousPresets() {
        EventBattlePresetRepository repository = new EventBattlePresetRepository();
        repository.replace(Map.of(
            ResourceLocation.parse("cobbleventure:valid"), document(
                "cobbleventure:battle/valid", "fixed", 0, 5, null
            )
        ));
        var invalid = JsonParser.parseString(document(
            "cobbleventure:battle/invalid", "fixed", 0, 5, null
        ).toString().replace("\"level_offset\":0", "\"level_offset\":0.5"));

        EventScriptFormatException error = assertThrows(
            EventScriptFormatException.class,
            () -> repository.replace(Map.of(
                ResourceLocation.parse("cobbleventure:invalid"), invalid
            ))
        );

        assertTrue(error.getMessage().contains("level_offset 정수가 필요합니다."));
        assertTrue(repository.find("cobbleventure:battle/valid").isPresent());
        assertTrue(repository.find("cobbleventure:battle/invalid").isEmpty());
    }

    private static com.google.gson.JsonElement document(
        String battleId, String levelMode, int offset, int level, Integer maxItems
    ) {
        String rules = maxItems == null ? "{}" : "{\"max_item_uses\":" + maxItems + "}";
        return JsonParser.parseString("""
            {
              "schema_version": 1,
              "id": "BATTLE_ID",
              "enabled": true,
              "battle": {
                "trainer_id": "cobbleventure:trainer/ai_test",
                "format": "GEN_9_SINGLES",
                "level_mode": "LEVEL_MODE",
                "level_offset": OFFSET,
                "rules": RULES,
                "team": [{"level": LEVEL}]
              }
            }
            """
            .replace("BATTLE_ID", battleId)
            .replace("LEVEL_MODE", levelMode)
            .replace("OFFSET", Integer.toString(offset))
            .replace("RULES", rules)
            .replace("LEVEL", Integer.toString(level))
        );
    }
}
