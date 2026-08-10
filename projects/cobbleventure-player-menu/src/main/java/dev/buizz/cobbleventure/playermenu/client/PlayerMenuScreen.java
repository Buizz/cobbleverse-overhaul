package dev.buizz.cobbleventure.playermenu.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

public final class PlayerMenuScreen extends Screen {
    private static final int MENU_MARGIN = 12;
    private static final int MENU_HEADER_HEIGHT = 22;
    private static final int MENU_PADDING = 5;
    private static final int ROW_GAP = 2;

    // Cobblemon's summary and party screens use neutral grey panels with a
    // one-pixel light edge and a dark outer border. Keep these colours local so
    // the menu remains usable even when Cobblemon itself is not installed.
    private static final int SHADOW_COLOR = 0xB0000000;
    private static final int PANEL_COLOR = 0xF05A5A5A;
    private static final int PANEL_DARK_COLOR = 0xFF303030;
    private static final int PANEL_LIGHT_COLOR = 0xFF888888;
    private static final int ROW_COLOR = 0xF0464646;
    private static final int ROW_HOVER_COLOR = 0xF0525252;
    private static final int ROW_SELECTED_COLOR = 0xFFF0F0F0;
    private static final int ROW_SELECTED_INNER_COLOR = 0xFFD2D2D2;
    private static final int PRIMARY_TEXT_COLOR = 0xFFF4F4F4;
    private static final int SECONDARY_TEXT_COLOR = 0xFFD0D0D0;
    private static final int MUTED_TEXT_COLOR = 0xFFA6A6A6;
    private static final int SELECTED_TEXT_COLOR = 0xFF303030;
    private static final int CONNECTED_COLOR = 0xFF91C7A2;
    private static final int PENDING_COLOR = 0xFF8D8D8D;

    private final List<MenuRow> rows = new ArrayList<>();
    private int selectedIndex;
    private int menuX;
    private int menuY;
    private int menuWidth;
    private int menuHeight;
    private int rowHeight;
    private int infoWidth;
    private Component statusMessage;

    public PlayerMenuScreen() {
        super(Component.translatable("screen.cobbleventure_player_menu.title"));
    }

