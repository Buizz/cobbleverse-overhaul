package dev.buizz.cobbleventure.pokefinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class PokenavAccessTest {
    @Test
    void recognizesEveryPokenavColorButNotTheStandaloneFinder() {
        assertTrue(PokenavAccess.isPokenav(ResourceLocation.parse(
            "cobblenav:pokenav_item_base"
        )));
        assertTrue(PokenavAccess.isPokenav(ResourceLocation.parse(
            "cobblenav:pokenav_item_red"
        )));
        assertFalse(PokenavAccess.isPokenav(ResourceLocation.parse(
            "cobblenav:pokefinder_item_red"
        )));
    }
}
