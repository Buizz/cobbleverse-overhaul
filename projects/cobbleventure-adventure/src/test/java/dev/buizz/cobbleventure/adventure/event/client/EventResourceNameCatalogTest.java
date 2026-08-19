package dev.buizz.cobbleventure.adventure.event.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class EventResourceNameCatalogTest {
    @Test
    void resolvesGeneratedSettlementNamesForTheCurrentLanguage() {
        EventResourceNameCatalog catalog = EventResourceNameCatalog.defaults();

        assertEquals(
            "태초마을",
            catalog.resolve("cobbleventure:settlement/starter_town", "ko-KR")
        );
        assertEquals(
            "Pallet Town",
            catalog.resolve("cobbleventure:settlement/starter_town", "en_us")
        );
        assertEquals(
            "태초마을",
            catalog.resolve("cobbleventure:settlement/starter_town", "ja_jp")
        );
        assertNull(catalog.resolve("cobbleventure:settlement/missing", "ko_kr"));
    }

    @Test
    void fallbackOrderIsDeterministic() {
        EventResourceNameCatalog catalog = EventResourceNameCatalog.parse(
            JsonParser.parseString("""
                {"resources":{"test:place":{"fr_fr":"Ville","en_us":"Town"}}}
                """).getAsJsonObject()
        );

        assertEquals("Town", catalog.resolve("test:place", "ja_jp"));
        assertEquals("Ville", catalog.resolve("test:place", "fr-FR"));

        EventResourceNameCatalog alphabeticalFallback = EventResourceNameCatalog.parse(
            JsonParser.parseString("""
                {"resources":{"test:other":{"fr_fr":"Ville","de_de":"Stadt"}}}
                """).getAsJsonObject()
        );
        assertEquals("Stadt", alphabeticalFallback.resolve("test:other", "ja_jp"));
    }
}
