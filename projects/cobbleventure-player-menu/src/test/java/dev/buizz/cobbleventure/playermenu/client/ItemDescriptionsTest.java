package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class ItemDescriptionsTest {
    private static final String FALLBACK = "No description";

    private static List<String> resolve(List<String> tooltip, String id, Predicate<String> exists) {
        return ItemDescriptionResolver.resolve(tooltip, id, value -> value, exists, key -> key, FALLBACK);
    }

    @Test
    void removesTitleAndBlankLinesButKeepsActualTooltip() {
        String effect = "Restores 20 HP.";
        assertEquals(List.of(effect), resolve(List.of("Potion", "  ", effect), "item.potion", key -> true));
    }

    @Test
    void resolvesNumberedCobblemonTooltipTranslationsInOrder() {
        Set<String> keys = Set.of("item.potion.tooltip", "item.potion.tooltip_1", "item.potion.tooltip_3");
        assertEquals(List.of("item.potion.tooltip", "item.potion.tooltip_1", "item.potion.tooltip_3"),
            resolve(List.of("Potion"), "item.potion", keys::contains));
    }

    @Test
    void fallsBackToDescriptionThenDescTranslation() {
        for (String suffix : List.of(".description", ".desc")) {
            String key = "item.potion" + suffix;
            assertEquals(List.of(key), resolve(List.of(), "item.potion", key::equals));
        }
    }

    @Test
    void showsExplicitFallbackWhenThereIsNoDescription() {
        assertEquals(List.of(FALLBACK), resolve(List.of("Stone"), "item.stone", key -> false));
    }
}
