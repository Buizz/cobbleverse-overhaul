package dev.buizz.cobbleventure.adventure.daycare.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DaycareScreenLayoutTest {
    @Test
    void highGuiScaleUsesACompactPanelWithScreenMargins() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(620, 337);

        assertTrue(layout.scale() < 1F);
        assertTrue(layout.panelX() >= 32);
        assertTrue(layout.panelY() >= 20);
        assertTrue(layout.panelX() + layout.panelWidth() <= 620 - 32);
        assertTrue(layout.panelY() + layout.panelHeight() <= 337 - 20);
    }

    @Test
    void normalGuiScaleUsesTheFullDesignSize() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(1280, 720);

        assertTrue(layout.scale() == 1F);
        assertTrue(layout.panelWidth() == 560);
        assertTrue(layout.panelHeight() == 306);
    }

    @Test
    void controlsRemainInsideCompactPanel() {
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(480, 270);
        int collectBottom = layout.actionY() + 47;
        int statusTop = layout.panelY() + layout.panelHeight() - 39;

        assertTrue(collectBottom <= statusTop);
        assertTrue(layout.storageX() + layout.storageWidth()
            <= layout.panelX() + layout.panelWidth() - layout.padding());
    }
}
