package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FlashCaveEffectsTest {
    @Test
    void doesNotApplyVisionEffectOutsideFlashRegion() {
        assertEquals("", FlashCaveEffects.desiredEffect(false, Double.NaN));
    }

    @Test
    void preservesEntranceVisibilityBeforeDarkeningTheCave() {
        assertEquals("", FlashCaveEffects.desiredEffect(false, 14.0D * 14.0D));
        assertEquals("darkness", FlashCaveEffects.desiredEffect(false, 15.0D * 15.0D));
        assertEquals("blindness", FlashCaveEffects.desiredEffect(false, 40.0D * 40.0D));
    }

    @Test
    void activeFlashIlluminatesEveryRequiredRegion() {
        assertEquals("night_vision", FlashCaveEffects.desiredEffect(true, 0.0D));
        assertEquals("night_vision", FlashCaveEffects.desiredEffect(true, 100.0D * 100.0D));
    }
}
