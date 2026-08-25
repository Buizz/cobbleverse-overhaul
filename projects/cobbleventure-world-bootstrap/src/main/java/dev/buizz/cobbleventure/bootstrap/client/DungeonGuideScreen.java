package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.DungeonGuideNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Compact confirmation screen shown before a dungeon run is created. */
public final class DungeonGuideScreen extends Screen {
    private final DungeonGuideNetwork.GuideData data;
    private boolean answered;

    private DungeonGuideScreen(DungeonGuideNetwork.GuideData data) {
        super(Component.literal(data.title()));
        this.data = data;
    }

    public static void open(DungeonGuideNetwork.GuideData data) {
        Minecraft.getInstance().setScreen(new DungeonGuideScreen(data));
    }

    @Override
    protected void init() {
        int panelBottom = height / 2 + 100;
        addRenderableWidget(Button.builder(
            Component.literal("입장"), button -> answer(true)
        ).bounds(width / 2 - 104, panelBottom - 30, 100, 20).build());
        addRenderableWidget(Button.builder(
            Component.literal("취소"), button -> answer(false)
        ).bounds(width / 2 + 4, panelBottom - 30, 100, 20).build());
    }

    private void answer(boolean accepted) {
        if (answered) {
            return;
        }
        answered = true;
        DungeonGuideNetwork.respond(data.entranceId(), accepted);
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void onClose() {
        if (!answered) {
            answered = true;
            DungeonGuideNetwork.respond(data.entranceId(), false);
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        int left = width / 2 - 150;
        int top = height / 2 - 105;
        int right = width / 2 + 150;
        int bottom = height / 2 + 100;
        graphics.fill(left, top, right, bottom, 0xE6161D26);
        graphics.fill(left, top, right, top + 3, 0xFFB84747);
        graphics.drawCenteredString(font, data.title(), width / 2, top + 16, 0xFFFFFFFF);

        int lineY = top + 40;
        for (FormattedCharSequence line : font.split(
            Component.literal(data.description()), 260
        )) {
            graphics.drawString(font, line, left + 20, lineY, 0xFFD5DDE7, false);
            lineY += 11;
        }
        lineY += 8;
        graphics.drawString(
            font,
            "권장 레벨  Lv." + data.recommendedMin() + "–" + data.recommendedMax(),
            left + 20, lineY, 0xFFFFD166, false
        );
        lineY += 16;
        graphics.drawString(
            font,
            "내부 레벨  Lv." + data.internalMin() + "–" + data.internalMax(),
            left + 20, lineY, 0xFFB9C8DB, false
        );
        lineY += 16;
        graphics.drawString(
            font, "인원: 1명 · 고정 지역 · 퇴장 시 귀환",
            left + 20, lineY, 0xFFB9C8DB, false
        );
        lineY += 16;
        graphics.drawString(
            font, "현재 테스트 버전에서는 NPC·보상·초기화가 적용되지 않습니다.",
            left + 20, lineY, 0xFFFF9F80, false
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
