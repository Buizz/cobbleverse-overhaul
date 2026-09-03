package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.buizz.cobbleventure.bootstrap.client.PokemonChallengeLabelFont;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class PokemonChallengeLabelFontTest {
    @Test
    void translatesPromptAndForcesVanillaFontOnNestedKeyNameWithoutChangingSource() {
        var originalFont = ResourceLocation.withDefaultNamespace("default");
        var key = Component.literal("R").withStyle(style -> style
            .withFont(originalFont).withColor(ChatFormatting.YELLOW));
        var source = Component.translatableWithFallback(
            "cobbleventure.test.challenge", "%s 키를 눌러 전투", key
        ).withStyle(style -> style.withFont(originalFont));
        var result = PokemonChallengeLabelFont.apply(source);

        assertNotSame(source, result);
        assertEquals("R 키를 눌러 전투", result.getString());
        result.visit((style, text) -> {
            assertEquals(ResourceLocation.withDefaultNamespace("uniform"), style.getFont());
            if (text.equals("R")) assertEquals(key.getStyle().getColor(), style.getColor());
            return Optional.empty();
        }, Style.EMPTY);
        assertEquals(originalFont, source.getStyle().getFont());
        assertEquals(originalFont, key.getStyle().getFont());
    }

    @Test
    void preservesRemappedKeyAndSiblingText() {
        var source = Component.literal("Press ").append(Component.literal("V"))
            .append(" to battle");
        assertEquals("Press V to battle", PokemonChallengeLabelFont.apply(source).getString());
    }
}
