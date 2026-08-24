package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.client.RadarDisplaySettings.Option;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pokefinder-styled exploration marker settings. */
public final class RadarSettingsScreen extends Screen {
    private static final int WIDTH = 288;
    private static final int HEIGHT = 192;
    private final Screen parent;
    private int left;
    private int top;

    public RadarSettingsScreen(Screen parent) {
        super(Component.literal("탐색 정보"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
        Option[] categories = {
            Option.TRAINERS, Option.IMPORTANT_NPCS, Option.FACILITIES,
            Option.ENTRANCES, Option.OBJECTIVES
        };
        for (int index = 0; index < categories.length; index++) {
            addToggle(categories[index], left + 12, top + 36 + index * 24);
        }
        Option[] details = {
            Option.NAMES, Option.DISTANCES, Option.DEFEATED_TRAINERS
        };
        for (int index = 0; index < details.length; index++) {
            addToggle(details[index], left + 152, top + 36 + index * 24);
        }
        addRenderableWidget(Button.builder(
            Component.literal("완료"), button -> onClose()
        ).bounds(left + 190, top + 162, 86, 18).build());
    }

    private void addToggle(Option option, int x, int y) {
        Button button = Button.builder(Component.empty(), pressed -> {
            RadarDisplaySettings.toggle(option);
            pressed.setMessage(message(option));
        }).bounds(x, y, 124, 18).build();
        button.setMessage(message(option));
        addRenderableWidget(button);
    }

    private static Component message(Option option) {
        return Component.literal(option.label() + ": "
            + (RadarDisplaySettings.value(option) ? "켜짐" : "꺼짐"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xE617202B);
        graphics.fill(left, top, left + WIDTH, top + 2, 0xFF62E6FF);
        graphics.fill(left, top + HEIGHT - 2, left + WIDTH, top + HEIGHT, 0xFF315F73);
        graphics.drawCenteredString(font, title, left + WIDTH / 2, top + 12, 0xFFF2F7FF);
        graphics.drawString(font, "마커 종류", left + 12, top + 25, 0xFF9EDDEC, false);
        graphics.drawString(font, "표시 옵션", left + 152, top + 25, 0xFF9EDDEC, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
