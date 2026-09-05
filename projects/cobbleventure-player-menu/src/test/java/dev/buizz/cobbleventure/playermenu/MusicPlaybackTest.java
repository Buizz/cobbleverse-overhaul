package dev.buizz.cobbleventure.playermenu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MusicPlaybackTest {
    @Test
    void leavingInteriorDoesNotInvalidateHigherPriorityBattleTrack() {
        assertFalse(MusicPlaybackPolicy.shouldInvalidatePlayingTrack(
            "facility.gym", "battle.gym_leader"
        ));
        assertTrue(MusicPlaybackPolicy.shouldInvalidatePlayingTrack(
            "facility.gym", "facility.gym"
        ));
    }
}
