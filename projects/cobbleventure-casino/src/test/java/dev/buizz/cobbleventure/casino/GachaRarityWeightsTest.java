package dev.buizz.cobbleventure.casino;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GachaRarityWeightsTest {
    @Test
    void hardPityExposesOnlyTheGuaranteedRarity() {
        GachaCatalog.Theme theme = theme();
        theme.pity.hard.enabled = true;
        theme.pity.hard.count = 10;
        theme.pity.hard.target_rarity = "rare";

        Map<GachaCatalog.Rarity, Double> weights =
            CobbleventureCasino.rarityWeights(theme, 9);

        assertEquals(1, weights.size());
        assertEquals("rare", weights.keySet().iterator().next().id);
    }

    @Test
    void softPityRaisesTheDisplayedTargetChance() {
        GachaCatalog.Theme theme = theme();
        theme.pity.soft.enabled = true;
        theme.pity.soft.start = 2;
        theme.pity.soft.max_at = 6;
        theme.pity.soft.target_rarity = "rare";
        theme.pity.soft.max_chance = .5D;

        double base = normalizedRareChance(CobbleventureCasino.rarityWeights(theme, 0));
        double boosted = normalizedRareChance(CobbleventureCasino.rarityWeights(theme, 5));

        assertTrue(boosted > base);
        assertEquals(.5D, boosted, 1.0E-9D);
    }

    private static double normalizedRareChance(Map<GachaCatalog.Rarity, Double> weights) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        return weights.entrySet().stream()
            .filter(entry -> entry.getKey().id.equals("rare"))
            .findFirst().orElseThrow().getValue() / total;
    }

    private static GachaCatalog.Theme theme() {
        GachaCatalog.Rarity common = new GachaCatalog.Rarity();
        common.id = "common";
        common.weight = 90.0D;
        common.rewards = List.of();
        GachaCatalog.Rarity rare = new GachaCatalog.Rarity();
        rare.id = "rare";
        rare.weight = 10.0D;
        rare.rewards = List.of();
        GachaCatalog.Theme theme = new GachaCatalog.Theme();
        theme.rarities = List.of(common, rare);
        theme.pity = new GachaCatalog.Pity();
        theme.pity.soft = new GachaCatalog.Soft();
        theme.pity.hard = new GachaCatalog.Hard();
        theme.pity.selection = new GachaCatalog.Selection();
        return theme;
    }
}
