package dev.buizz.cobbleventure.playermenu.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public final class PlayerMenuScreen extends Screen {
    private static final int COLUMN_COUNT = 4;
    private static final int ROW_COUNT = 2;
    private static final int CARD_GAP = 8;
    private static final int MAX_CARD_WIDTH = 132;
    private static final int MIN_CARD_WIDTH = 70;
    private static final int MAX_CARD_HEIGHT = 92;
    private static final int MIN_CARD_HEIGHT = 56;
    private static final int HEADER_HEIGHT = 44;

    private static final int BACKDROP_COLOR = 0x80141B26;
    private static final int PANEL_COLOR = 0xE0182636;
    private static final int PANEL_BORDER_COLOR = 0xFF465B70;
    private static final int CARD_COLOR = 0xE01B2A3B;
    private static final int CARD_HOVER_COLOR = 0xF022394B;
    private static final int CARD_SELECTED_COLOR = 0xF0244052;
    private static final int CARD_BORDER_COLOR = 0xFF52677C;
    private static final int SELECTED_BORDER_COLOR = 0xFF56E0DD;
    private static final int CONNECTED_COLOR = 0xFF63E6BE;
    private static final int PENDING_COLOR = 0xFF8D99A6;
    private static final int PRIMARY_TEXT_COLOR = 0xFFF4E8C8;
    private static final int SECONDARY_TEXT_COLOR = 0xFFC4D1DD;
    private static final int MUTED_TEXT_COLOR = 0xFF8FA1B3;

    private final List<MenuCard> cards = new ArrayList<>();
    private int selectedIndex;
    private int gridX;
    private int gridY;
    private int gridWidth;
    private int gridHeight;
    private Component statusMessage;

    public PlayerMenuScreen() {
        super(Component.translatable("screen.cobbleventure_player_menu.title"));
    }

    @Override
    protected void init() {
        super.init();
        cards.clear();

        int availableWidth = Math.max(1, width - 32);
        int targetGridWidth = Math.min(
            COLUMN_COUNT * MAX_CARD_WIDTH + (COLUMN_COUNT - 1) * CARD_GAP,
            availableWidth
        );
        int cardWidth = clamp(
            (targetGridWidth - (COLUMN_COUNT - 1) * CARD_GAP) / COLUMN_COUNT,
            MIN_CARD_WIDTH,
            MAX_CARD_WIDTH
        );
        int cardHeight = clamp(
            (height - HEADER_HEIGHT - 64 - CARD_GAP) / ROW_COUNT,
            MIN_CARD_HEIGHT,
            MAX_CARD_HEIGHT
        );

        gridWidth = COLUMN_COUNT * cardWidth + (COLUMN_COUNT - 1) * CARD_GAP;
        gridHeight = ROW_COUNT * cardHeight + (ROW_COUNT - 1) * CARD_GAP;
        int contentHeight = HEADER_HEIGHT + CARD_GAP + gridHeight;
        int contentTop = Math.max(10, (height - contentHeight) / 2 - 4);
        gridX = (width - gridWidth) / 2;
        gridY = contentTop + HEADER_HEIGHT + CARD_GAP;

        PlayerMenuEntry[] entries = PlayerMenuEntry.values();
        selectedIndex = clamp(selectedIndex, 0, entries.length - 1);
        for (int index = 0; index < entries.length; index++) {
            int column = index % COLUMN_COUNT;
            int row = index / COLUMN_COUNT;
            MenuCard card = new MenuCard(
                index,
                entries[index],
                gridX + column * (cardWidth + CARD_GAP),
                gridY + row * (cardHeight + CARD_GAP),
                cardWidth,
                cardHeight
            );
            addRenderableWidget(card);
            cards.add(card);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKDROP_COLOR);

        for (MenuCard card : cards) {
            if (card.isMouseOver(mouseX, mouseY)) {
                select(card.index());
                break;
            }
        }

        renderHeader(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderFooter(graphics);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The menu draws its own translucent backdrop. Screen's default implementation
        // would blur everything already drawn before super.render() renders the cards.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        int numberIndex = keyCode - GLFW.GLFW_KEY_1;
        if (numberIndex >= 0 && numberIndex < PlayerMenuEntry.values().length) {
            select(numberIndex);
            activateSelected();
            return true;
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> moveSelection(-1, 0);
            case GLFW.GLFW_KEY_RIGHT -> moveSelection(1, 0);
            case GLFW.GLFW_KEY_UP -> moveSelection(0, -1);
            case GLFW.GLFW_KEY_DOWN -> moveSelection(0, 1);
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

    private void renderHeader(GuiGraphics graphics) {
        int headerY = gridY - HEADER_HEIGHT - CARD_GAP;
        drawPanel(graphics, gridX, headerY, gridWidth, HEADER_HEIGHT, PANEL_COLOR, PANEL_BORDER_COLOR);

        int textX = gridX + 12;
        graphics.drawString(font, title, textX, headerY + 8, PRIMARY_TEXT_COLOR, false);

        String playerName = minecraft != null && minecraft.player != null
            ? minecraft.player.getGameProfile().getName()
            : Component.translatable("screen.cobbleventure_player_menu.header.adventurer").getString();
        Component playerLine = Component.translatable(
            "screen.cobbleventure_player_menu.header.player",
            playerName
        );
        graphics.drawString(font, playerLine, textX, headerY + 25, SECONDARY_TEXT_COLOR, false);

        long connectedCount = Arrays.stream(PlayerMenuEntry.values())
            .filter(PlayerMenuEntry::connected)
            .count();
        Component available = Component.translatable(
            "screen.cobbleventure_player_menu.header.available",
            connectedCount,
            PlayerMenuEntry.values().length
        );
        graphics.drawString(
            font,
            available,
            gridX + gridWidth - 12 - font.width(available),
            headerY + 8,
            CONNECTED_COLOR,
            false
        );

        Component detail = statusMessage != null
            ? statusMessage
            : PlayerMenuEntry.values()[selectedIndex].description();
        String clippedDetail = font.plainSubstrByWidth(detail.getString(), Math.max(48, gridWidth / 2));
        graphics.drawString(
            font,
            clippedDetail,
            gridX + gridWidth - 12 - font.width(clippedDetail),
            headerY + 25,
            statusMessage == null ? MUTED_TEXT_COLOR : PRIMARY_TEXT_COLOR,
            false
        );
    }

    private void renderFooter(GuiGraphics graphics) {
        int footerY = gridY + gridHeight + CARD_GAP;
        int footerHeight = 20;
        if (footerY + footerHeight > height - 4) {
            footerY = height - footerHeight - 4;
        }
        drawPanel(graphics, gridX, footerY, gridWidth, footerHeight, PANEL_COLOR, PANEL_BORDER_COLOR);
        graphics.drawCenteredString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.controls"),
            gridX + gridWidth / 2,
            footerY + 6,
            SECONDARY_TEXT_COLOR
        );
    }

    private void moveSelection(int columnDelta, int rowDelta) {
        int column = selectedIndex % COLUMN_COUNT;
        int row = selectedIndex / COLUMN_COUNT;
        column = Math.floorMod(column + columnDelta, COLUMN_COUNT);
        row = Math.floorMod(row + rowDelta, ROW_COUNT);
        select(row * COLUMN_COUNT + column);
    }

    private void select(int index) {
        if (index == selectedIndex) {
            return;
        }
        selectedIndex = clamp(index, 0, PlayerMenuEntry.values().length - 1);
        statusMessage = null;
        if (selectedIndex < cards.size()) {
            setFocused(cards.get(selectedIndex));
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void drawPanel(
        GuiGraphics graphics,
        int x,
        int y,
        int panelWidth,
        int panelHeight,
        int fillColor,
        int borderColor
    ) {
        graphics.fill(x, y, x + panelWidth, y + panelHeight, fillColor);
        graphics.fill(x, y, x + panelWidth, y + 1, borderColor);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, borderColor);
        graphics.fill(x, y, x + 1, y + panelHeight, borderColor);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, borderColor);
    }

    private final class MenuCard extends AbstractButton {
        private final int index;
        private final PlayerMenuEntry entry;
        private final ItemStack icon;

        private MenuCard(
            int index,
            PlayerMenuEntry entry,
            int x,
            int y,
            int cardWidth,
            int cardHeight
        ) {
            super(x, y, cardWidth, cardHeight, entry.title());
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
            int fillColor = selected ? CARD_SELECTED_COLOR : (isHovered() ? CARD_HOVER_COLOR : CARD_COLOR);
            int borderColor = selected ? SELECTED_BORDER_COLOR : CARD_BORDER_COLOR;
            drawPanel(graphics, getX(), getY(), getWidth(), getHeight(), fillColor, borderColor);
            if (selected) {
                drawPanel(
                    graphics,
                    getX() + 2,
                    getY() + 2,
                    getWidth() - 4,
                    getHeight() - 4,
                    0x00000000,
                    0x9056E0DD
                );
            }

            graphics.drawString(
                font,
                Integer.toString(index + 1),
                getX() + 6,
                getY() + 5,
                MUTED_TEXT_COLOR,
                false
            );
            int dotColor = entry.connected() ? CONNECTED_COLOR : PENDING_COLOR;
            graphics.fill(
                getX() + getWidth() - 10,
                getY() + 6,
                getX() + getWidth() - 6,
                getY() + 10,
                dotColor
            );

            renderIcon(graphics, icon, getX() + getWidth() / 2, getY() + 14, getHeight());

            String clippedTitle = font.plainSubstrByWidth(entry.title().getString(), getWidth() - 12);
            int titleY = selected && getHeight() >= 74 ? getY() + getHeight() - 31 : getY() + getHeight() - 17;
            graphics.drawCenteredString(
                font,
                clippedTitle,
                getX() + getWidth() / 2,
                titleY,
                PRIMARY_TEXT_COLOR
            );

            if (selected && getHeight() >= 74) {
                String description = font.plainSubstrByWidth(entry.description().getString(), getWidth() - 12);
                graphics.drawCenteredString(
                    font,
                    description,
                    getX() + getWidth() / 2,
                    getY() + getHeight() - 16,
                    SECONDARY_TEXT_COLOR
                );
            }
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
        int cardHeight
    ) {
        float scale = cardHeight >= 74 ? 2.0F : 1.25F;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - 8.0F * scale, topY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(icon, 0, 0);
        graphics.pose().popPose();
    }
}
