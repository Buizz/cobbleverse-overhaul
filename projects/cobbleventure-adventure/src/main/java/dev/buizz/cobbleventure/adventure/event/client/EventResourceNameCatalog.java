package dev.buizz.cobbleventure.adventure.event.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Language-neutral content display names generated from the authoritative content project. */
final class EventResourceNameCatalog {
    private static final String RESOURCE =
        "/assets/cobbleventure_adventure/event-resource-names.json";
    private static final EventResourceNameCatalog DEFAULT = loadDefault();

    private final Map<String, Map<String, String>> resources;

    private EventResourceNameCatalog(Map<String, Map<String, String>> resources) {
        this.resources = Collections.unmodifiableMap(new TreeMap<>(resources));
    }

    static EventResourceNameCatalog defaults() {
        return DEFAULT;
    }

    static EventResourceNameCatalog parse(JsonObject root) {
        JsonElement encodedResources = root.get("resources");
        if (encodedResources == null || !encodedResources.isJsonObject()) {
            throw new IllegalArgumentException("이름 카탈로그 resources 객체가 필요합니다.");
        }
        Map<String, Map<String, String>> resources = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> resource
            : encodedResources.getAsJsonObject().entrySet()) {
            if (!resource.getValue().isJsonObject()) {
                throw new IllegalArgumentException(
                    "리소스 이름은 언어 객체여야 합니다: " + resource.getKey()
                );
            }
            Map<String, String> names = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry
                : resource.getValue().getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()
                    || entry.getValue().getAsString().isBlank()) {
                    throw new IllegalArgumentException(
                        "표시명은 비어 있지 않은 문자열이어야 합니다: " + resource.getKey()
                    );
                }
                names.put(normalize(entry.getKey()), entry.getValue().getAsString());
            }
            if (names.isEmpty()) {
                throw new IllegalArgumentException(
                    "리소스 표시명이 비어 있습니다: " + resource.getKey()
                );
            }
            resources.put(
                resource.getKey(),
                Collections.unmodifiableMap(new TreeMap<>(names))
            );
        }
        return new EventResourceNameCatalog(resources);
    }

    String resolve(String resourceId, String language) {
        Map<String, String> names = resources.get(resourceId);
        if (names == null) return null;
        String normalizedLanguage = normalize(language);
        if (names.containsKey(normalizedLanguage)) return names.get(normalizedLanguage);
        if (names.containsKey("ko_kr")) return names.get("ko_kr");
        if (names.containsKey("en_us")) return names.get("en_us");
        return names.values().iterator().next();
    }

    private static EventResourceNameCatalog loadDefault() {
        try (InputStream stream = EventResourceNameCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("이름 카탈로그 리소스를 찾을 수 없습니다: " + RESOURCE);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("이름 카탈로그를 읽지 못했습니다: " + RESOURCE, error);
        }
    }

    private static String normalize(String language) {
        if (language == null || language.isBlank()) return "en_us";
        return language.toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
