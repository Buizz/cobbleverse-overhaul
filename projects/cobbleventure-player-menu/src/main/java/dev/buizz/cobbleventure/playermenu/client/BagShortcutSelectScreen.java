package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Selects one of ten persistent bag shortcut slots. */
final class BagShortcutSelectScreen extends Screen {
    private static final int PANEL_WIDTH = 252;
    private static final int PANEL_HEIGHT = 132;
    private static final int PANEL_COLOR = 0xF05A5A5A;
    private static final int DARK_COLOR = 0xFF303030;
    private static final int LIGHT_COLOR = 0xFF888888;
    private static final int TEXT_COLOR = 0xFFF4F4F4;
    private static final int MUTED_COLOR = 0xFFA6A6A6;

    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack sourceStack;
    private final List<ShortcutButton> slotButtons = new ArrayList<>();
    private int panelX;
    private int panelY;

    BagShortcutSelectScreen(BagScreen parent, boolean extended, int sourceSlot, ItemStack sourceStack) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag.shortcut_select.title"));
        this.parent = parent;
        this.extended = extended;
        this.sourceSlot = sourceSlot;
        this.sourceStack = sourceStack;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        slotButtons.clear();
        List<ItemStack> assignments = BagNetwork.clientSnapshot().shortcuts();
        for (int index = 0; index < 10; index++) {
            int column = index % 5;
            int row = index / 5;
            ItemStack assigned = index < assignments.size() ? assignments.get(index) : ItemStack.EMPTY;
            ShortcutButton button = new ShortcutButton(
                index, assigned, panelX + 10 + column * 47, panelY + 42 + row * 34
            );
            addRenderableWidget(button);
            slotButtons.add(button);
        }
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.bag.cancel"), ignored -> onClose()
        ).bounds(panelX + PANEL_WIDTH - 62, panelY + 10, 52, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.renderItem(sourceStack, panelX + 10, panelY + 10);
        graphics.drawString(font, title, panelX + 32, panelY + 9, TEXT_COLOR, false);
        graphics.drawString(font,
            font.plainSubstrByWidth(sourceStack.getHoverName().getString(), PANEL_WIDTH - 108),
            panelX + 32, panelY + 21, MUTED_COLOR, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (ShortcutButton button : slotButtons) {
            if (button.isMouseOver(mouseX, mouseY) && !button.assigned.isEmpty()) {
                graphics.renderTooltip(font, button.assigned, mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            assign(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_0) {
            assign(9);
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

    private void assign(int shortcutIndex) {
        PlayerMenuClient.assignBagItemToShortcut(extended, sourceSlot, shortcutIndex);
        parent.shortcutAssigned(shortcutIndex);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 3, y + 3, x + width + 3, y + height + 3, 0xB0000000);
        graphics.fill(x, y, x + width, y + height, DARK_COLOR);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_COLOR);
        graphics.fill(x + 2, y + 2, x + width - 2, y + 3, LIGHT_COLOR);
    }

    private final class ShortcutButton extends AbstractButton {
        private final int shortcutIndex;
        private final ItemStack assigned;

        private ShortcutButton(int shortcutIndex, ItemStack assigned, int x, int y) {
            super(x, y, 42, 29, Component.literal(Integer.toString(shortcutIndex + 1)));
            this.shortcutIndex = shortcutIndex;
            this.assigned = assigned;
        }

        @Override
        public void onPress() { assign(shortcutIndex); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int fill = isHovered() ? 0xFFF0F0F0 : 0xE0464646;
            int text = isHovered() ? DARK_COLOR : TEXT_COLOR;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), DARK_COLOR);
            graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, fill);
            graphics.drawString(font, Integer.toString(shortcutIndex + 1), getX() + 4, getY() + 4, text, false);
            if (!assigned.isEmpty()) graphics.renderItem(assigned, getX() + 21, getY() + 7);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
