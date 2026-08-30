package dev.buizz.cobbleventure.adventure.daycare.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Reads the global menu theme shared by the player menu, bag, shop, and daycare. */
final class DaycareMenuTheme {
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

    private DaycareMenuTheme(JsonObject root) {
        JsonObject menu = object(root, "menu");
        background = color(menu, "background", "#f8fbff", decimal(menu, "background_opacity", .98F));
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
        shadow = color(panel, "shadow", "#24445f", decimal(panel, "shadow_opacity", .45F));
        shadowOffset = integer(panel, "shadow_offset", 3, 0, 12);
    }

    static DaycareMenuTheme load(Minecraft minecraft) {
        if (minecraft != null) {
            try {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "cobbleventure", "dialogue_theme/global.json"
                );
                var resource = minecraft.getResourceManager().getResource(id);
                if (resource.isPresent()) try (Reader reader = resource.get().openAsReader()) {
                    return new DaycareMenuTheme(JsonParser.parseReader(reader).getAsJsonObject());
                }
            } catch (Exception ignored) {}
        }
        return new DaycareMenuTheme(new JsonObject());
    }

    int mutedText() { return DaycareThemedPanel.withOpacity(textColor, .62F); }

    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject()
            ? root.getAsJsonObject(key) : new JsonObject();
    }

    private static float decimal(JsonObject object, String key, float fallback) {
        try { return Math.clamp(object.has(key) ? object.get(key).getAsFloat() : fallback, 0, 1); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        try { return Math.clamp(object.has(key) ? object.get(key).getAsInt() : fallback, min, max); }
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
