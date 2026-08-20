package dev.buizz.cobbleventure.adventure.event.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Defensive client view of the server-supplied dialogue presentation contract. */
final class EventDialogueTheme {
    private static final ResourceLocation DEFAULT_FONT = ResourceLocation.withDefaultNamespace("default");

    final ResourceLocation font;
    final float bodyScale;
    final float speakerScale;
    final float hintScale;
    final int panelBackground;
    final int panelAccent;
    final int panelInnerBorder;
    final int panelBorderWidth;
    final int panelInnerBorderWidth;
    final int panelCornerRadius;
    final int panelShadow;
    final int panelShadowOffset;
    final int speakerColor;
    final int textColor;
    final int hintColor;
    final int pageColor;
    final float panelHeightRatio;
    final int panelMinHeight;
    final int panelMaxHeight;
    final int choiceSelectedBackground;
    final int choiceHoverBackground;
    final int choiceBackground;
    final int choiceSelectedAccent;
    final int choiceTextColor;
    final int choiceRowHeight;
    final int choicePanelBackground;
    final int choicePanelBorder;
    final int choicePanelInnerBorder;
    final int choiceCornerRadius;
    final int choicePanelWidth;
    final int choicePanelGap;
    final int choicePanelPadding;
    final int menuBackground;
    final int menuBorder;
    final int menuInnerBorder;
    final int menuCornerRadius;
    final int menuRowRadius;
    final int menuSelectedBackground;
    final int menuHoverBackground;
    final int menuTextColor;
    final int menuSelectedTextColor;
    final int menuAccent;
    final float portraitYaw;
    final float portraitPitch;
    final float portraitScale;
    final int portraitBackground;
    final int portraitAccent;

    private EventDialogueTheme(JsonObject root) {
        JsonObject fonts = object(root, "font");
        ResourceLocation parsedFont = ResourceLocation.tryParse(string(fonts, "resource", "minecraft:default"));
        font = parsedFont == null ? DEFAULT_FONT : parsedFont;
        bodyScale = decimal(fonts, "body_scale", 1.0F, 0.5F, 2.0F);
        speakerScale = decimal(fonts, "speaker_scale", 1.0F, 0.5F, 2.0F);
        hintScale = decimal(fonts, "hint_scale", 0.85F, 0.5F, 2.0F);

        JsonObject panel = object(root, "panel");
        panelBackground = color(panel, "background", "#f8fbff", decimal(panel, "background_opacity", 0.98F, 0, 1));
        panelAccent = color(panel, "border", "#72a8d4", 1);
        panelInnerBorder = color(panel, "inner_border", "#d9f4ff", 1);
        panelBorderWidth = integer(panel, "border_width", 3, 1, 8);
        panelInnerBorderWidth = integer(panel, "inner_border_width", 2, 0, 8);
        panelCornerRadius = integer(panel, "corner_radius", 18, 0, 32);
        panelShadow = color(panel, "shadow", "#24445f", decimal(panel, "shadow_opacity", 0.45F, 0, 1));
        panelShadowOffset = integer(panel, "shadow_offset", 3, 0, 12);
        speakerColor = color(panel, "speaker_color", "#c52b2b", 1);
        textColor = color(panel, "text_color", "#27323d", 1);
        hintColor = color(panel, "hint_color", "#57758e", 1);
        pageColor = color(panel, "page_color", "#72a8d4", 1);
        panelHeightRatio = decimal(panel, "height_ratio", 0.333F, 0.2F, 0.7F);
        panelMinHeight = integer(panel, "min_height", 112, 80, 300);
        panelMaxHeight = Math.max(panelMinHeight, integer(panel, "max_height", 166, 100, 400));

        JsonObject choice = object(root, "choice");
        choicePanelBackground = color(choice, "panel_background", "#f8fbff", decimal(choice, "panel_opacity", 0.98F, 0, 1));
        choicePanelBorder = color(choice, "panel_border", "#72a8d4", 1);
        choicePanelInnerBorder = color(choice, "panel_inner_border", "#d9f4ff", 1);
        choiceCornerRadius = integer(choice, "corner_radius", 12, 0, 28);
        choicePanelWidth = integer(choice, "panel_width", 190, 100, 360);
        choicePanelGap = integer(choice, "panel_gap", 8, 0, 32);
        choicePanelPadding = integer(choice, "panel_padding", 10, 4, 24);
        choiceSelectedBackground = color(choice, "selected_background", "#d9f4ff", 1);
        choiceHoverBackground = color(choice, "hover_background", "#eaf7ff", 1);
        choiceBackground = color(choice, "background", "#f8fbff", 1);
        choiceSelectedAccent = color(choice, "selected_accent", "#4f8fc2", 1);
        choiceTextColor = color(choice, "text_color", "#27323d", 1);
        choiceRowHeight = integer(choice, "row_height", 24, 18, 48);

        JsonObject menu = object(root, "menu");
        menuBackground = color(menu, "background", "#f8fbff", decimal(menu, "background_opacity", 0.98F, 0, 1));
        menuBorder = color(menu, "border", "#72a8d4", 1);
        menuInnerBorder = color(menu, "inner_border", "#d9f4ff", 1);
        menuCornerRadius = integer(menu, "corner_radius", 14, 0, 32);
        menuRowRadius = integer(menu, "row_radius", 7, 0, 20);
        menuSelectedBackground = color(menu, "selected_background", "#d9f4ff", 1);
        menuHoverBackground = color(menu, "hover_background", "#eaf7ff", 1);
        menuTextColor = color(menu, "text_color", "#27323d", 1);
        menuSelectedTextColor = color(menu, "selected_text_color", "#173f5f", 1);
        menuAccent = color(menu, "accent", "#4f8fc2", 1);

        JsonObject portrait = object(root, "portrait");
        portraitYaw = decimal(portrait, "yaw_degrees", 18, -35, 35);
        portraitPitch = decimal(portrait, "pitch_degrees", -4, -20, 20);
        portraitScale = decimal(portrait, "scale", 1, 0.6F, 1.5F);
        portraitBackground = color(portrait, "background", "#0a1017", decimal(portrait, "background_opacity", 0.72F, 0, 1));
        portraitAccent = color(portrait, "accent", "#5e7789", 1);
    }

    static EventDialogueTheme parse(String json) {
        try {
            return new EventDialogueTheme(JsonParser.parseString(json).getAsJsonObject());
        } catch (RuntimeException ignored) {
            return new EventDialogueTheme(new JsonObject());
        }
    }

    Component text(String value) {
        return Component.literal(value).withStyle(style -> style.withFont(font));
    }

    Component text(Component value) {
        return value.copy().withStyle(style -> style.withFont(font));
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static float decimal(JsonObject object, String key, float fallback, float minimum, float maximum) {
        float value;
        try { value = object.has(key) ? object.get(key).getAsFloat() : fallback; }
        catch (RuntimeException ignored) { value = fallback; }
        return Float.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback, int minimum, int maximum) {
        int value;
        try { value = object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { value = fallback; }
        return Math.clamp(value, minimum, maximum);
    }

    private static int color(JsonObject object, String key, String fallback, float opacity) {
        String value = string(object, key, fallback);
        if (!value.matches("#[0-9a-fA-F]{6}")) value = fallback;
        int rgb = Integer.parseInt(value.substring(1), 16);
        return (Math.round(Math.clamp(opacity, 0, 1) * 255) << 24) | rgb;
    }
}
