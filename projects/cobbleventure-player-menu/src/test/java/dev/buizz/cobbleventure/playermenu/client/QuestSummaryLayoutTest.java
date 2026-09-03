package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class QuestSummaryLayoutTest {
    @Test
    void usesInlineSummaryInsteadOfHidingItOnSmallLogicalScreens() {
        assertEquals(QuestSummaryLayout.Mode.INLINE, layout(320, 240).mode());
        assertEquals(QuestSummaryLayout.Mode.INLINE, layout(480, 270).mode());
        assertEquals(40, layout(320, 240).height());
    }

    @Test
    void narrowsAndShortensTheSidePanelBeforeUsingInline() {
        QuestSummaryLayout compact = layout(600, 360);
        assertEquals(QuestSummaryLayout.Mode.COMPACT, compact.mode());
        assertEquals(96, compact.height());
        assertTrue(compact.width() >= 112 && compact.width() < 220);
        QuestSummaryLayout full = layout(1280, 720);
        assertEquals(QuestSummaryLayout.Mode.FULL, full.mode());
        assertEquals(330, full.width());
        assertEquals(142, full.height());
    }

    @Test
    void stillUsesFormerlyHiddenNarrowSideSpace() {
        QuestSummaryLayout panel = QuestSummaryLayout.calculate(300, 140, 180, 12, 110, 96, 12, 8);
        assertEquals(QuestSummaryLayout.Mode.COMPACT, panel.mode());
        assertEquals(120, panel.width());
    }

    @Test
    void keepsPanelsWithinScreenAndOutOfTheMenuAcrossGuiScales() {
        for (int width : new int[] {320, 360, 426, 480, 540, 640, 800, 960, 1280, 1920}) {
            for (int height : new int[] {240, 270, 300, 360, 540, 720, 1080}) {
                QuestSummaryLayout panel = layout(width, height);
                int menuX = width - Math.clamp(width / 4, 172, 208) - 12;
                assertTrue(panel.x() >= 12, width + "x" + height);
                assertTrue(panel.y() >= 12);
                assertTrue(panel.width() > 0 && panel.height() >= 40);
                assertTrue(panel.x() + panel.width() <= menuX - 8);
                assertTrue(panel.y() + panel.height() <= height - 12);
            }
        }
    }

    @Test
    void lineBudgetRespectsRemainingHeightAndLargeThemeTypography() {
        assertEquals(2, QuestSummaryLayout.visibleLines(40, 11, 2));
        assertEquals(1, QuestSummaryLayout.visibleLines(40, 22, 2));
        assertEquals(0, QuestSummaryLayout.visibleLines(8, 9, 2));
        assertEquals(0, QuestSummaryLayout.visibleLines(-3, 11, 2));
    }

    @Test
    void keyboardHintsCannotPaintOverTheInlineQuest() {
        assertFalse(QuestSummaryLayout.controlsFit(193, 226, 8));
        assertFalse(QuestSummaryLayout.controlsFit(233, 226, 8));
        assertTrue(QuestSummaryLayout.controlsFit(234, 226, 8));
    }

    private static QuestSummaryLayout layout(int width, int height) {
        int menuWidth = Math.clamp(width / 4, 172, 208);
        int menuX = width - menuWidth - 12;
        int rowHeight = Math.clamp((height - 24 - 22 - 10 - 2 * 7) / 8, 19, 27);
        int trainerTop = Math.max(12, (height - (22 + 10 + rowHeight * 8 + 2 * 7)) / 2);
        int infoX = Math.max(12, menuX - 8 - Math.clamp(width / 5, 180, 240));
        int infoWidth = Math.max(112, menuX - 8 - infoX);
        return QuestSummaryLayout.calculate(height, infoX, infoWidth, trainerTop, 110, 96, 12, 8);
    }
}
