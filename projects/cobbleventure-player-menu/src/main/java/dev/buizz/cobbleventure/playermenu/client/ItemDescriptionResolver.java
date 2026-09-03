package dev.buizz.cobbleventure.playermenu.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Description selection rules, independent of Minecraft's client/runtime classes. */
final class ItemDescriptionResolver {
    private ItemDescriptionResolver() {}

    static <T> List<T> resolve(
        List<T> tooltip, String descriptionId, Function<T, String> text,
        Predicate<String> translationExists, Function<String, T> translate, T fallback
    ) {
        List<T> result = new ArrayList<>();
        for (int index = 1; index < tooltip.size(); index++) {
            if (!text.apply(tooltip.get(index)).isBlank()) result.add(tooltip.get(index));
        }
        if (result.isEmpty()) {
            List<String> keys = new ArrayList<>();
            keys.add(descriptionId + ".tooltip");
            for (int index = 1; index <= 4; index++) keys.add(descriptionId + ".tooltip_" + index);
            for (String key : keys) {
                if (translationExists.test(key)) result.add(translate.apply(key));
            }
            for (String suffix : List.of(".description", ".desc")) {
                if (result.isEmpty() && translationExists.test(descriptionId + suffix)) {
                    result.add(translate.apply(descriptionId + suffix));
                }
            }
        }
        return result.isEmpty() ? List.of(fallback) : List.copyOf(result);
    }
}
