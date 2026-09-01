package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Quantity picker shared by dropping and permanently deleting a bag item. */
final class BagDiscardScreen extends Screen {
    enum Mode { DROP, DELETE }

    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 144;

    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack stack;
    private final int maximum;
    private final Mode mode;
    private final MenuTheme theme;
    private EditBox quantityBox;
    private AbstractButton confirmButton;
    private int panelX;
    private int panelY;

    BagDiscardScreen(BagScreen parent, boolean extended, int sourceSlot, ItemStack stack, int maximum, Mode mode) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag."
            + (mode == Mode.DROP ? "drop_select.title" : "delete_select.title")));
        this.parent = parent;
        this.extended = extended;
        this.sourceSlot = sourceSlot;
        this.stack = stack;
        this.maximum = Math.max(1, maximum);
        this.mode = mode;
        this.theme = MenuTheme.load(net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        quantityBox = new QuantityEditBox(panelX + 91, panelY + 48, 78, 20,
            Component.translatable("screen.cobbleventure_player_menu.bag.discard_quantity"));
        quantityBox.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        quantityBox.setMaxLength(9);
        quantityBox.setValue("1");
        quantityBox.setBordered(false);
        quantityBox.setTextColor(theme.textColor);
        quantityBox.setTextColorUneditable(theme.disabledText);
        quantityBox.setResponder(ignored -> updateConfirmButton());
        addRenderableWidget(quantityBox);

        addRenderableWidget(new DiscardButton(Component.literal("−"), panelX + 65, panelY + 48,
            22, 20, ButtonTone.NEUTRAL, () -> adjust(-1)));
        addRenderableWidget(new DiscardButton(Component.literal("+"), panelX + 173, panelY + 48,
            22, 20, ButtonTone.NEUTRAL, () -> adjust(1)));

        int presetY = panelY + 75;
        addPreset(Component.literal("1"), 1, panelX + 10, presetY);
        addPreset(Component.literal("10"), Math.min(10, maximum), panelX + 60, presetY);
        addPreset(Component.translatable("screen.cobbleventure_player_menu.bag.discard_half"),
            Math.max(1, maximum / 2), panelX + 110, presetY);
        addPreset(Component.translatable("screen.cobbleventure_player_menu.bag.discard_all"),
            maximum, panelX + 160, presetY);

        confirmButton = addRenderableWidget(new DiscardButton(
            Component.translatable("screen.cobbleventure_player_menu.bag."
                + (mode == Mode.DROP ? "drop_confirm" : "delete_confirm")),
            panelX + 82, panelY + 110, 82, 20,
            mode == Mode.DROP ? ButtonTone.PRIMARY : ButtonTone.DANGER, this::confirm));
        addRenderableWidget(new DiscardButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.cancel"),
            panelX + 170, panelY + 110, 80, 20, ButtonTone.NEUTRAL, this::onClose));
        setInitialFocus(quantityBox);
        updateConfirmButton();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, theme.scrim);
        drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.fill(panelX + 8, panelY + 7, panelX + PANEL_WIDTH - 8, panelY + 34,
            theme.selectedBackground);
        graphics.fill(panelX + 8, panelY + 33, panelX + PANEL_WIDTH - 8, panelY + 34,
            theme.border);
        graphics.fill(panelX + 8, panelY + 7, panelX + 29, panelY + 30,
            theme.cardBackground);
        graphics.fill(panelX + 9, panelY + 8, panelX + 28, panelY + 29,
            theme.innerBorder);
        graphics.renderItem(stack, panelX + 10, panelY + 10);
        theme.drawText(graphics, font, title, panelX + 32, panelY + 9,
            MenuTheme.TextRole.HEADING);
        theme.drawText(graphics, font, Component.literal(font.plainSubstrByWidth(
            stack.getHoverName().getString(), PANEL_WIDTH - 44
        )), panelX + 32, panelY + 21, MenuTheme.TextRole.CAPTION);
        Component owned = Component.translatable(
            "screen.cobbleventure_player_menu.bag.discard_owned", maximum
        );
        graphics.drawString(font, owned,
            panelX + (PANEL_WIDTH - font.width(owned)) / 2, panelY + 36,
            theme.mutedTextColor, false);
        drawInputFrame(graphics, panelX + 89, panelY + 46, 82, 24,
            quantityBox.isFocused(), validQuantity() > 0);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_player_menu.bag."
                + (mode == Mode.DROP ? "drop_warning" : "delete_warning")),
            panelX + 10, panelY + 98,
            mode == Mode.DROP ? theme.accent : theme.danger, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawQuantity(graphics);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && validQuantity() > 0) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void addPreset(Component label, int value, int x, int y) {
        addRenderableWidget(new DiscardButton(
            label, x, y, 46, 20, ButtonTone.NEUTRAL, () -> setQuantity(value)
        ));
    }

    private void adjust(int delta) {
        setQuantity(clamp(validQuantity() + delta, 1, maximum));
    }

    private void setQuantity(int value) {
        quantityBox.setValue(Integer.toString(clamp(value, 1, maximum)));
    }

    private int validQuantity() {
        try {
            int value = Integer.parseInt(quantityBox.getValue());
            return value >= 1 && value <= maximum ? value : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void updateConfirmButton() {
        if (confirmButton != null) confirmButton.active = validQuantity() > 0;
    }

    private void confirm() {
        int quantity = validQuantity();
        if (quantity <= 0) return;
        if (mode == Mode.DROP) {
            PlayerMenuClient.dropBagItem(extended, sourceSlot, quantity);
            parent.dropRequested(quantity);
        } else {
            PlayerMenuClient.discardBagItem(extended, sourceSlot, quantity);
            parent.deleteRequested(quantity);
        }
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        ThemedOverlayPanel.draw(graphics, theme, x, y, width, height, 1, theme.accent);
    }

    private void drawInputFrame(
        GuiGraphics graphics, int x, int y, int width, int height, boolean focused, boolean valid
    ) {
        int border = !valid ? theme.danger : focused ? theme.accent : theme.border;
        graphics.fill(x + 2, y, x + width - 2, y + height, border);
        graphics.fill(x, y + 2, x + width, y + height - 2, border);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2,
            theme.inputBackground);
        graphics.fill(x + 5, y + height - 4, x + width - 5, y + height - 3,
            theme.innerBorder);
    }

    private void drawQuantity(GuiGraphics graphics) {
        String value = quantityBox.getValue();
        int textWidth = font.width(value);
        int textX = panelX + (PANEL_WIDTH - textWidth) / 2;
        int textY = panelY + 54;
        graphics.drawString(font, value, textX, textY, theme.textColor, false);
        if (quantityBox.isFocused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = textX + textWidth + 1;
            graphics.fill(cursorX, textY - 1, cursorX + 1,
                textY + font.lineHeight + 1, theme.textColor);
        }
    }

    private enum ButtonTone { PRIMARY, DANGER, NEUTRAL }

    private final class DiscardButton extends AbstractButton {
        private final ButtonTone tone;
        private final Runnable action;

        private DiscardButton(
            Component message, int x, int y, int width, int height, ButtonTone tone, Runnable action
        ) {
            super(x, y, width, height, message);
            this.tone = tone;
            this.action = action;
        }

        @Override
        public void onPress() {
            if (active) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            MenuTheme.ButtonVariant variant = switch (tone) {
                case PRIMARY -> MenuTheme.ButtonVariant.PRIMARY;
                case DANGER -> MenuTheme.ButtonVariant.DANGER;
                case NEUTRAL -> MenuTheme.ButtonVariant.SECONDARY;
            };
            MenuTheme.ButtonStyle style = theme.button(
                variant, active, isHoveredOrFocused(), false
            );
            ThemedOverlayPanel.fillRoundedRect(
                graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                theme.rowRadius, style.border()
            );
            ThemedOverlayPanel.fillRoundedRect(
                graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), style.background()
            );
            theme.drawCenteredText(
                graphics, font, getMessage(), getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2,
                MenuTheme.TextRole.LABEL, style.text()
            );
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class QuantityEditBox extends EditBox {
        private QuantityEditBox(int x, int y, int width, int height, Component narration) {
            super(font, x, y, width, height, narration);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // The value and cursor are rendered by the screen to keep them centered and shadow-free.
        }
    }
}
