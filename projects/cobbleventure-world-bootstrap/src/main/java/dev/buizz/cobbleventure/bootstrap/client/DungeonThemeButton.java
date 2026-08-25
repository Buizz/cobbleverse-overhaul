package dev.buizz.cobbleventure.bootstrap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** High-contrast button shared by the dungeon guide and matchmaking screens. */
final class DungeonThemeButton extends AbstractButton {
    enum Tone { PRIMARY, SECONDARY }

    private final Tone tone;
    private final Runnable action;

    DungeonThemeButton(
        Component message,
        int x,
        int y,
        int width,
        int height,
        Tone tone,
        Runnable action
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
    protected void renderWidget(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        boolean highlighted = active && isHoveredOrFocused();
        int border = !active ? 0xFF45505F
            : highlighted ? 0xFFFFFFFF
            : tone == Tone.PRIMARY ? 0xFFFF7B89 : 0xFF8EA4BC;
        int fill = !active ? 0xFF252C36
            : tone == Tone.PRIMARY
                ? highlighted ? 0xFFB23C4C : 0xFF842C39
                : highlighted ? 0xFF3C526A : 0xFF26384C;
        graphics.fill(
            getX() + 2, getY(), getX() + getWidth() - 2,
            getY() + getHeight(), border
        );
        graphics.fill(
            getX(), getY() + 2, getX() + getWidth(),
            getY() + getHeight() - 2, border
        );
        graphics.fill(
            getX() + 2, getY() + 2, getX() + getWidth() - 2,
            getY() + getHeight() - 2, fill
        );
        graphics.fill(
            getX() + 5, getY() + 3, getX() + getWidth() - 5,
            getY() + 4, active ? 0x55FFFFFF : 0x224F5966
        );
        int textColor = active ? 0xFFFFFFFF : 0xFF8E99A8;
        String label = Minecraft.getInstance().font.plainSubstrByWidth(
            getMessage().getString(), getWidth() - 10
        );
        graphics.drawCenteredString(
            Minecraft.getInstance().font, label,
            getX() + getWidth() / 2,
            getY() + (getHeight() - Minecraft.getInstance().font.lineHeight) / 2,
            textColor
        );
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
