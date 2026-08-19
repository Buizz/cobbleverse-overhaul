package dev.buizz.cobbleventure.adventure.event.client;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DialoguePlaybackTest {
    @Test
    void firstAdvanceRevealsAndSecondAdvanceMovesToNextPage() {
        DialoguePlayback playback = new DialoguePlayback(List.of("안녕!", "다음 문장"));

        playback.tick();
        assertEquals("안", playback.visibleText());
        assertEquals(DialoguePlayback.AdvanceResult.REVEALED, playback.advance());
        assertEquals("안녕!", playback.visibleText());
        assertEquals(DialoguePlayback.AdvanceResult.NEXT_PAGE, playback.advance());
        assertEquals(2, playback.pageNumber());
        assertEquals("", playback.visibleText());
    }

    @Test
    void finalRevealedPageCompletesWithoutDuplicateStateChange() {
        DialoguePlayback playback = new DialoguePlayback(List.of("끝"));
        playback.revealPage();

        assertEquals(DialoguePlayback.AdvanceResult.COMPLETED, playback.advance());
        assertEquals(DialoguePlayback.AdvanceResult.COMPLETED, playback.advance());
        assertTrue(playback.lastPage());
    }

    @Test
    void unicodeSupplementaryCharactersRevealAsOneUnit() {
        DialoguePlayback playback = new DialoguePlayback(List.of("A😀B"));

        playback.tick();
        playback.tick();

        assertEquals("A😀", playback.visibleText());
        assertFalse(playback.pageRevealed());
        playback.tick();
        assertTrue(playback.pageRevealed());
    }
}
