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
}
