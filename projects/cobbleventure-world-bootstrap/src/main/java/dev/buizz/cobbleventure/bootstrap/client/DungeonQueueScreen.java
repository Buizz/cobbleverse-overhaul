package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.DungeonGuideNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Non-pausing queue screen backed by the server's dungeon entry request. */
public final class DungeonQueueScreen extends Screen {
    private final DungeonGuideNetwork.QueueData data;
    private Button cancelButton;
    private int elapsedTicks;
    private boolean preparing;
    private boolean closedByServer;

    private DungeonQueueScreen(DungeonGuideNetwork.QueueData data) {
        super(Component.literal(data.title()));
        this.data = data;
    }

    public static void open(DungeonGuideNetwork.QueueData data) {
        Minecraft.getInstance().setScreen(new DungeonQueueScreen(data));
    }

    public static void preparing(String entranceId) {
        if (Minecraft.getInstance().screen instanceof DungeonQueueScreen screen
            && screen.data.entranceId().equals(entranceId)) {
            screen.preparing = true;
            if (screen.cancelButton != null) {
                screen.cancelButton.active = false;
            }
        }
    }

    public static void close(String entranceId) {
        if (Minecraft.getInstance().screen instanceof DungeonQueueScreen screen
            && screen.data.entranceId().equals(entranceId)) {
            screen.closedByServer = true;
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    protected void init() {
        cancelButton = addRenderableWidget(Button.builder(
            Component.literal("대기 취소"), button -> cancel()
        ).bounds(width / 2 - 55, height / 2 + 45, 110, 20).build());
        cancelButton.active = !preparing;
    }

    @Override
    public void tick() {
        elapsedTicks++;
    }

    private void cancel() {
        if (preparing || closedByServer) {
            return;
        }
        requestCancellation();
        Minecraft.getInstance().setScreen(null);
    }

    private void requestCancellation() {
        if (preparing || closedByServer) {
            return;
        }
        closedByServer = true;
        DungeonGuideNetwork.cancelQueue(data.entranceId());
    }

    @Override
    public void onClose() {
        requestCancellation();
        super.onClose();
    }

    @Override
    public void removed() {
        requestCancellation();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        int left = width / 2 - 130;
        int top = height / 2 - 75;
        int right = width / 2 + 130;
        int bottom = height / 2 + 75;
        graphics.fill(left, top, right, bottom, 0xE6161D26);
        graphics.fill(left, top, right, top + 3, 0xFFB84747);
        graphics.drawCenteredString(font, data.title(), width / 2, top + 17, 0xFFFFFFFF);
        graphics.drawCenteredString(
            font,
            preparing ? "던전을 준비하고 있습니다." : "다른 도전자를 기다리는 중입니다.",
            width / 2, top + 48, preparing ? 0xFFFFD166 : 0xFFD5DDE7
        );
        graphics.drawCenteredString(
            font,
            "현재 인원 " + (preparing ? data.requiredPlayers() : data.currentPlayers())
                + " / " + data.requiredPlayers(),
            width / 2, top + 70, 0xFFB9C8DB
        );
        if (!preparing) {
            int remainingSeconds = Math.max(
                0, data.timeoutSeconds() - elapsedTicks / 20
            );
            graphics.drawCenteredString(
                font,
                "남은 시간 %02d:%02d".formatted(
                    remainingSeconds / 60, remainingSeconds % 60
                ),
                width / 2, top + 88, 0xFF8EA0B5
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
