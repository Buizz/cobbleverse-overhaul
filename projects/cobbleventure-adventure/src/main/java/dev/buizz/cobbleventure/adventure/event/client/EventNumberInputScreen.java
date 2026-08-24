package dev.buizz.cobbleventure.adventure.event.client;

import dev.buizz.cobbleventure.adventure.event.EventDialogueNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Modal integer editor used by the CVES number_input await command. */
public final class EventNumberInputScreen extends Screen {
    private final EventDialogueNetwork.NumberInputOpenPayload payload;
    private EditBox input;
    private Button confirm;
    private boolean replied;

    EventNumberInputScreen(EventDialogueNetwork.NumberInputOpenPayload payload) {
        super(Component.translatable("screen.cobbleventure_adventure.number_input.title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        int left = width / 2 - 130;
        int top = height / 2 - 56;
        input = new EditBox(font, left + 15, top + 42, 230, 20,
            Component.translatable("screen.cobbleventure_adventure.number_input.field"));
        input.setFilter(value -> value.matches("-?\\d*"));
        input.setMaxLength(11);
        input.setResponder(ignored -> refresh());
        addRenderableWidget(input);
        confirm = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_adventure.number_input.confirm"),
            button -> submit()
        ).bounds(left + 15, top + 72, 110, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_adventure.number_input.cancel"),
            button -> complete(0, true)
        ).bounds(left + 135, top + 72, 110, 20).build());
        setInitialFocus(input);
        refresh();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = width / 2 - 130;
        int top = height / 2 - 56;
        graphics.fill(left, top, left + 260, top + 112, 0xE0121824);
        graphics.drawCenteredString(font, title, width / 2, top + 10, 0xFFF2C14E);
        graphics.drawCenteredString(font, Component.translatable(
            "screen.cobbleventure_adventure.number_input.range",
            payload.minimum(), payload.maximum()
        ), width / 2, top + 25, 0xFFD7DCE6);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { complete(0, true); }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && validValue() != null) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void refresh() {
        if (confirm != null) confirm.active = validValue() != null;
    }

    private Integer validValue() {
        if (input == null || input.getValue().isBlank() || "-".equals(input.getValue())) return null;
        try {
            int value = Integer.parseInt(input.getValue());
            return value >= payload.minimum() && value <= payload.maximum() ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void submit() {
        Integer value = validValue();
        if (value != null) complete(value, false);
    }

    private void complete(int value, boolean cancelled) {
        if (replied) return;
        replied = true;
        PacketDistributor.sendToServer(new EventDialogueNetwork.NumberInputCompletePayload(
            payload.token(), payload.npcId(), payload.scriptId(), payload.triggerInstance(),
            value, cancelled
        ));
        if (minecraft != null) minecraft.setScreen(null);
    }
}
