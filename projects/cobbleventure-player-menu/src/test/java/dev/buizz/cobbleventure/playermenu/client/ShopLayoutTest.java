package dev.buizz.cobbleventure.playermenu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ShopLayoutTest {
    @Test
    void keepsMarginsOnSmallLogicalScreens() {
        ShopLayout.Panel panel = ShopLayout.panel(480, 320);

        assertEquals(394, panel.width());
        assertEquals(262, panel.height());
        assertTrue(panel.width() < 480 - 40);
        assertTrue(panel.height() < 320 - 40);
    }

    @Test
    void capsLargeLogicalScreens() {
        ShopLayout.Panel panel = ShopLayout.panel(1920, 1080);

        assertEquals(480, panel.width());
        assertEquals(286, panel.height());
    }

    @Test
    void narrowsTheCatalogColumnForCompactScreens() {
        assertEquals(182, ShopLayout.contentWidth(304));
        assertEquals(320, ShopLayout.contentWidth(480));
    }

    @Test
    void descriptionStaysAboveQuantityControlsAtEverySupportedPanelSize() {
        for (int panelHeight : new int[] {220, 246, 262, 286}) {
            for (int panelWidth : new int[] {304, 320, 394, 480}) {
                ShopLayout.DescriptionArea area = ShopLayout.descriptionArea(panelWidth, panelHeight, 9);
                assertTrue(area.height() >= 11);
                assertTrue(area.x() + area.width() < panelWidth);
                assertTrue(area.y() + area.height() <= panelHeight - 90 - 5);
            }
        }
    }

    @Test
    void descriptionScrollClampsAndDoesNotCaptureQuantityButtons() {
        ShopLayout.DescriptionArea area = ShopLayout.descriptionArea(480, 286, 9);
        assertEquals(0, area.clampScroll(-1, 30, 11));
        assertEquals(30 - area.visibleLines(11), area.clampScroll(100, 30, 11));
        assertEquals(0, area.clampScroll(5, 1, 11));
        assertTrue(area.contains(area.x(), area.y()));
        assertTrue(!area.contains(area.x(), 286 - 90));
    }
}
