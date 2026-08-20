package dev.buizz.cobbleventure.adventure.event.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class EventDialogueThemeTest {
    @Test
    void parsesConfiguredFontScaleAndPortraitAngles() {
        EventDialogueTheme theme = EventDialogueTheme.parse("""
            {"font":{"resource":"cobbleventure:battle","body_scale":1.25},
             "portrait":{"yaw_degrees":-22,"pitch_degrees":7,"scale":1.15}}
            """);

        assertEquals("cobbleventure:battle", theme.font.toString());
        assertEquals(1.25F, theme.bodyScale);
        assertEquals(-22F, theme.portraitYaw);
        assertEquals(7F, theme.portraitPitch);
        assertEquals(1.15F, theme.portraitScale);
        assertEquals(18, theme.panelCornerRadius);
        assertEquals(190, theme.choicePanelWidth);
        assertEquals(14, theme.menuCornerRadius);
    }

    @Test
    void malformedThemeFallsBackAndNumericValuesAreClamped() {
        EventDialogueTheme fallback = EventDialogueTheme.parse("not json");
        EventDialogueTheme clamped = EventDialogueTheme.parse("""
            {"font":{"body_scale":9},"portrait":{"yaw_degrees":90}}
            """);

        assertEquals("minecraft:default", fallback.font.toString());
        assertEquals(18F, fallback.portraitYaw);
        assertEquals(2F, clamped.bodyScale);
        assertEquals(35F, clamped.portraitYaw);
        assertEquals(0xFAF8FBFF, fallback.panelBackground);
    }
}
