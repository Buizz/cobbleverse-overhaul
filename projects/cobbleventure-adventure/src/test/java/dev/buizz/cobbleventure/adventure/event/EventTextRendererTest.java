package dev.buizz.cobbleventure.adventure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EventTextRendererTest {
    private final EventTextRenderer renderer = new EventTextRenderer(
        (resourceId, language) -> resourceId.equals("cobblemon:bulbasaur")
            ? language.equals("ko_kr") ? "이상해씨" : "Bulbasaur"
            : "<" + resourceId + ">"
    );

    @Test
    void selectsCurrentLanguageAndFallsBackDeterministically() {
        JsonObject text = localized(
            "en_us", "Hello, ${player.name}!",
            "ko_kr", "안녕, ${player.name}!"
        );
        Map<String, com.google.gson.JsonElement> locals = Map.of(
            "player", object("name", new JsonPrimitive("레드"))
        );

        assertEquals("안녕, 레드!", renderer.render(text, locals, "ko_KR"));
        assertEquals("안녕, 레드!", renderer.render(text, locals, "ja_jp"));
        assertEquals("Hello, 레드!", renderer.render(text, locals, "en-us"));
    }

    @Test
    void rendersResourceNamesNumbersFallbackAndKoreanJosa() {
        JsonObject starter = new JsonObject();
        starter.addProperty("species_id", "cobblemon:bulbasaur");
        starter.addProperty("level", 5);
        Map<String, com.google.gson.JsonElement> locals = new LinkedHashMap<>();
        locals.put("starter", starter);
        locals.put("reward", new JsonPrimitive(new BigDecimal("12345.50")));
        locals.put("nickname", JsonNull.INSTANCE);
        locals.put("species", new JsonPrimitive("cobblemon:bulbasaur"));
        JsonObject text = literal(
            "${starter.name|josa:을/를} 골랐다. "
                + "${reward|number}원 / ${nickname|fallback:없음} / ${species|name}"
        );

        assertEquals(
            "이상해씨를 골랐다. 12,345.5원 / 없음 / 이상해씨",
            renderer.render(text, locals, "ko_kr")
        );
        assertEquals(
            "Bulbasaur를 골랐다. 12,345.5원 / 없음 / Bulbasaur",
            renderer.render(text, locals, "en_us")
        );
    }

    @Test
    void rendersLocationResourceNameAndKoreanJosa() {
        EventTextRenderer locationRenderer = new EventTextRenderer(
            (resourceId, language) -> resourceId.equals(
                "cobbleventure:settlement/starter_town"
            ) ? language.equals("ko_kr") ? "태초마을" : "Pallet Town" : resourceId
        );
        JsonObject destination = new JsonObject();
        destination.addProperty("kind", "settlement");
        destination.addProperty("resource_id", "cobbleventure:settlement/starter_town");

        assertEquals(
            "태초마을을 목적지로 선택했어.",
            locationRenderer.render(
                literal("${destination.name|josa:을/를} 목적지로 선택했어."),
                Map.of("destination", destination),
                "ko_kr"
            )
        );
        assertEquals(
            "Pallet Town를 목적지로 선택했어.",
            locationRenderer.render(
                literal("${destination.name|josa:을/를} 목적지로 선택했어."),
                Map.of("destination", destination),
                "en_us"
            )
        );
    }

    @Test
    void rejectsExecutableOrIllTypedTemplateContent() {
        assertThrows(
            EventRuntimeException.class,
            () -> renderer.render(literal("${money()}"), Map.of(), "ko_kr")
        );
        assertThrows(
            EventRuntimeException.class,
            () -> renderer.render(
                literal("${value|number}"),
                Map.of("value", new JsonPrimitive("twelve")),
                "en_us"
            )
        );
        assertThrows(
            EventRuntimeException.class,
            () -> renderer.render(literal("${missing}"), Map.of(), "en_us")
        );
    }

    @Test
    void detectsHangulFinalConsonantsForSupportedJosaPairs() {
        assertTrue(EventTextRenderer.hasFinalConsonant("피카츄와 레온"));
        assertFalse(EventTextRenderer.hasFinalConsonant("이상해씨"));
        assertFalse(EventTextRenderer.hasFinalConsonant("Bulbasaur"));
    }

    private static JsonObject literal(String value) {
        JsonObject text = new JsonObject();
        text.addProperty("kind", "literal");
        text.addProperty("value", value);
        return text;
    }

    private static JsonObject localized(String... entries) {
        JsonObject text = new JsonObject();
        text.addProperty("kind", "localized");
        JsonArray values = new JsonArray();
        for (int index = 0; index < entries.length; index += 2) {
            JsonObject entry = new JsonObject();
            entry.addProperty("language", entries[index]);
            entry.addProperty("value", entries[index + 1]);
            values.add(entry);
        }
        text.add("entries", values);
        return text;
    }

    private static JsonObject object(String name, com.google.gson.JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(name, value);
        return object;
    }
}
