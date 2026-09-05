package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class RadarLocationCatalogTest {
    @Test
    void locationKeepsAuthoredCategoryAndLabel() {
        RadarLocationCatalog.Location location = new RadarLocationCatalog.Location(
            "building/department_store", RadarLocationCatalog.Kind.POKEMART,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            1.5D, 64.0D, 2.5D, "포켓몬상점", "celadon"
        );
        assertEquals(RadarLocationCatalog.Kind.POKEMART, location.kind());
        assertEquals("포켓몬상점", location.label());
    }
}
