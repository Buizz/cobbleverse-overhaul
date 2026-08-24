package dev.buizz.cobbleventure.casino;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GachaRarityWeightsTest {
    @Test
    void hardPityExposesOnlyTheGuaranteedRarity() {
        GachaCatalog.Machine machine = machine();
        machine.pity.hard.enabled = true;
        machine.pity.hard.count = 10;
        machine.pity.hard.target_rarity = "rare";

        Map<GachaCatalog.Rarity, Double> weights =
            CobbleventureCasino.rarityWeights(machine, 9);

        assertEquals(1, weights.size());
        assertEquals("rare", weights.keySet().iterator().next().id);
    }

    @Test
    void softPityRaisesTheDisplayedTargetChance() {
        GachaCatalog.Machine machine = machine();
        machine.pity.soft.enabled = true;
        machine.pity.soft.start = 2;
        machine.pity.soft.max_at = 6;
        machine.pity.soft.target_rarity = "rare";
        machine.pity.soft.max_chance = .5D;

        double base = normalizedRareChance(CobbleventureCasino.rarityWeights(machine, 0));
        double boosted = normalizedRareChance(CobbleventureCasino.rarityWeights(machine, 5));

        assertTrue(boosted > base);
        assertEquals(.5D, boosted, 1.0E-9D);
    }

    private static double normalizedRareChance(Map<GachaCatalog.Rarity, Double> weights) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        return weights.entrySet().stream()
            .filter(entry -> entry.getKey().id.equals("rare"))
            .findFirst().orElseThrow().getValue() / total;
    }

    private static GachaCatalog.Machine machine() {
        GachaCatalog.Rarity common = new GachaCatalog.Rarity();
        common.id = "common";
        common.weight = 90.0D;
        common.rewards = List.of();
        GachaCatalog.Rarity rare = new GachaCatalog.Rarity();
        rare.id = "rare";
        rare.weight = 10.0D;
        rare.rewards = List.of();
        GachaCatalog.Machine machine = new GachaCatalog.Machine();
        machine.rarities = List.of(common, rare);
        machine.pity = new GachaCatalog.Pity();
        machine.pity.soft = new GachaCatalog.Soft();
        machine.pity.hard = new GachaCatalog.Hard();
        machine.pity.selection = new GachaCatalog.Selection();
        return machine;
    }
}
