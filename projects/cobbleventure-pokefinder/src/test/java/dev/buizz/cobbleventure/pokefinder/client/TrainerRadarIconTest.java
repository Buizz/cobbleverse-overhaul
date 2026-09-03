package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TrainerRadarIconTest {
    @Test
    void silhouetteFitsNinePixelBoundsWithSeparateCapFaceAndShoulders() {
        assertEquals(9, TrainerRadarIcon.PIXELS.size());
        assertTrue(TrainerRadarIcon.PIXELS.stream().allMatch(row ->
            row.length() == 9 && row.matches("[.#x]+")));
        assertEquals(".######..", TrainerRadarIcon.PIXELS.get(2));
        assertEquals("..#xx#...", TrainerRadarIcon.PIXELS.get(3));
        assertEquals(".#xxxx#..", TrainerRadarIcon.PIXELS.get(7));
    }
}
