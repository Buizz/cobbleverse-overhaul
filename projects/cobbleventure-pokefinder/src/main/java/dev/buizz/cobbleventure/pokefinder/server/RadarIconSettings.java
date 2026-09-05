package dev.buizz.cobbleventure.pokefinder.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Data-authored mapping from semantic marker categories to built-in radar glyphs. */
public final class RadarIconSettings {
    private static final Map<String, Entry> ENTRIES = load();

    private RadarIconSettings() {}

    public static Entry resolve(String category, String fallbackIcon) {
        return ENTRIES.getOrDefault(category, Entry.fallback(fallbackIcon));
    }

    public static Entry style(String icon) {
        return ENTRIES.get(icon.toUpperCase(Locale.ROOT));
    }

    private static Map<String, Entry> load() {
        try (var stream = RadarIconSettings.class.getResourceAsStream(
            "/data/cobbleventure/pokefinder_icons.json"
        )) {
            if (stream == null) return Map.of();
            JsonObject root = JsonParser.parseReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8
            )).getAsJsonObject();
            Map<String, Entry> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> item
                : root.getAsJsonObject("categories").entrySet()) {
                JsonObject value = item.getValue().getAsJsonObject();
                String category = item.getKey().toUpperCase(Locale.ROOT);
                java.util.List<String> pixels = new java.util.ArrayList<>();
                for (JsonElement row : value.getAsJsonArray("pixels")) {
                    pixels.add(row.getAsString());
                }
                result.put(category, new Entry(
                    category.toLowerCase(Locale.ROOT),
                    parseColor(value.get("primary").getAsString()),
                    parseColor(value.get("secondary").getAsString()),
                    parseColor(value.get("outline").getAsString()),
                    java.util.List.copyOf(pixels)
                ));
            }
            return Map.copyOf(result);
        } catch (RuntimeException | java.io.IOException ignored) {
            return Map.of();
        }
    }

    private static int parseColor(String value) {
        return 0xFF000000 | Integer.parseInt(value.substring(1), 16);
    }

    public record Entry(
        String icon, int primary, int secondary, int outline,
        java.util.List<String> pixels
    ) {
        static Entry fallback(String icon) {
            return new Entry(icon, 0xFF62E6FF, 0xFFF7FBFF, 0xFF101820, java.util.List.of());
        }
    }
}
