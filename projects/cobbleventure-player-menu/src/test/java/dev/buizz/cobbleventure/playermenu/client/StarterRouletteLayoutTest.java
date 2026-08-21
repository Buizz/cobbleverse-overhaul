package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StarterRouletteLayoutTest {
    @Test
    void modelScaleShrinksWithTheResponsiveSlotSize() {
        assertEquals(0.9F, StarterRouletteLayout.modelScaleForSize(32, 96));
        assertEquals(1.3F, StarterRouletteLayout.modelScaleForSize(48, 96));
        assertEquals(2.6F, StarterRouletteLayout.modelScaleForSize(96, 96));
    }

    @Test
    void modelScaleStaysWithinSupportedBounds() {
        assertEquals(0.9F, StarterRouletteLayout.modelScaleForSize(1, 96));
        assertEquals(2.6F, StarterRouletteLayout.modelScaleForSize(200, 96));
    }
}
