package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.playermenu.client.MenuBackButton;
import dev.buizz.cobbleventure.playermenu.client.MenuTheme;
import dev.buizz.cobbleventure.playermenu.client.ThemedOverlayPanel;
import dev.buizz.cobbleventure.pokefinder.client.RadarDisplaySettings.Option;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pokefinder-styled exploration marker settings. */
public final class RadarSettingsScreen extends Screen {
    private static final int WIDTH = 288;
    private static final int HEIGHT = 216;
    private final Screen parent;
    private final MenuTheme theme;
    private int left;
    private int top;

    public RadarSettingsScreen(Screen parent) {
        super(Component.literal("탐색 정보"));
        this.parent = parent;
        this.theme = MenuTheme.load(net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
        Option[] categories = {
            Option.PLAYERS, Option.TRAINERS, Option.IMPORTANT_NPCS, Option.FACILITIES,
            Option.ENTRANCES, Option.OBJECTIVES
        };
        for (int index = 0; index < categories.length; index++) {
            addToggle(categories[index], left + 12, top + 36 + index * 24);
        }
        Option[] details = {
            Option.NAMES, Option.DISTANCES, Option.DEFEATED_TRAINERS
        };
        for (int index = 0; index < details.length; index++) {
            addToggle(details[index], left + 152, top + 36 + index * 24);
        }
        addRenderableWidget(new MenuBackButton(
            theme, left + 190, top + 186, 86, 18, this::onClose
        ));
    }

    private void addToggle(Option option, int x, int y) {
        ThemeButton button = new ThemeButton(
            Component.empty(), x, y, 124, 18,
            MenuTheme.ButtonVariant.SECONDARY, () -> {
                RadarDisplaySettings.toggle(option);
                rebuildWidgets();
            }
        );
        button.setMessage(message(option));
        addRenderableWidget(button);
    }

    private static Component message(Option option) {
        return Component.literal(option.label() + ": "
            + (RadarDisplaySettings.value(option) ? "켜짐" : "꺼짐"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, theme.scrim);
        ThemedOverlayPanel.draw(
            graphics, theme, left, top, WIDTH, HEIGHT, 1, theme.accent
        );
        theme.drawCenteredText(
            graphics, font, title, left + WIDTH / 2, top + 10,
            MenuTheme.TextRole.TITLE, theme.text(MenuTheme.TextRole.TITLE).color()
        );
        theme.drawText(graphics, font, Component.literal("마커 종류"),
            left + 12, top + 25, MenuTheme.TextRole.HEADING);
        theme.drawText(graphics, font, Component.literal("표시 옵션"),
            left + 152, top + 25, MenuTheme.TextRole.HEADING);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class ThemeButton extends AbstractButton {
        private final MenuTheme.ButtonVariant variant;
        private final Runnable action;

        private ThemeButton(
            Component message, int x, int y, int width, int height,
            MenuTheme.ButtonVariant variant, Runnable action
        ) {
            super(x, y, width, height, message);
            this.variant = variant;
            this.action = action;
        }

        @Override public void onPress() { if (active) action.run(); }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            MenuTheme.ButtonStyle style = theme.button(
                variant, active, isHoveredOrFocused(), false
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
            theme.drawCenteredText(
                graphics, font, getMessage(), getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2,
                MenuTheme.TextRole.LABEL, style.text()
            );
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
