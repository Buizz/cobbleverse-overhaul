package dev.buizz.cobbleventure.adventure.daycare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DaycareScreenLayoutTest {
    @Test
    void normalGuiScaleUsesTheNewWideDesignSize() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(1280, 720);

        assertEquals(1F, layout.scale());
        assertEquals(720, layout.panelWidth());
        assertEquals(390, layout.panelHeight());
    }

    @Test
    void depositedPokemonOccupyOneHorizontalRow() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(1280, 720);
        int lastCardRight = layout.storedX()
            + layout.storedCardWidth() * 6 + layout.storedGap() * 5;

        assertTrue(layout.storedCardWidth() > layout.partyCardHeight());
        assertTrue(lastCardRight <= layout.panelX() + layout.panelWidth() - layout.padding());
        assertTrue(layout.storedY() + layout.storedCardHeight() <= layout.contentY());
    }

    @Test
    void partyUsesTwoColumnsAndThreeRows() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(620, 337);
        int gridRight = layout.partyGridX()
            + layout.partyCardWidth() * 2 + layout.partyGap();
        int gridBottom = layout.partyGridY()
            + layout.partyCardHeight() * 3 + layout.partyGap() * 2;

        assertTrue(gridRight <= layout.partyPanelX() + layout.partyPanelWidth());
        assertTrue(gridBottom <= layout.contentY() + layout.contentHeight());
        assertTrue(layout.partyCardHeight() >= 22);
    }

    @Test
    void compactViewportKeepsBothLowerPanelsReadable() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(480, 270);

        assertTrue(layout.scale() < 1F);
        assertTrue(layout.partyCardWidth() >= 48);
        assertTrue(layout.detailPanelWidth() >= 150);
        assertTrue(layout.detailInfoX()
            < layout.detailPanelX() + layout.detailPanelWidth() - layout.padding());
        assertTrue(layout.actionY() + layout.actionHeight()
            <= layout.contentY() + layout.contentHeight());
    }

    @Test
    void allRegionsRemainInsideAHighGuiScaleViewport() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(320, 240);

        assertTrue(layout.panelX() >= 0);
        assertTrue(layout.panelY() >= 0);
        assertTrue(layout.panelX() + layout.panelWidth() <= 320);
        assertTrue(layout.panelY() + layout.panelHeight() <= 240);
        assertTrue(layout.closeX() + layout.closeWidth()
            <= layout.panelX() + layout.panelWidth() - layout.padding());
        assertTrue(layout.eggX() + layout.eggWidth()
            <= layout.panelX() + layout.panelWidth() - layout.padding());
    }
}
