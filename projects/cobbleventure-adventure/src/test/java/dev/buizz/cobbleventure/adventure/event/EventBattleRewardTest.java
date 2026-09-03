package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonParser;
import java.util.Map;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EventBattleRewardTest {
    private static final String SCRIPT = "test:event_script/trainer";

    private static EventBattlePreset.MoneyReward fixed(boolean enabled, int amount) {
        return new EventBattlePreset.MoneyReward(enabled, "fixed", amount, 1, 0, 0,
            false, "minecraft:air", 1);
    }

    private static EventBattlePreset preset(EventBattlePreset.MoneyReward reward) {
        return new EventBattlePreset("test:battle/trainer", "test:trainer/test",
            "GEN_9_SINGLES", "fixed", 0, 6, null, reward);
    }

    @Test
    void npcOverrideWinsOnceAndExplicitDisableDoesNotFallBackToBattleReward() {
        var battle = preset(fixed(true, 500));
        var npcReward = fixed(true, 220);
        var binding = new EventNpcBinding("test:trainer", "tag", SCRIPT, npcReward);
        assertSame(npcReward, EventBattleBridge.rewardFor(battle, binding, SCRIPT));
        assertEquals("cobbleventure_reward prepare Red fixed 220 false minecraft:air 1",
            EventBattleBridge.rewardFor(battle, binding, SCRIPT).prepareCommand("Red"));

        var disabled = new EventNpcBinding("test:trainer", "tag", SCRIPT, fixed(false, 220));
        assertNull(EventBattleBridge.rewardFor(battle, disabled, SCRIPT).prepareCommand("Red"));
        assertSame(battle.moneyReward(), EventBattleBridge.rewardFor(battle, null, SCRIPT));
        assertSame(battle.moneyReward(), EventBattleBridge.rewardFor(battle, binding,
            "test:event_script/unrelated"));
    }

    @Test
    void legacyBindingAndGymWithoutAutomaticRewardDoNotGainAnImplicitPayout() {
        var binding = new EventNpcBinding("test:gym", "tag", SCRIPT);
        assertNull(EventBattleBridge.rewardFor(preset(null), binding, SCRIPT));
        var battle = preset(fixed(true, 500));
        assertSame(battle.moneyReward(), EventBattleBridge.rewardFor(battle, binding, SCRIPT));
    }

    @Test
    void bindingReloadPreservesRewardAndEvaluatesAllFlagsForTheCurrentPlayer() {
        var repository = new EventNpcBindingRepository();
        repository.replace(Map.of(ResourceLocation.parse("test:trainer"), JsonParser.parseString("""
            {"schema_version":1,"script_id":"test:event_script/trainer",
             "money_reward":{"enabled":true,"mode":"regional_level",
              "fallback_region_level":6,"per_level":20,"offset":100,
              "held_item_bonus":true,"held_item":"cobblemon:amulet_coin","held_item_multiplier":2,
              "conditions":[{"type":"flag_equals","key":"test:eligible","value":true},
                            {"type":"flag_equals","key":"test:blocked","value":false}]}}
            """)));
        var reward = repository.bindingsByTag().get("cves_binding/test/trainer").moneyReward();
        assertEquals("cobbleventure_reward prepare Red regional 6 20 100 true cobblemon:amulet_coin 2",
            reward.prepareCommand("Red", key -> key.equals("test:eligible")));
        assertNull(reward.prepareCommand("Blue", key -> false));
        assertNull(reward.prepareCommand("Red", key -> true));
        assertThrows(IllegalStateException.class, () -> reward.prepareCommand("Red"));
        assertThrows(UnsupportedOperationException.class, () -> reward.conditions().clear());
    }

    @Test
    void invalidRewardRejectsReloadInsteadOfIgnoringConditionsOrKeepingPartialData() {
        var repository = new EventNpcBindingRepository();
        var id = ResourceLocation.parse("test:trainer");
        var document = JsonParser.parseString("""
            {"schema_version":1,"script_id":"test:event_script/trainer",
             "money_reward":{"enabled":true,"mode":"fixed","amount":0,
                             "held_item_bonus":false,"conditions":[]}}
            """);
        repository.replace(Map.of(id, document));
        var snapshot = repository.bindingsByTag();
        for (String invalid : List.of(
            "[{\"type\":\"has_item\"}]", "{}",
            "[{\"type\":\"flag_equals\",\"key\":\"test:flag\",\"value\":1}]"
        )) {
            var changed = document.deepCopy();
            changed.getAsJsonObject().getAsJsonObject("money_reward")
                .add("conditions", JsonParser.parseString(invalid));
            assertThrows(EventScriptFormatException.class, () -> repository.replace(Map.of(id, changed)));
            assertEquals(snapshot, repository.bindingsByTag());
        }
        document.getAsJsonObject().getAsJsonObject("money_reward").addProperty("amount", -1);
        assertThrows(EventScriptFormatException.class, () -> repository.replace(Map.of(id, document)));
    }
}
