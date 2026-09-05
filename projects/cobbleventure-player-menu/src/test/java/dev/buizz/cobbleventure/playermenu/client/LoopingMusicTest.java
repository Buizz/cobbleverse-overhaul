package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LoopingMusicTest {
    @Test
    void transientInactiveStreamDoesNotRestartAtItsIntro() {
        assertFalse(MusicLoopPolicy.shouldRestartPass(20, false));
        assertFalse(MusicLoopPolicy.shouldRestartPass(599, false));
        assertTrue(MusicLoopPolicy.shouldRestartPass(600, false));
        assertFalse(MusicLoopPolicy.shouldRestartPass(600, true));
    }
}
