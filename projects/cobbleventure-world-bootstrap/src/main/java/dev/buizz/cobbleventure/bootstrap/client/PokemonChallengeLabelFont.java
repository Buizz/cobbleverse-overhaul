package dev.buizz.cobbleventure.bootstrap.client;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Vanilla world-space text; minecraft:default is the pack's Caxton GUI font. */
public final class PokemonChallengeLabelFont {
    private static final ResourceLocation FONT = ResourceLocation.withDefaultNamespace("uniform");

    private PokemonChallengeLabelFont() {}

    public static MutableComponent apply(Component label) {
        MutableComponent result = Component.empty().withStyle(style -> style.withFont(FONT));
        // Visit resolved translation arguments too: a keybinding component may
        // carry its own font, overriding a font set only on the parent component.
        label.visit((style, text) -> {
            result.append(Component.literal(text).setStyle(style.withFont(FONT)));
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
}
