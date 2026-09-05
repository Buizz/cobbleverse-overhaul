package dev.buizz.cobbleventure.playermenu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class KeyItemsTagTest {
    @Test
    void pokenavIsCategorizedAsAKeyItem() throws Exception {
        try (var stream = getClass().getResourceAsStream(
            "/data/cobbleventure_player_menu/tags/item/key_items.json"
        )) {
            String tag = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(tag.contains("\"id\": \"cobblenav:pokenav_item_red\""));
        }
    }
}
