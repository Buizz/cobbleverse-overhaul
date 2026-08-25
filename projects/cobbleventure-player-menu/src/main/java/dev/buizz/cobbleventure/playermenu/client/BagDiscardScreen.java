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
    private static final int TEXT_COLOR = 0xFF243947;
    private static final int MUTED_COLOR = 0xFF607B8C;
    private static final int PANEL_BORDER = 0xFF4B91C1;
    private static final int PANEL_INNER = 0xFFD8F2F8;
    private static final int PANEL_FILL = 0xFFF7FCFD;
    private static final int ACTION_BLUE = 0xFF3478A8;
    private static final int ACTION_RED = 0xFFC95555;

    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack stack;
    private final int maximum;
    private final Mode mode;
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
        quantityBox.setTextColor(TEXT_COLOR);
        quantityBox.setTextColorUneditable(0xFF93A4AD);
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
        graphics.fill(0, 0, width, height, 0xA81A2730);
        drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.fill(panelX + 8, panelY + 7, panelX + PANEL_WIDTH - 8, panelY + 34, 0xFFCDEAF2);
        graphics.fill(panelX + 8, panelY + 33, panelX + PANEL_WIDTH - 8, panelY + 34, 0xFF8CC5D8);
        graphics.fill(panelX + 8, panelY + 7, panelX + 29, panelY + 30, 0xFFF6FCFE);
        graphics.fill(panelX + 9, panelY + 8, panelX + 28, panelY + 29, 0xFFDDEFF4);
        graphics.renderItem(stack, panelX + 10, panelY + 10);
        graphics.drawString(font, title, panelX + 32, panelY + 9, TEXT_COLOR, false);
        graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), PANEL_WIDTH - 44),
            panelX + 32, panelY + 21, MUTED_COLOR, false);
        Component owned = Component.translatable(
            "screen.cobbleventure_player_menu.bag.discard_owned", maximum
        );
        graphics.drawString(font, owned,
            panelX + (PANEL_WIDTH - font.width(owned)) / 2, panelY + 36, MUTED_COLOR, false);
        drawInputFrame(graphics, panelX + 89, panelY + 46, 82, 24,
            quantityBox.isFocused(), validQuantity() > 0);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_player_menu.bag."
                + (mode == Mode.DROP ? "drop_warning" : "delete_warning")),
            panelX + 10, panelY + 98, mode == Mode.DROP ? ACTION_BLUE : ACTION_RED, false);
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

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 4, y + 5, x + width + 4, y + height + 5, 0x76000000);
        graphics.fill(x + 2, y, x + width - 2, y + height, PANEL_BORDER);
        graphics.fill(x, y + 2, x + width, y + height - 2, PANEL_BORDER);
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, PANEL_INNER);
        graphics.fill(x + 5, y + 5, x + width - 5, y + height - 5, PANEL_FILL);
        graphics.fill(x + 7, y + height - 7, x + width - 7, y + height - 5, 0xFFB4DCE8);
    }

    private static void drawInputFrame(
        GuiGraphics graphics, int x, int y, int width, int height, boolean focused, boolean valid
    ) {
        int border = !valid ? ACTION_RED : focused ? PANEL_BORDER : 0xFF9BCAD8;
        graphics.fill(x + 2, y, x + width - 2, y + height, border);
        graphics.fill(x, y + 2, x + width, y + height - 2, border);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFFFFFFFF);
        graphics.fill(x + 5, y + height - 4, x + width - 5, y + height - 3, 0xFFD7EAF0);
    }

    private void drawQuantity(GuiGraphics graphics) {
        String value = quantityBox.getValue();
        int textWidth = font.width(value);
        int textX = panelX + (PANEL_WIDTH - textWidth) / 2;
        int textY = panelY + 54;
        graphics.drawString(font, value, textX, textY, TEXT_COLOR, false);
        if (quantityBox.isFocused() && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cursorX = textX + textWidth + 1;
            graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight + 1, TEXT_COLOR);
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
            int base = switch (tone) {
                case PRIMARY -> ACTION_BLUE;
                case DANGER -> ACTION_RED;
                case NEUTRAL -> 0xFFF4FAFC;
            };
            int border = active
                ? (isHoveredOrFocused() ? 0xFFFFFFFF : tone == ButtonTone.NEUTRAL ? 0xFF91C2D2 : base)
                : 0xFFB7C3C8;
            int fill = active
                ? (isHoveredOrFocused() ? lighten(base) : base)
                : 0xFFE1E7E9;
            graphics.fill(getX() + 2, getY(), getX() + getWidth() - 2, getY() + getHeight(), border);
            graphics.fill(getX(), getY() + 2, getX() + getWidth(), getY() + getHeight() - 2, border);
            graphics.fill(getX() + 2, getY() + 2,
                getX() + getWidth() - 2, getY() + getHeight() - 2, fill);
            graphics.fill(getX() + 5, getY() + 3,
                getX() + getWidth() - 5, getY() + 4,
                tone == ButtonTone.NEUTRAL ? 0xFFFFFFFF : 0x55FFFFFF);
            int textColor = !active ? 0xFF89969C
                : tone == ButtonTone.NEUTRAL ? TEXT_COLOR : 0xFFFFFFFF;
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8);
            graphics.drawString(font, label,
                getX() + (getWidth() - font.width(label)) / 2,
                getY() + (getHeight() - font.lineHeight) / 2, textColor, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        private int lighten(int color) {
            if (tone == ButtonTone.NEUTRAL) return 0xFFE2F3F8;
            int red = Math.min(255, ((color >> 16) & 0xFF) + 24);
            int green = Math.min(255, ((color >> 8) & 0xFF) + 24);
            int blue = Math.min(255, (color & 0xFF) + 24);
            return 0xFF000000 | red << 16 | green << 8 | blue;
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
