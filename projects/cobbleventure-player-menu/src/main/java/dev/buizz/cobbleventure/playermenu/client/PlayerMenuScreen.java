package dev.buizz.cobbleventure.playermenu.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class PlayerMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 88;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MIN_RADIUS = 64;
    private static final int MAX_RADIUS = 112;
    private static final int CONNECTED_COLOR = 0xFF77DD77;
    private static final int PENDING_COLOR = 0xFFFFCC66;
    private static final int CONNECTOR_COLOR = 0x806E7D91;
    private static final int CENTER_BACKGROUND = 0xD0202935;

    private final List<EntryButton> entryButtons = new ArrayList<>();
    private Component statusMessage = Component.translatable(
        "screen.cobbleventure_player_menu.status.select"
    );

    public PlayerMenuScreen() {
        super(Component.translatable("screen.cobbleventure_player_menu.title"));
    }

    @Override
    protected void init() {
        super.init();
        entryButtons.clear();

        int centerX = width / 2;
        int centerY = height / 2;
        int horizontalRadius = centerX - BUTTON_WIDTH / 2 - 8;
        int verticalRadius = centerY - BUTTON_HEIGHT / 2 - 18;
        int radius = Math.max(
            MIN_RADIUS,
            Math.min(MAX_RADIUS, Math.min(horizontalRadius, verticalRadius))
        );
        PlayerMenuEntry[] entries = PlayerMenuEntry.values();

        for (int index = 0; index < entries.length; index++) {
            PlayerMenuEntry entry = entries[index];
            double angle = -Math.PI / 2.0D + index * Math.PI / 4.0D;
            int buttonX = centerX + (int) Math.round(Math.cos(angle) * radius) - BUTTON_WIDTH / 2;
            int buttonY = centerY + (int) Math.round(Math.sin(angle) * radius) - BUTTON_HEIGHT / 2;
            Component label = Component.translatable(
                "screen.cobbleventure_player_menu.entry.numbered",
                index + 1,
                entry.title()
            );
            Button button = Button.builder(label, pressed -> activate(entry))
                .bounds(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
            addRenderableWidget(button);
            entryButtons.add(new EntryButton(entry, button));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB010151D);

        int centerX = width / 2;
        int centerY = height / 2;
        for (EntryButton entryButton : entryButtons) {
            Button button = entryButton.button();
            drawLine(
                graphics,
                centerX,
                centerY,
                button.getX() + button.getWidth() / 2,
                button.getY() + button.getHeight() / 2,
                CONNECTOR_COLOR
            );
        }

        graphics.fill(centerX - 62, centerY - 25, centerX + 62, centerY + 25, CENTER_BACKGROUND);
        graphics.drawCenteredString(font, title, centerX, centerY - 17, 0xFFFFFFFF);
        graphics.drawCenteredString(font, statusForHover(), centerX, centerY - 3, 0xFFE6EDF3);
        graphics.drawCenteredString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.hint"),
            centerX,
            height - 18,
            0xFFB8C4D2
        );

        super.render(graphics, mouseX, mouseY, partialTick);

        for (EntryButton entryButton : entryButtons) {
            Button button = entryButton.button();
            int color = entryButton.entry().connected() ? CONNECTED_COLOR : PENDING_COLOR;
            graphics.fill(button.getX() + 3, button.getY() + 3, button.getX() + 6, button.getY() + 6, color);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        int firstNumberKey = GLFW.GLFW_KEY_1;
        int index = keyCode - firstNumberKey;
        if (index >= 0 && index < PlayerMenuEntry.values().length) {
            activate(PlayerMenuEntry.values()[index]);
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void activate(PlayerMenuEntry entry) {
        statusMessage = switch (entry.open()) {
            case OPENED -> statusMessage;
            case MISSING_POKEDEX -> Component.translatable(
                "screen.cobbleventure_player_menu.status.missing_pokedex"
            );
            case UNAVAILABLE -> Component.translatable(
                "screen.cobbleventure_player_menu.status.coming_soon",
                entry.title()
            );
        };
    }

    private Component statusForHover() {
        for (EntryButton entryButton : entryButtons) {
            if (entryButton.button().isHovered()) {
                return entryButton.entry().description();
            }
        }
        return statusMessage;
    }

    private static void drawLine(
        GuiGraphics graphics,
        int startX,
        int startY,
        int endX,
        int endY,
        int color
    ) {
        int deltaX = endX - startX;
        int deltaY = endY - startY;
        int steps = Math.max(Math.abs(deltaX), Math.abs(deltaY));
        if (steps == 0) {
            return;
        }
        for (int step = 0; step <= steps; step++) {
            int x = startX + deltaX * step / steps;
            int y = startY + deltaY * step / steps;
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private record EntryButton(PlayerMenuEntry entry, Button button) {}
}
