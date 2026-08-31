package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GroundFloorAirPreservationProcessorTest {
    @Test
    void skipsAirOnTheTemplateBottomLayer() {
        assertTrue(NbtGroundFloorRules.shouldPreserveWorldBlock(0, true));
    }

    @Test
    void keepsSolidBlocksAndAirAboveTheBottomLayer() {
        assertFalse(NbtGroundFloorRules.shouldPreserveWorldBlock(0, false));
        assertFalse(NbtGroundFloorRules.shouldPreserveWorldBlock(1, true));
    }
}
