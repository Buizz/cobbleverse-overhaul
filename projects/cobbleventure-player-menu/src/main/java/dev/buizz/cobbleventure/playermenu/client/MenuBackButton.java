package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Shared back button for dismissible Cobbleventure menu screens. */
public final class MenuBackButton extends AbstractButton {
    public static final Component LABEL = Component.translatable(
        "screen.cobbleventure_player_menu.common.back"
    );

    private final MenuTheme theme;
    private final Runnable action;

    public MenuBackButton(
        MenuTheme theme, int x, int y, int width, int height, Runnable action
    ) {
        super(x, y, width, height, LABEL);
        this.theme = theme;
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        MenuTheme.ButtonStyle style = theme.button(
            MenuTheme.ButtonVariant.SECONDARY, active, isHoveredOrFocused(), false
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
        var font = Minecraft.getInstance().font;
        theme.drawCenteredText(
            graphics, font, getMessage(), getX() + getWidth() / 2,
            getY() + (getHeight() - font.lineHeight) / 2,
            MenuTheme.TextRole.LABEL, style.text()
        );
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
