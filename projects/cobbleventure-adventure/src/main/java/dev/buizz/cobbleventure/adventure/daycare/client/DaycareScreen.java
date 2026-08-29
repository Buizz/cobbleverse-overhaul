package dev.buizz.cobbleventure.adventure.daycare.client;

import dev.buizz.cobbleventure.adventure.daycare.DaycareNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Compact party selection and collection screen opened by the daycare NPC. */
final class DaycareScreen extends Screen {
    private final DaycareNetwork.ViewPayload payload;
    private final List<Button> partyButtons = new ArrayList<>();
    private int firstSlot = -1;
    private int secondSlot = -1;

    DaycareScreen(DaycareNetwork.ViewPayload payload) {
        super(Component.translatable("screen.cobbleventure_adventure.daycare.title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(360, width - 30);
        int left = (width - panelWidth) / 2;
        int top = Math.max(28, (height - 210) / 2);
        if (payload.state().equals("EMPTY")) {
            for (int index = 0; index < 6; index++) {
                int slot = index;
                String label = payload.partySlots().get(index);
                Button button = Button.builder(
                    Component.literal(label.isBlank() ? (index + 1) + ". —" : (index + 1) + ". " + label),
                    ignored -> select(slot)
                ).bounds(left + (index % 2) * (panelWidth / 2 + 2),
                    top + 42 + (index / 2) * 26, panelWidth / 2 - 2, 22).build();
                button.active = !label.isBlank();
                partyButtons.add(addRenderableWidget(button));
            }
            addRenderableWidget(Button.builder(
                Component.translatable("screen.cobbleventure_adventure.daycare.deposit", payload.fee()),
                ignored -> send(DaycareNetwork.Action.DEPOSIT)
            ).bounds(left, top + 128, panelWidth, 22).build());
        } else {
            DaycareNetwork.Action primary = payload.state().equals("READY")
                ? DaycareNetwork.Action.COLLECT : DaycareNetwork.Action.REFRESH;
            String primaryKey = payload.state().equals("READY")
                ? "screen.cobbleventure_adventure.daycare.collect"
                : "screen.cobbleventure_adventure.daycare.refresh";
            addRenderableWidget(Button.builder(Component.translatable(primaryKey), ignored -> send(primary))
                .bounds(left, top + 72, panelWidth, 22).build());
            if (!payload.state().equals("READY")) {
                addRenderableWidget(Button.builder(
                    Component.translatable("screen.cobbleventure_adventure.daycare.cancel"),
                    ignored -> send(DaycareNetwork.Action.CANCEL)
                ).bounds(left, top + 100, panelWidth, 22).build());
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
            .bounds(left, top + 160, panelWidth, 22).build());
        refreshSelectionLabels();
    }

    private void select(int slot) {
        if (firstSlot == slot) firstSlot = -1;
        else if (secondSlot == slot) secondSlot = -1;
        else if (firstSlot < 0) firstSlot = slot;
        else secondSlot = slot;
        refreshSelectionLabels();
    }

    private void refreshSelectionLabels() {
        for (int index = 0; index < partyButtons.size(); index++) {
            String raw = payload.partySlots().get(index);
            String marker = index == firstSlot ? "[A] " : index == secondSlot ? "[B] " : "";
            partyButtons.get(index).setMessage(Component.literal(
                marker + (index + 1) + ". " + (raw.isBlank() ? "—" : raw)
            ));
        }
    }

    private void send(DaycareNetwork.Action action) {
        if (action == DaycareNetwork.Action.DEPOSIT && (firstSlot < 0 || secondSlot < 0)) return;
        PacketDistributor.sendToServer(new DaycareNetwork.ActionPayload(
            payload.npcId(), action, firstSlot, secondSlot
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int center = width / 2;
        int top = Math.max(28, (height - 210) / 2);
        graphics.drawCenteredString(font, title, center, top + 8, 0xFFFFFF);
        Component status = switch (payload.state()) {
            case "READY" -> Component.translatable("screen.cobbleventure_adventure.daycare.ready");
            case "BREEDING" -> Component.translatable(
                "screen.cobbleventure_adventure.daycare.breeding", payload.remainingMinutes()
            );
            default -> Component.translatable("screen.cobbleventure_adventure.daycare.select_parents");
        };
        graphics.drawCenteredString(font, status, center, top + 25, 0xD8E8C8);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
