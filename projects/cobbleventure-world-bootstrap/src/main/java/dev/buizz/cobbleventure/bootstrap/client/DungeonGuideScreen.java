package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.DungeonGuideNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Compact confirmation screen shown before a dungeon run is created. */
public final class DungeonGuideScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 360;
    private static final int PANEL_HEIGHT = 236;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFE7EEF7;
    private static final int TEXT_MUTED = 0xFFC7D3E2;
    private static final int TEXT_GOLD = 0xFFFFD970;
    private static final int TEXT_DANGER = 0xFFFF9B87;

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
        int left = panelLeft();
        int bottom = panelTop() + PANEL_HEIGHT;
        int innerWidth = panelWidth() - 32;
        int buttonWidth = (innerWidth - 8) / 2;
        int buttonY = bottom - 34;
        addRenderableWidget(new DungeonThemeButton(
            Component.literal("입장"), left + 16, buttonY, buttonWidth, 22,
            DungeonThemeButton.Tone.PRIMARY, () -> answer(true)
        ));
        addRenderableWidget(new DungeonThemeButton(
            Component.literal("취소"), left + 24 + buttonWidth, buttonY,
            buttonWidth, 22, DungeonThemeButton.Tone.SECONDARY,
            () -> answer(false)
        ));
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
    public void renderBackground(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        // The panel draws its own dim overlay. Minecraft's default implementation
        // blurs the completed screen, including its text and buttons.
    }

    @Override
    public void render(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + PANEL_HEIGHT;
        graphics.fill(0, 0, width, height, 0x76000000);
        drawPanel(graphics, left, top, right, bottom);
        graphics.drawCenteredString(
            font, "던전 브리핑", width / 2, top + 10, 0xFFFF7180
        );
        graphics.drawCenteredString(
            font, data.title(), width / 2, top + 25, TEXT_PRIMARY
        );

        int lineY = top + 47;
        for (FormattedCharSequence line : font.split(
            Component.literal(data.description()), panelWidth() - 40
        )) {
            graphics.drawString(font, line, left + 20, lineY, TEXT_SECONDARY, true);
            lineY += 10;
        }
        lineY += 7;
        lineY = drawInfoRow(
            graphics, left, right, lineY,
            "권장 레벨  Lv." + data.recommendedMin() + "–" + data.recommendedMax()
                + " · 파티 " + (data.levelMeasure().equals("highest") ? "최고" : "평균")
                + " Lv." + data.currentPartyLevel(),
            TEXT_GOLD
        );
        if (data.tetherMaxDistance() > 0) {
            lineY = drawInfoRow(
                graphics, left, right, lineY,
                "협력 거리: " + data.tetherWarnDistance() + "블록부터 경고 · "
                    + data.tetherMaxDistance() + "블록 초과 시 동료에게 복귀",
                TEXT_GOLD
            );
        }
        lineY = drawInfoRow(
            graphics, left, right, lineY,
            "내부 레벨  Lv." + data.internalMin() + "–" + data.internalMax(),
            TEXT_MUTED
        );
        lineY = drawInfoRow(
            graphics, left, right, lineY,
            "인원: " + data.requiredPlayers() + "명 · "
                + (data.multiplayerMode().equals("cooperative") ? "협력형" :
                    data.multiplayerMode().equals("independent") ? "독립행동형" : "싱글")
                + " · 고정 지역 · "
                + (data.repeatable() ? "반복 도전 가능" : "1회 클리어"),
            TEXT_MUTED
        );
        drawInfoRow(
            graphics, left, right, lineY,
            "패배: 전체 초기화 · "
                + (data.wipeReturn().equals("pokemon_center")
                    ? "포켓몬센터 복귀"
                    : "입구 복귀")
                + (data.healOnWipe() ? " · 파티 회복" : ""),
            TEXT_DANGER
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int panelWidth() {
        return Math.min(PANEL_MAX_WIDTH, width - 24);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return Math.max(4, (height - PANEL_HEIGHT) / 2);
    }

    private void drawPanel(
        GuiGraphics graphics, int left, int top, int right, int bottom
    ) {
        graphics.fill(left + 4, top + 5, right + 4, bottom + 5, 0xA0000000);
        graphics.fill(left, top, right, bottom, 0xFF7D2732);
        graphics.fill(left + 2, top + 3, right - 2, bottom - 2, 0xFF0C121C);
        graphics.fill(left + 2, top + 3, right - 2, top + 6, 0xFFE05666);
        graphics.fill(left + 16, top + 40, right - 16, top + 41, 0xFF354354);
        graphics.fill(left + 2, bottom - 47, right - 2, bottom - 46, 0xFF354354);
    }

    private int drawInfoRow(
        GuiGraphics graphics,
        int left,
        int right,
        int y,
        String text,
        int color
    ) {
        var lines = font.split(Component.literal(text), panelWidth() - 48);
        int rowBottom = y + lines.size() * 10 + 1;
        graphics.fill(left + 16, y - 3, right - 16, rowBottom, 0xFF151F2C);
        graphics.fill(left + 16, y - 3, left + 18, rowBottom, 0xFFB84755);
        int textY = y;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, left + 23, textY, color, true);
            textY += 10;
        }
        return rowBottom + 6;
    }
}
