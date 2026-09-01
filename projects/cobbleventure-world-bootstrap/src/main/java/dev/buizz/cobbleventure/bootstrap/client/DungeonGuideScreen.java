package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.DungeonGuideNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** Cinematic dungeon briefing shown before a dungeon run is created. */
public final class DungeonGuideScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 760;
    private static final int PANEL_MAX_HEIGHT = 360;
    private static final int BACKGROUND_WIDTH = 1672;
    private static final int BACKGROUND_HEIGHT = 940;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFE4EAF2;
    private static final int TEXT_MUTED = 0xFFAAB8C8;
    private static final int TEXT_GOLD = 0xFFFFD970;
    private static final int TEXT_DANGER = 0xFFFF9B87;

    private final DungeonGuideNetwork.GuideData data;
    private final ResourceLocation background;
    private boolean answered;

    private DungeonGuideScreen(DungeonGuideNetwork.GuideData data) {
        super(Component.literal(data.title()));
        this.data = data;
        this.background = ResourceLocation.parse(data.backgroundTexture());
    }

    public static void open(DungeonGuideNetwork.GuideData data) {
        Minecraft.getInstance().setScreen(new DungeonGuideScreen(data));
    }

    @Override
    protected void init() {
        int cardLeft = cardLeft();
        int cardRight = panelLeft() + panelWidth() - 16;
        int innerWidth = cardRight - cardLeft - 24;
        int buttonWidth = (innerWidth - 8) / 2;
        int buttonY = panelTop() + panelHeight() - 48;
        addRenderableWidget(new DungeonThemeButton(
            Component.literal("입장"), cardLeft + 12, buttonY, buttonWidth, 24,
            DungeonThemeButton.Tone.PRIMARY, () -> answer(true)
        ));
        addRenderableWidget(new DungeonThemeButton(
            Component.literal("취소"), cardLeft + 20 + buttonWidth, buttonY,
            buttonWidth, 24, DungeonThemeButton.Tone.SECONDARY,
            () -> answer(false)
        ));
    }

    private void answer(boolean accepted) {
        if (answered) return;
        answered = true;
        if (accepted) {
            DungeonTransitionOverlay.start(data.title(), data.backgroundTexture());
        }
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
        // The cinematic panel owns its overlay; the default blur obscures UI text.
    }

    @Override
    public void render(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();
        int cardLeft = cardLeft();
        int cardRight = right - 16;
        int cardTop = top + 16;
        int cardBottom = bottom - 16;

        graphics.fill(0, 0, width, height, 0xA8000000);
        graphics.fill(left + 5, top + 7, right + 5, bottom + 7, 0xA0000000);
        graphics.blit(
            background, left, top, panelWidth(), panelHeight(),
            0.0F, 0.0F, BACKGROUND_WIDTH, BACKGROUND_HEIGHT,
            BACKGROUND_WIDTH, BACKGROUND_HEIGHT
        );
        drawImageOverlays(graphics, left, top, right, bottom, cardLeft);
        graphics.fill(left, top, right, top + 2, 0xFFFF6878);
        graphics.fill(left, bottom - 2, right, bottom, 0xFF611F2A);

        drawHeroCopy(graphics, left, top, cardLeft, bottom);
        drawInformationCard(graphics, cardLeft, cardTop, cardRight, cardBottom);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawImageOverlays(
        GuiGraphics graphics, int left, int top, int right, int bottom, int cardLeft
    ) {
        graphics.fill(left, top, right, bottom, 0x25040A11);
        int gradientStart = Math.max(left, cardLeft - 92);
        int gradientWidth = Math.max(1, right - gradientStart);
        for (int step = 0; step < 24; step++) {
            int x1 = gradientStart + gradientWidth * step / 24;
            int x2 = gradientStart + gradientWidth * (step + 1) / 24;
            int alpha = 16 + step * 5;
            graphics.fill(x1, top, x2, bottom, alpha << 24);
        }
        for (int step = 0; step < 10; step++) {
            int y1 = bottom - panelHeight() * (step + 1) / 20;
            int y2 = bottom - panelHeight() * step / 20;
            int alpha = 18 + (9 - step) * 12;
            graphics.fill(left, y1, cardLeft, y2, alpha << 24);
        }
    }

    private void drawHeroCopy(
        GuiGraphics graphics, int left, int top, int cardLeft, int bottom
    ) {
        int copyLeft = left + 24;
        int copyWidth = Math.max(80, cardLeft - copyLeft - 20);
        graphics.fill(copyLeft, top + 20, copyLeft + 3, top + 34, 0xFFFF6878);
        graphics.drawString(
            font, "DUNGEON BRIEFING", copyLeft + 9, top + 23,
            0xFFFFA1AA, false
        );

        graphics.pose().pushPose();
        graphics.pose().translate(copyLeft, top + 48, 0.0F);
        graphics.pose().scale(1.35F, 1.35F, 1.0F);
        graphics.drawString(
            font,
            font.plainSubstrByWidth(data.title(), (int)(copyWidth / 1.35F)),
            0, 0, TEXT_PRIMARY, true
        );
        graphics.pose().popPose();

        int descriptionY = top + 72;
        for (FormattedCharSequence line : font.split(
            Component.literal(data.description()), copyWidth
        )) {
            graphics.drawString(
                font, line, copyLeft, descriptionY, TEXT_SECONDARY, true
            );
            descriptionY += 11;
            if (descriptionY > top + 126) break;
        }

        String classification = data.infoMode().equals("mystery")
            ? "분류 미확인" : "작전 정보 확인됨";
        drawChip(
            graphics, copyLeft, bottom - 38,
            classification, data.infoMode().equals("mystery")
                ? 0xCC6D5630 : 0xCC253D4F
        );
    }

    private void drawInformationCard(
        GuiGraphics graphics, int left, int top, int right, int bottom
    ) {
        graphics.fill(left + 4, top + 5, right + 4, bottom + 5, 0x76000000);
        graphics.fill(left, top, right, bottom, 0xE80A111B);
        graphics.fill(left, top, left + 2, bottom, 0xFFFF6878);
        graphics.fill(left + 12, top + 42, right - 12, top + 43, 0xFF334152);

        graphics.drawString(font, "도전 정보", left + 14, top + 14, TEXT_PRIMARY, false);
        graphics.drawString(
            font,
            "현재 파티 " + data.currentPartySize() + "마리 · 휴대 허용 "
                + partySizeRangeLabel(),
            left + 14, top + 27,
            data.currentPartySize() >= data.minimumPartySize()
                && data.currentPartySize() <= data.maximumPartySize()
                ? TEXT_MUTED : TEXT_DANGER,
            false
        );

        int gap = 6;
        int tileLeft = left + 12;
        int tileRight = right - 12;
        int tileWidth = (tileRight - tileLeft - gap) / 2;
        int tileTop = top + 52;
        boolean roomy = panelHeight() >= 320;
        int reservedBottom = roomy && data.tetherMaxDistance() > 0 ? 112 : 92;
        int tileAreaHeight = Math.max(58, bottom - reservedBottom - tileTop);
        int tileHeight = Math.max(27, Math.min(46, (tileAreaHeight - gap) / 2));

        boolean recommended = data.currentPartyLevel() >= data.recommendedMin()
            && data.currentPartyLevel() <= data.recommendedMax();
        drawTile(
            graphics, tileLeft, tileTop, tileWidth, tileHeight,
            "권장 레벨", "Lv." + data.recommendedMin() + "–" + data.recommendedMax(),
            TEXT_GOLD
        );
        drawTile(
            graphics, tileLeft + tileWidth + gap, tileTop, tileWidth, tileHeight,
            "현재 파티",
            (data.levelMeasure().equals("highest") ? "최고 " : "평균 ")
                + "Lv." + data.currentPartyLevel(),
            recommended ? 0xFF83E5B0 : TEXT_DANGER
        );
        drawTile(
            graphics, tileLeft, tileTop + tileHeight + gap, tileWidth, tileHeight,
            "내부 레벨",
            data.infoMode().equals("mystery")
                ? "미확인" : "Lv." + data.internalMin() + "–" + data.internalMax(),
            TEXT_SECONDARY
        );
        drawTile(
            graphics, tileLeft + tileWidth + gap,
            tileTop + tileHeight + gap, tileWidth, tileHeight,
            "도전 방식", modeLabel(), TEXT_SECONDARY
        );

        int statusY = tileTop + (tileHeight + gap) * 2 + 5;
        if (roomy && data.tetherMaxDistance() > 0) {
            drawStatusLine(
                graphics, left + 12, right - 12, statusY,
                "협력 거리 " + data.tetherWarnDistance() + "–"
                    + data.tetherMaxDistance() + "블록",
                TEXT_GOLD
            );
        }
        if (roomy) {
            drawBattleRules(
                graphics, left + 12,
                statusY + (data.tetherMaxDistance() > 0 ? 26 : 0)
            );
        }

        String wipe = data.wipeReturn().equals("pokemon_center")
            ? "패배 시 포켓몬센터 복귀" : "패배 시 입구 복귀";
        if (data.healOnWipe()) wipe += " · 파티 회복";
        drawStatusLine(
            graphics, left + 12, right - 12, bottom - 75, wipe, TEXT_DANGER
        );
    }

    private void drawBattleRules(GuiGraphics graphics, int left, int y) {
        int x = left;
        x = drawRuleChip(
            graphics, x, y,
            data.allowFlee() ? "도주 가능" : "도주 불가",
            data.allowFlee() ? 0xCC285140 : 0xCC6B2932
        );
        x = drawRuleChip(
            graphics, x + 5, y,
            data.allowCapture() ? "포획 가능" : "포획 불가",
            data.allowCapture() ? 0xCC285140 : 0xCC6B2932
        );
        drawRuleChip(
            graphics, x + 5, y,
            data.allowItems() ? "아이템 가능" : "아이템 불가",
            data.allowItems() ? 0xCC285140 : 0xCC6B2932
        );
    }

    private int drawRuleChip(
        GuiGraphics graphics, int x, int y, String text, int color
    ) {
        int chipWidth = font.width(text) + 10;
        graphics.fill(x, y, x + chipWidth, y + 17, color);
        graphics.drawString(font, text, x + 5, y + 4, TEXT_PRIMARY, false);
        return x + chipWidth;
    }

    private void drawTile(
        GuiGraphics graphics, int x, int y, int width, int height,
        String label, String value, int valueColor
    ) {
        graphics.fill(x, y, x + width, y + height, 0xC0182432);
        graphics.fill(x, y, x + 2, y + height, 0xFF3F5266);
        graphics.drawString(font, label, x + 8, y + 6, TEXT_MUTED, false);
        graphics.drawString(
            font,
            font.plainSubstrByWidth(value, Math.max(20, width - 16)),
            x + 8, y + Math.max(16, height - 13), valueColor, false
        );
    }

    private void drawStatusLine(
        GuiGraphics graphics, int left, int right, int y, String text, int color
    ) {
        graphics.fill(left, y, right, y + 19, 0xC0131D29);
        graphics.fill(left, y, left + 2, y + 19, color);
        graphics.drawString(
            font,
            font.plainSubstrByWidth(text, Math.max(20, right - left - 16)),
            left + 8, y + 5, color, false
        );
    }

    private void drawChip(
        GuiGraphics graphics, int x, int y, String text, int color
    ) {
        int chipWidth = font.width(text) + 16;
        graphics.fill(x, y, x + chipWidth, y + 18, color);
        graphics.fill(x, y, x + 2, y + 18, 0xFFFF6878);
        graphics.drawString(font, text, x + 8, y + 5, TEXT_PRIMARY, false);
    }

    private String modeLabel() {
        String mode = data.multiplayerMode().equals("cooperative") ? "협력형"
            : data.multiplayerMode().equals("independent") ? "독립행동형" : "싱글";
        return data.requiredPlayers() + "인 · " + mode
            + (data.repeatable() ? " · 반복" : " · 1회");
    }

    private String partySizeRangeLabel() {
        if (data.minimumPartySize() == data.maximumPartySize()) {
            return data.maximumPartySize() + "마리";
        }
        return data.minimumPartySize() + "–" + data.maximumPartySize() + "마리";
    }

    private int panelWidth() {
        return Math.min(PANEL_MAX_WIDTH, width - 24);
    }

    private int panelHeight() {
        return Math.min(PANEL_MAX_HEIGHT, height - 20);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (height - panelHeight()) / 2;
    }

    private int cardLeft() {
        int ratio = panelWidth() < 560 ? 40 : 56;
        return panelLeft() + panelWidth() * ratio / 100;
    }
}
