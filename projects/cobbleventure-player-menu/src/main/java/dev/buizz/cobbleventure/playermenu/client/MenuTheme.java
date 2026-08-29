package dev.buizz.cobbleventure.playermenu.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Client-resource view of the global menu presentation tokens. */
final class MenuTheme {
    private static final String RESOURCE_PATH = "dialogue_theme/global.json";

    final int background;
    final int border;
    final int innerBorder;
    final int cornerRadius;
    final int rowRadius;
    final int selectedBackground;
    final int hoverBackground;
    final int textColor;
    final int selectedTextColor;
    final int accent;
    final int shadow;
    final int shadowOffset;

    private MenuTheme(JsonObject root) {
        JsonObject menu = object(root, "menu");
        background = color(menu, "background", "#f8fbff", decimal(menu, "background_opacity", 0.98F));
        border = color(menu, "border", "#72a8d4", 1);
        innerBorder = color(menu, "inner_border", "#d9f4ff", 1);
        cornerRadius = integer(menu, "corner_radius", 14, 0, 32);
        rowRadius = integer(menu, "row_radius", 7, 0, 20);
        selectedBackground = color(menu, "selected_background", "#d9f4ff", 1);
        hoverBackground = color(menu, "hover_background", "#eaf7ff", 1);
        textColor = color(menu, "text_color", "#27323d", 1);
        selectedTextColor = color(menu, "selected_text_color", "#173f5f", 1);
        accent = color(menu, "accent", "#4f8fc2", 1);
        JsonObject panel = object(root, "panel");
        shadow = color(panel, "shadow", "#24445f", decimal(panel, "shadow_opacity", 0.45F));
        shadowOffset = integer(panel, "shadow_offset", 3, 0, 12);
    }

    static MenuTheme load(Minecraft minecraft) {
        if (minecraft != null) {
            try {
                ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                    "cobbleventure", RESOURCE_PATH
                );
                var resource = minecraft.getResourceManager().getResource(resourceId);
                if (resource.isPresent()) {
                    try (Reader reader = resource.get().openAsReader()) {
                        return new MenuTheme(JsonParser.parseReader(reader).getAsJsonObject());
                    }
                }
            } catch (Exception ignored) {
                // A malformed optional visual resource must not make the menu unusable.
            }
        }
        return new MenuTheme(new JsonObject());
    }

    static MenuTheme parse(String json) {
        try { return new MenuTheme(JsonParser.parseString(json).getAsJsonObject()); }
        catch (RuntimeException ignored) { return new MenuTheme(new JsonObject()); }
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject()
            ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static float decimal(JsonObject object, String key, float fallback) {
        try { return Math.clamp(object.has(key) ? object.get(key).getAsFloat() : fallback, 0, 1); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject object, String key, int fallback, int minimum, int maximum) {
        try { return Math.clamp(object.has(key) ? object.get(key).getAsInt() : fallback, minimum, maximum); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int color(JsonObject object, String key, String fallback, float opacity) {
        String value;
        try { value = object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { value = fallback; }
        if (!value.matches("#[0-9a-fA-F]{6}")) value = fallback;
        return (Math.round(opacity * 255) << 24) | Integer.parseInt(value.substring(1), 16);
    }
}
