package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Quantity picker shown before permanently discarding a bag item. */
final class BagDiscardScreen extends Screen {
    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 144;
    private static final int TEXT_COLOR = 0xFFF4F4F4;
    private static final int MUTED_COLOR = 0xFFA6A6A6;

    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack stack;
    private final int maximum;
    private EditBox quantityBox;
    private Button confirmButton;
    private int panelX;
    private int panelY;

    BagDiscardScreen(BagScreen parent, boolean extended, int sourceSlot, ItemStack stack, int maximum) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag.discard_select.title"));
        this.parent = parent;
        this.extended = extended;
        this.sourceSlot = sourceSlot;
        this.stack = stack;
        this.maximum = Math.max(1, maximum);
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        quantityBox = new EditBox(font, panelX + 91, panelY + 48, 78, 20,
            Component.translatable("screen.cobbleventure_player_menu.bag.discard_quantity"));
        quantityBox.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        quantityBox.setMaxLength(9);
        quantityBox.setValue("1");
        quantityBox.setResponder(ignored -> updateConfirmButton());
        addRenderableWidget(quantityBox);

        addRenderableWidget(Button.builder(Component.literal("−"), ignored -> adjust(-1))
            .bounds(panelX + 65, panelY + 48, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), ignored -> adjust(1))
            .bounds(panelX + 173, panelY + 48, 22, 20).build());

        int presetY = panelY + 75;
        addPreset(Component.literal("1"), 1, panelX + 10, presetY);
        addPreset(Component.literal("10"), Math.min(10, maximum), panelX + 60, presetY);
        addPreset(Component.translatable("screen.cobbleventure_player_menu.bag.discard_half"),
            Math.max(1, maximum / 2), panelX + 110, presetY);
        addPreset(Component.translatable("screen.cobbleventure_player_menu.bag.discard_all"),
            maximum, panelX + 160, presetY);

        confirmButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.discard_confirm"), ignored -> confirm()
        ).bounds(panelX + 82, panelY + 110, 82, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.cancel"), ignored -> onClose()
        ).bounds(panelX + 170, panelY + 110, 80, 20).build());
        setInitialFocus(quantityBox);
        updateConfirmButton();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.renderItem(stack, panelX + 10, panelY + 10);
        graphics.drawString(font, title, panelX + 32, panelY + 9, TEXT_COLOR, false);
        graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), PANEL_WIDTH - 44),
            panelX + 32, panelY + 21, MUTED_COLOR, false);
        graphics.drawCenteredString(font,
            Component.translatable("screen.cobbleventure_player_menu.bag.discard_owned", maximum),
            panelX + PANEL_WIDTH / 2, panelY + 36, MUTED_COLOR);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_player_menu.bag.discard_warning"),
            panelX + 10, panelY + 98, 0xFFFFB0A8, false);
        super.render(graphics, mouseX, mouseY, partialTick);
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
        addRenderableWidget(Button.builder(label, ignored -> setQuantity(value)).bounds(x, y, 46, 20).build());
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
        PlayerMenuClient.discardBagItem(extended, sourceSlot, quantity);
        parent.discardRequested(quantity);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 3, y + 3, x + width + 3, y + height + 3, 0xB0000000);
        graphics.fill(x, y, x + width, y + height, 0xFF303030);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xF05A5A5A);
        graphics.fill(x + 2, y + 2, x + width - 2, y + 3, 0xFF888888);
    }
}
