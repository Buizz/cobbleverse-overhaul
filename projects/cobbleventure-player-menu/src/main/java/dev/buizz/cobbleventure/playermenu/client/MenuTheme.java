package dev.buizz.cobbleventure.playermenu.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Client-resource view of the global menu presentation tokens. */
public final class MenuTheme {
    private static final String RESOURCE_PATH = "dialogue_theme/global.json";

    public final int background;
    public final int border;
    public final int innerBorder;
    public final int cornerRadius;
    public final int rowRadius;
    public final int selectedBackground;
    public final int hoverBackground;
    public final int textColor;
    public final int selectedTextColor;
    public final int accent;
    public final int shadow;
    public final int shadowOffset;
    public final int cardBackground;
    public final int inputBackground;
    public final int secondaryTextColor;
    public final int mutedTextColor;
    public final int textOnAccent;
    public final int danger;
    public final int success;
    public final int warning;
    public final int scrim;
    public final int disabledBackground;
    public final int disabledBorder;
    public final int disabledText;
    public final ResourceLocation fontResource;
    private final TextStyle titleText;
    private final TextStyle headingText;
    private final TextStyle bodyText;
    private final TextStyle labelText;
    private final TextStyle captionText;
    private final JsonObject buttons;

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
        cardBackground = color(menu, "card_background", "#f8fbff", 1);
        inputBackground = color(menu, "input_background", "#f8fbff", 1);
        secondaryTextColor = color(menu, "secondary_text_color", "#57758e", 1);
        mutedTextColor = color(menu, "muted_text_color", "#7b8d9a", 1);
        textOnAccent = color(menu, "text_on_accent", "#ffffff", 1);
        danger = color(menu, "danger", "#c94b52", 1);
        success = color(menu, "success", "#2f7d56", 1);
        warning = color(menu, "warning", "#d38a2e", 1);
        scrim = color(menu, "scrim", "#07121c", decimal(menu, "scrim_opacity", .52F));
        disabledBackground = color(menu, "disabled_background", "#aeb9c2", 1);
        disabledBorder = color(menu, "disabled_border", "#8796a2", 1);
        disabledText = color(menu, "disabled_text", "#5f6b74", 1);
        JsonObject panel = object(root, "panel");
        shadow = color(panel, "shadow", "#24445f", decimal(panel, "shadow_opacity", 0.45F));
        shadowOffset = integer(panel, "shadow_offset", 3, 0, 12);
        JsonObject font = object(root, "font");
        fontResource = resource(font, "resource", "minecraft:default");
        JsonObject typography = object(root, "typography");
        titleText = textStyle(typography, "title", 1.25F, false, selectedTextColor);
        headingText = textStyle(typography, "heading", 1.0F, false, selectedTextColor);
        bodyText = textStyle(typography, "body", 1.0F, false, textColor);
        labelText = textStyle(typography, "label", .9F, false, textColor);
        captionText = textStyle(typography, "caption", .8F, false, secondaryTextColor);
        buttons = object(root, "buttons");
    }

    public static MenuTheme load(Minecraft minecraft) {
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

    public static MenuTheme parse(String json) {
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

    private static ResourceLocation resource(JsonObject object, String key, String fallback) {
        try {
            ResourceLocation parsed = ResourceLocation.tryParse(
                object.has(key) ? object.get(key).getAsString() : fallback
            );
            return parsed == null ? ResourceLocation.parse(fallback) : parsed;
        } catch (RuntimeException ignored) {
            return ResourceLocation.parse(fallback);
        }
    }

    private TextStyle textStyle(
        JsonObject typography, String key, float scale, boolean shadow, int color
    ) {
        JsonObject style = object(typography, key);
        return new TextStyle(
            decimal(style, "scale", scale, .5F, 2.0F),
            bool(style, "shadow", shadow),
            style.has("color") ? color(style, "color", "#ffffff", 1) : color
        );
    }

    public TextStyle text(TextRole role) {
        return switch (role) {
            case TITLE -> titleText;
            case HEADING -> headingText;
            case BODY -> bodyText;
            case LABEL -> labelText;
            case CAPTION -> captionText;
        };
    }

    public int drawText(
        GuiGraphics graphics, Font font, Component text,
        int x, int y, TextRole role
    ) {
        return drawText(graphics, font, text, x, y, role, text(role).color());
    }

    public int drawText(
        GuiGraphics graphics, Font font, Component text,
        int x, int y, TextRole role, int color
    ) {
        TextStyle style = text(role);
        Component styled = text.copy().withStyle(value -> value.withFont(fontResource));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(style.scale(), style.scale(), 1);
        graphics.drawString(font, styled, 0, 0, color, style.shadow());
        graphics.pose().popPose();
        return Math.round(font.width(styled) * style.scale());
    }

    public int textWidth(Font font, Component text, TextRole role) {
        Component styled = text.copy().withStyle(value -> value.withFont(fontResource));
        return Math.round(font.width(styled) * text(role).scale());
    }

    public int textHeight(Font font, TextRole role) {
        return Math.round(font.lineHeight * text(role).scale());
    }

    /** Draws wrapped text while preserving the configured font, scale, and shadow role. */
    public int drawWrappedText(
        GuiGraphics graphics, Font font, Component text,
        int x, int y, int width, TextRole role, int color, int maximumLines
    ) {
        TextStyle style = text(role);
        Component styled = text.copy().withStyle(value -> value.withFont(fontResource));
        int unscaledWidth = Math.max(1, (int)Math.floor(width / style.scale()));
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(styled, unscaledWidth);
        int count = Math.min(Math.max(0, maximumLines), lines.size());
        int lineAdvance = font.lineHeight + 2;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(style.scale(), style.scale(), 1);
        for (int index = 0; index < count; index++) {
            graphics.drawString(
                font, lines.get(index), 0, index * lineAdvance, color, style.shadow()
            );
        }
        graphics.pose().popPose();
        return Math.round(count * lineAdvance * style.scale());
    }

    public int drawCenteredText(
        GuiGraphics graphics, Font font, Component text,
        int centerX, int y, TextRole role, int color
    ) {
        TextStyle style = text(role);
        Component styled = text.copy().withStyle(value -> value.withFont(fontResource));
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0);
        graphics.pose().scale(style.scale(), style.scale(), 1);
        graphics.drawString(
            font, styled, -font.width(styled) / 2, 0, color, style.shadow()
        );
        graphics.pose().popPose();
        return Math.round(font.width(styled) * style.scale());
    }

    public ButtonStyle button(
        ButtonVariant variant, boolean active, boolean hovered, boolean selected
    ) {
        if (!active) return new ButtonStyle(disabledBackground, disabledBorder, disabledText);
        String key = variant.name().toLowerCase(java.util.Locale.ROOT);
        JsonObject variantTokens = object(buttons, key);
        int baseFill = switch (variant) {
            case PRIMARY -> accent;
            case SECONDARY -> background;
            case DANGER -> danger;
            case GHOST -> 0x00000000;
        };
        int baseBorder = switch (variant) {
            case PRIMARY -> accent;
            case DANGER -> danger;
            default -> border;
        };
        int baseText = variant == ButtonVariant.PRIMARY || variant == ButtonVariant.DANGER
            ? textOnAccent : textColor;
        String state = selected ? "selected" : hovered ? "hover" : "normal";
        JsonObject stateTokens = object(variantTokens, state);
        int fallbackFill = selected ? selectedBackground : hovered ? hoverBackground : baseFill;
        return new ButtonStyle(
            optionalColor(stateTokens, "background", fallbackFill),
            optionalColor(stateTokens, "border", baseBorder),
            optionalColor(stateTokens, "text", baseText)
        );
    }

    public int mutedText() {
        return mutedTextColor;
    }

    private static int optionalColor(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        String value;
        try { value = object.get(key).getAsString(); }
        catch (RuntimeException ignored) { return fallback; }
        if (!value.matches("#[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(value.substring(1), 16);
    }

    private static float decimal(
        JsonObject object, String key, float fallback, float minimum, float maximum
    ) {
        try {
            float value = object.has(key) ? object.get(key).getAsFloat() : fallback;
            return Math.clamp(value, minimum, maximum);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    public enum TextRole { TITLE, HEADING, BODY, LABEL, CAPTION }
    public enum ButtonVariant { PRIMARY, SECONDARY, DANGER, GHOST }
    public record TextStyle(float scale, boolean shadow, int color) {}
    public record ButtonStyle(int background, int border, int text) {}
}
