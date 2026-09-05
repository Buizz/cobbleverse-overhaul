package dev.buizz.cobbleventure.pokefinder.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DoorRadarIconTest {
    @Test
    void iconFitsNinePixelBoundsWithFramePanelAndHandle() {
        assertEquals(9, DoorRadarIcon.PIXELS.size());
        assertTrue(DoorRadarIcon.PIXELS.stream().allMatch(row ->
            row.length() == 9 && row.matches("[.#xo]+")));
        assertEquals("..#####..", DoorRadarIcon.PIXELS.getFirst());
        assertEquals("..#xxo#..", DoorRadarIcon.PIXELS.get(5));
        assertEquals("..#####..", DoorRadarIcon.PIXELS.getLast());
    }
}