    @Override
    protected void init() {
        super.init();
        rows.clear();

        PlayerMenuEntry[] entries = PlayerMenuEntry.values();
        selectedIndex = clamp(selectedIndex, 0, entries.length - 1);

        menuWidth = clamp(width / 3, 138, 184);
        rowHeight = clamp(
            (height - MENU_MARGIN * 2 - MENU_HEADER_HEIGHT - MENU_PADDING * 2
                - ROW_GAP * (entries.length - 1)) / entries.length,
            19,
            27
        );
        menuHeight = MENU_HEADER_HEIGHT + MENU_PADDING * 2
            + rowHeight * entries.length + ROW_GAP * (entries.length - 1);
        menuX = width - menuWidth - MENU_MARGIN;
        menuY = (height - menuHeight) / 2;
        infoWidth = clamp(menuX - MENU_MARGIN * 2, 140, 230);

        int rowX = menuX + MENU_PADDING;
        int rowY = menuY + MENU_HEADER_HEIGHT + MENU_PADDING;
        int rowWidth = menuWidth - MENU_PADDING * 2;
        for (int index = 0; index < entries.length; index++) {
            MenuRow row = new MenuRow(
                index,
                entries[index],
                rowX,
                rowY + index * (rowHeight + ROW_GAP),
                rowWidth,
                rowHeight
            );
            addRenderableWidget(row);
            rows.add(row);
        }
        setFocused(rows.get(selectedIndex));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (MenuRow row : rows) {
            if (row.isMouseOver(mouseX, mouseY)) {
                select(row.index());
                break;
            }
        }

        renderTrainerPanel(graphics);
        renderInfoPanel(graphics);
        renderMenuPanel(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderControls(graphics);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty: the compact panels provide their own contrast and
        // the field must remain visible without Minecraft's fullscreen blur.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        PlayerMenuEntry shortcutEntry = PlayerMenuKeyMappings.matchingEntry(keyCode, scanCode);
        if (shortcutEntry != null) {
            select(shortcutEntry.ordinal());
            activate(shortcutEntry);
            return true;
        }

        int numberIndex = keyCode - GLFW.GLFW_KEY_1;
        if (numberIndex >= 0 && numberIndex < PlayerMenuEntry.values().length) {
            select(numberIndex);
            activateSelected();
            return true;
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT -> moveSelection(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT -> moveSelection(1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> activateSelected();
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderTrainerPanel(GuiGraphics graphics) {
        int x = MENU_MARGIN;
        int y = MENU_MARGIN;
        int panelHeight = 42;
        drawCobblemonPanel(graphics, x, y, infoWidth, panelHeight);

        String playerName = minecraft != null && minecraft.player != null
            ? minecraft.player.getGameProfile().getName()
            : Component.translatable("screen.cobbleventure_player_menu.header.adventurer").getString();
        Component playerLine = Component.translatable(
            "screen.cobbleventure_player_menu.header.player",
            playerName
        );
        graphics.drawString(font, playerLine, x + 8, y + 7, PRIMARY_TEXT_COLOR, false);

        Component summary;
        int partySize = partySize();
        if (partySize >= 0) {
            summary = Component.translatable("screen.cobbleventure_player_menu.header.party", partySize, 6);
        } else {
            long connectedCount = Arrays.stream(PlayerMenuEntry.values())
                .filter(PlayerMenuEntry::connected)
                .count();
            summary = Component.translatable(
                "screen.cobbleventure_player_menu.header.available",
                connectedCount,
                PlayerMenuEntry.values().length
            );
        }
        graphics.drawString(font, summary, x + 8, y + 24, CONNECTED_COLOR, false);
    }

    private void renderInfoPanel(GuiGraphics graphics) {
        int panelHeight = 96;
        int x = MENU_MARGIN;
        int y = (height - panelHeight) / 2;
        drawCobblemonPanel(graphics, x, y, infoWidth, panelHeight);

        PlayerMenuEntry entry = PlayerMenuEntry.values()[selectedIndex];
        renderIcon(graphics, entry.icon(), x + 17, y + 11, 1.5F);
        graphics.drawString(font, entry.title(), x + 39, y + 10, PRIMARY_TEXT_COLOR, false);

        Component availability = Component.translatable(
            entry.connected()
                ? "screen.cobbleventure_player_menu.status.available"
                : "screen.cobbleventure_player_menu.status.pending"
        );
        graphics.drawString(
            font,
            availability,
            x + 39,
            y + 24,
            entry.connected() ? CONNECTED_COLOR : MUTED_TEXT_COLOR,
            false
        );

        Component shortcut = Component.translatable(
            "screen.cobbleventure_player_menu.shortcut",
            PlayerMenuKeyMappings.keyName(entry)
        );
        graphics.drawString(font, shortcut, x + 39, y + 36, CONNECTED_COLOR, false);

        Component detail = statusMessage != null ? statusMessage : entry.description();
        List<FormattedCharSequence> lines = font.split(detail, infoWidth - 16);
        int textY = y + 52;
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            graphics.drawString(
                font,
                lines.get(index),
                x + 8,
                textY + index * 11,
                statusMessage == null ? SECONDARY_TEXT_COLOR : PRIMARY_TEXT_COLOR,
                false
            );
        }
    }

    private void renderMenuPanel(GuiGraphics graphics) {
        drawCobblemonPanel(graphics, menuX, menuY, menuWidth, menuHeight);
        graphics.drawString(font, title, menuX + 8, menuY + 7, PRIMARY_TEXT_COLOR, false);
        graphics.fill(
            menuX + 5,
            menuY + MENU_HEADER_HEIGHT - 2,
            menuX + menuWidth - 5,
            menuY + MENU_HEADER_HEIGHT - 1,
            PANEL_DARK_COLOR
        );
    }

    private void renderControls(GuiGraphics graphics) {
        int panelHeight = 35;
        int x = MENU_MARGIN;
        int y = height - MENU_MARGIN - panelHeight;
        drawCobblemonPanel(graphics, x, y, infoWidth, panelHeight);
        graphics.drawString(
            font,
            Component.translatable(
                "screen.cobbleventure_player_menu.controls.primary",
                minecraft == null ? Component.literal("E") : minecraft.options.keyInventory.getTranslatedKeyMessage()
            ),
            x + 8,
            y + 7,
            SECONDARY_TEXT_COLOR,
            false
        );
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.controls.secondary"),
            x + 8,
            y + 20,
            MUTED_TEXT_COLOR,
            false
        );
    }

    private void moveSelection(int delta) {
        select(Math.floorMod(selectedIndex + delta, PlayerMenuEntry.values().length));
    }

    private void select(int index) {
        int nextIndex = clamp(index, 0, PlayerMenuEntry.values().length - 1);
        if (nextIndex == selectedIndex) {
            return;
        }
        selectedIndex = nextIndex;
        statusMessage = null;
        if (selectedIndex < rows.size()) {
            setFocused(rows.get(selectedIndex));
        }
    }

    private void activateSelected() {
        activate(PlayerMenuEntry.values()[selectedIndex]);
    }

    private void activate(PlayerMenuEntry entry) {
        statusMessage = switch (entry.open()) {
            case OPENED -> statusMessage;
            case NO_POKEMON -> Component.translatable(
                "screen.cobbleventure_player_menu.status.no_pokemon"
            );
            case MISSING_POKEDEX -> Component.translatable(
                "screen.cobbleventure_player_menu.status.missing_pokedex"
            );
            case ACTION_FAILED -> Component.translatable(
                "screen.cobbleventure_player_menu.status.action_failed"
            );
            case UNAVAILABLE -> Component.translatable(
                "screen.cobbleventure_player_menu.status.coming_soon",
                entry.title()
            );
        };
    }

    private static int partySize() {
        if (!ModList.get().isLoaded("cobblemon")) {
            return -1;
        }
        return CobblemonMenuIntegration.partySize();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void drawCobblemonPanel(
        GuiGraphics graphics,
        int x,
        int y,
        int panelWidth,
        int panelHeight
    ) {
        graphics.fill(x + 2, y + 2, x + panelWidth + 2, y + panelHeight + 2, SHADOW_COLOR);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL_DARK_COLOR);
        graphics.fill(x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1, PANEL_COLOR);
        graphics.fill(x + 2, y + 2, x + panelWidth - 2, y + 3, PANEL_LIGHT_COLOR);
        graphics.fill(x + 2, y + 2, x + 3, y + panelHeight - 2, PANEL_LIGHT_COLOR);
    }

    private final class MenuRow extends AbstractButton {
        private final int index;
        private final PlayerMenuEntry entry;
        private final ItemStack icon;

        private MenuRow(
            int index,
            PlayerMenuEntry entry,
            int x,
            int y,
            int rowWidth,
            int rowHeight
        ) {
            super(x, y, rowWidth, rowHeight, entry.title());
            this.index = index;
            this.entry = entry;
            this.icon = entry.icon();
        }

        int index() {
            return index;
        }

        @Override
        public void onPress() {
            select(index);
            activate(entry);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = index == selectedIndex;
            int fillColor = selected
                ? ROW_SELECTED_COLOR
                : (isHovered() ? ROW_HOVER_COLOR : ROW_COLOR);
            int textColor = selected ? SELECTED_TEXT_COLOR : PRIMARY_TEXT_COLOR;

            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_DARK_COLOR);
            graphics.fill(
                getX() + 1,
                getY() + 1,
                getX() + getWidth() - 1,
                getY() + getHeight() - 1,
                fillColor
            );
            if (selected) {
                graphics.fill(
                    getX() + 2,
                    getY() + 2,
                    getX() + getWidth() - 2,
                    getY() + 3,
                    ROW_SELECTED_INNER_COLOR
                );
                graphics.fill(
                    getX() + 2,
                    getY() + 2,
                    getX() + 4,
                    getY() + getHeight() - 2,
                    CONNECTED_COLOR
                );
            }

            int iconY = getY() + (getHeight() - 16) / 2;
            graphics.renderItem(icon, getX() + 17, iconY);
            graphics.drawString(
                font,
                Integer.toString(index + 1),
                getX() + 7,
                getY() + (getHeight() - 8) / 2,
                selected ? 0xFF6A6A6A : MUTED_TEXT_COLOR,
                false
            );

            String shortcut = PlayerMenuKeyMappings.keyName(entry).getString();
            String clippedShortcut = font.plainSubstrByWidth(shortcut, 30);
            int shortcutWidth = font.width(clippedShortcut);
            int shortcutX = getX() + getWidth() - 13 - shortcutWidth;
            graphics.fill(shortcutX - 2, getY() + 4, shortcutX + shortcutWidth + 2,
                getY() + getHeight() - 4, selected ? ROW_SELECTED_INNER_COLOR : PANEL_DARK_COLOR);
            graphics.drawString(font, clippedShortcut, shortcutX,
                getY() + (getHeight() - 8) / 2,
                selected ? SELECTED_TEXT_COLOR : CONNECTED_COLOR, false);

            int titleWidth = Math.max(12, shortcutX - (getX() + 38) - 4);
            String clippedTitle = font.plainSubstrByWidth(entry.title().getString(), titleWidth);
            graphics.drawString(
                font,
                clippedTitle,
                getX() + 38,
                getY() + (getHeight() - 8) / 2,
                textColor,
                false
            );

            int dotColor = entry.connected() ? CONNECTED_COLOR : PENDING_COLOR;
            int dotY = getY() + getHeight() / 2 - 2;
            graphics.fill(
                getX() + getWidth() - 9,
                dotY,
                getX() + getWidth() - 5,
                dotY + 4,
                dotColor
            );
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void renderIcon(
        GuiGraphics graphics,
        ItemStack icon,
        int centerX,
        int topY,
        float scale
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - 8.0F * scale, topY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(icon, 0, 0);
        graphics.pose().popPose();
    }
}
