package dev.buizz.cobbleventure.playermenu.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Shared button widget rendered exclusively from global menu theme tokens. */
public final class ThemedButton extends AbstractButton {
    private final MenuTheme theme;
    private final MenuTheme.ButtonVariant variant;
    private final Runnable action;
    private final BooleanSupplier selected;

    public ThemedButton(
        MenuTheme theme,
        Component message,
        int x,
        int y,
        int width,
        int height,
        MenuTheme.ButtonVariant variant,
        Runnable action
    ) {
        this(theme, message, x, y, width, height, variant, action, () -> false);
    }

    public ThemedButton(
        MenuTheme theme,
        Component message,
        int x,
        int y,
        int width,
        int height,
        MenuTheme.ButtonVariant variant,
        Runnable action,
        BooleanSupplier selected
    ) {
        super(x, y, width, height, message);
        this.theme = Objects.requireNonNull(theme, "theme");
        this.variant = Objects.requireNonNull(variant, "variant");
        this.action = Objects.requireNonNull(action, "action");
        this.selected = Objects.requireNonNull(selected, "selected");
    }

    @Override public void onPress() {
        if (active) action.run();
    }

    @Override protected void renderWidget(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        MenuTheme.ButtonStyle style = theme.button(
            variant, active, isHoveredOrFocused(), selected.getAsBoolean()
        );
        int radius = Math.min(theme.rowRadius, getHeight() / 2);
        ThemedOverlayPanel.fillRoundedRect(
            graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
            radius, style.border()
        );
        ThemedOverlayPanel.fillRoundedRect(
            graphics, getX() + 1, getY() + 1,
            getX() + getWidth() - 1, getY() + getHeight() - 1,
            Math.max(0, radius - 1), style.background()
        );
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        int textY = getY() + Math.max(
            1, (getHeight() - theme.textHeight(font, MenuTheme.TextRole.LABEL)) / 2
        );
        theme.drawCenteredText(
            graphics, font, getMessage(), getX() + getWidth() / 2,
            textY, MenuTheme.TextRole.LABEL, style.text()
        );
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
