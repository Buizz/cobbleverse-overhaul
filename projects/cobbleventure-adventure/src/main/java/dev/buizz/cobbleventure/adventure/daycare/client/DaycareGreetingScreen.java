package dev.buizz.cobbleventure.adventure.daycare.client;

import dev.buizz.cobbleventure.adventure.daycare.DaycareNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Short NPC greeting shown before entering the daycare storage interface. */
final class DaycareGreetingScreen extends Screen {
    private final DaycareNetwork.ViewPayload payload;
    private DaycareMenuTheme theme;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    DaycareGreetingScreen(DaycareNetwork.ViewPayload payload) {
        super(Component.translatable("screen.cobbleventure_adventure.daycare.greeting_title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        theme = DaycareMenuTheme.load(minecraft);
        panelWidth = Math.min(520, width - 24);
        panelHeight = Math.min(128, Math.max(104, height / 3));
        panelX = (width - panelWidth) / 2;
        panelY = height - panelHeight - 18;
        addRenderableWidget(new ContinueButton(
            panelX + panelWidth - 92, panelY + panelHeight - 30, 76, 20
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x44071924);
        DaycareThemedPanel.draw(
            graphics, theme, panelX, panelY, panelWidth, panelHeight, theme.accent
        );
        Component npcName = payload.npcName().getString().isBlank()
            ? Component.translatable("screen.cobbleventure_adventure.daycare.attendant")
            : payload.npcName();
        graphics.drawString(font, npcName, panelX + 16, panelY + 13,
            theme.selectedTextColor, false);
        Component greeting = payload.eggCount() > 0
            ? Component.translatable(
                "screen.cobbleventure_adventure.daycare.greeting_eggs", payload.eggCount()
            )
            : payload.storedPokemon().isEmpty()
                ? Component.translatable("screen.cobbleventure_adventure.daycare.greeting_empty")
                : Component.translatable(
                    "screen.cobbleventure_adventure.daycare.greeting_return",
                    payload.storedPokemon().size()
                );
        int textWidth = panelWidth - 32;
        int y = panelY + 36;
        for (var line : font.split(greeting, textWidth)) {
            graphics.drawString(font, line, panelX + 16, y, theme.textColor, false);
            y += 12;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {}

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
            || keyCode == GLFW.GLFW_KEY_SPACE) {
            openStorage();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openStorage() {
        if (minecraft != null) minecraft.setScreen(new DaycareScreen(payload));
    }

    private final class ContinueButton extends AbstractButton {
        private ContinueButton(int x, int y, int width, int height) {
            super(x, y, width, height,
                Component.translatable("screen.cobbleventure_adventure.daycare.open_storage"));
        }

        @Override public void onPress() {
            openStorage();
        }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            int fill = isHovered() ? theme.hoverBackground : theme.selectedBackground;
            DaycareThemedPanel.roundedFill(graphics, getX(), getY(),
                getX() + getWidth(), getY() + getHeight(), theme.rowRadius, theme.accent);
            DaycareThemedPanel.roundedFill(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), fill);
            graphics.drawCenteredString(font, getMessage(),
                getX() + getWidth() / 2, getY() + 6, theme.selectedTextColor);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
