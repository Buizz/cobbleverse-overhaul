package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectiveRadarIconTest {
    @Test
    void pointsTowardEachRadarDirection() {
        assertTip(3, 0, ObjectiveRadarIcon.oriented(1.0D, 0.0D));
        assertTip(-3, 0, ObjectiveRadarIcon.oriented(-1.0D, 0.0D));
        assertTip(0, 3, ObjectiveRadarIcon.oriented(0.0D, 1.0D));
        assertTip(0, -3, ObjectiveRadarIcon.oriented(0.0D, -1.0D));
    }

    @Test
    void keepsSeparatedTailPixelsToSuggestForwardMovement() {
        List<ObjectiveRadarIcon.Pixel> pixels = ObjectiveRadarIcon.oriented(1.0D, 0.0D);
        assertTrue(pixels.stream().anyMatch(pixel ->
            pixel.x() == -5 && pixel.y() == -2 && !pixel.head()));
        assertTrue(pixels.stream().anyMatch(pixel ->
            pixel.x() == -3 && pixel.y() == -1 && pixel.head()));
    }

    @Test
    void defaultsToUpWhenPlayerIsAlreadyOnTheTarget() {
        assertTip(0, -3, ObjectiveRadarIcon.oriented(0.0D, 0.0D));
    }

    private static void assertTip(
        int expectedX, int expectedY, List<ObjectiveRadarIcon.Pixel> pixels
    ) {
        ObjectiveRadarIcon.Pixel tip = pixels.stream()
            .filter(ObjectiveRadarIcon.Pixel::head)
            .max((left, right) -> Integer.compare(
                left.x() * expectedX + left.y() * expectedY,
                right.x() * expectedX + right.y() * expectedY
            ))
            .orElseThrow();
        assertEquals(expectedX, tip.x());
        assertEquals(expectedY, tip.y());
    }
}
