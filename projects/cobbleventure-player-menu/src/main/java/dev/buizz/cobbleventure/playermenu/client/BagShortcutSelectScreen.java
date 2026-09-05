package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.BagNetwork;
import dev.buizz.cobbleventure.playermenu.BagStorage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Selects one persistent bag shortcut for each Minecraft hotbar slot. */
final class BagShortcutSelectScreen extends Screen {
    private static final int PANEL_WIDTH = 252;
    private static final int PANEL_HEIGHT = 132;
    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack sourceStack;
    private final MenuTheme theme;
    private final List<ShortcutButton> slotButtons = new ArrayList<>();
    private int panelX;
    private int panelY;

    BagShortcutSelectScreen(BagScreen parent, boolean extended, int sourceSlot, ItemStack sourceStack) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag.shortcut_select.title"));
        this.parent = parent;
        this.extended = extended;
        this.sourceSlot = sourceSlot;
        this.sourceStack = sourceStack;
        this.theme = MenuTheme.load(Minecraft.getInstance());
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        slotButtons.clear();
        List<ItemStack> assignments = BagNetwork.clientSnapshot().shortcuts();
        for (int index = 0; index < BagStorage.SHORTCUT_COUNT; index++) {
            int column = index % 5;
            int row = index / 5;
            ItemStack assigned = index < assignments.size() ? assignments.get(index) : ItemStack.EMPTY;
            ShortcutButton button = new ShortcutButton(
                index, assigned, panelX + 10 + column * 47, panelY + 42 + row * 34
            );
            addRenderableWidget(button);
            slotButtons.add(button);
        }
        addRenderableWidget(new ThemeButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.cancel"),
            panelX + PANEL_WIDTH - 62, panelY + 10, 52, 20, this::onClose
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ThemedOverlayPanel.draw(graphics, theme, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        graphics.renderItem(sourceStack, panelX + 10, panelY + 10);
        theme.drawText(graphics, font, title, panelX + 32, panelY + 9, MenuTheme.TextRole.HEADING);
        theme.drawText(graphics, font,
            Component.literal(font.plainSubstrByWidth(sourceStack.getHoverName().getString(), PANEL_WIDTH - 108)),
            panelX + 32, panelY + 22, MenuTheme.TextRole.CAPTION);
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
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), false
            );
            ThemedOverlayPanel.fillRoundedRect(graphics, getX(), getY(),
                getX() + getWidth(), getY() + getHeight(), theme.rowRadius, style.border());
            ThemedOverlayPanel.fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), style.background());
            theme.drawText(graphics, font, Component.literal(Integer.toString(shortcutIndex + 1)),
                getX() + 4, getY() + 4, MenuTheme.TextRole.LABEL, style.text());
            if (!assigned.isEmpty()) graphics.renderItem(assigned, getX() + 21, getY() + 7);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class ThemeButton extends AbstractButton {
        private final Runnable action;

        private ThemeButton(Component message, int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
        }

        @Override
        public void onPress() { action.run(); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.GHOST, active, isHovered(), false
            );
            ThemedOverlayPanel.fillRoundedRect(graphics, getX(), getY(),
                getX() + getWidth(), getY() + getHeight(), theme.rowRadius, style.border());
            ThemedOverlayPanel.fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), style.background());
            theme.drawCenteredText(graphics, font, getMessage(), getX() + getWidth() / 2,
                getY() + 6, MenuTheme.TextRole.LABEL, style.text());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
