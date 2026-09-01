package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StationaryNpcLookTest {
    @Test
    void clampsHeadTurnWithoutRotatingTheBody() {
        assertEquals(
            105.0F,
            StationaryNpcLookMath.clampHeadYaw(30.0F, 160.0F, 75.0F),
            0.001F
        );
        assertEquals(
            -45.0F,
            StationaryNpcLookMath.clampHeadYaw(30.0F, -100.0F, 75.0F),
            0.001F
        );
    }

    @Test
    void approachesAcrossTheDegreeWrapUsingTheShortestTurn() {
        assertEquals(
            -175.0F,
            StationaryNpcLookMath.approachAngle(175.0F, -170.0F, 10.0F),
            0.001F
        );
    }
}
