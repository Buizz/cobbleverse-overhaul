package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.DungeonGuideNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lists the concrete item rewards granted after a dungeon clear. */
public final class DungeonRewardScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 380;
    private static final int PANEL_MAX_HEIGHT = 300;
    private static final int ROW_HEIGHT = 27;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFE7EEF7;
    private static final int TEXT_MUTED = 0xFFC7D3E2;
    private static final int TEXT_GOLD = 0xFFFFD970;

    private final DungeonGuideNetwork.RewardData data;
    private int scroll;

    private DungeonRewardScreen(DungeonGuideNetwork.RewardData data) {
        super(Component.literal("던전 보상"));
        this.data = data;
    }

    public static void open(DungeonGuideNetwork.RewardData data) {
        Minecraft.getInstance().setScreen(new DungeonRewardScreen(data));
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(180, panelWidth() - 32);
        addRenderableWidget(new DungeonThemeButton(
            Component.literal("확인"),
            width / 2 - buttonWidth / 2,
            panelTop() + panelHeight() - 34,
            buttonWidth,
            22,
            DungeonThemeButton.Tone.PRIMARY,
            () -> Minecraft.getInstance().setScreen(null)
        ));
        scroll = Math.min(scroll, maxScroll());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        // The panel owns its dim overlay so text and item icons remain sharp.
    }

    @Override
    public void render(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();
        int listTop = top + 67;
        int listBottom = bottom - 47;

        graphics.fill(0, 0, width, height, 0x76000000);
        drawPanel(graphics, left, top, right, bottom);
        graphics.drawCenteredString(
            font, "던전 보상", width / 2, top + 10, 0xFFFF7180
        );
        graphics.drawCenteredString(
            font, data.dungeonName(), width / 2, top + 25, TEXT_PRIMARY
        );
        graphics.drawCenteredString(
            font,
            data.firstClear()
                ? "첫 클리어 보상 · " + data.clearCount() + "회차"
                : "클리어 보상 · " + data.clearCount() + "회차",
            width / 2,
            top + 43,
            data.firstClear() ? TEXT_GOLD : TEXT_MUTED
        );

        DungeonGuideNetwork.RewardEntry hovered = null;
        if (data.rewards().isEmpty()) {
            graphics.drawCenteredString(
                font, "지급된 아이템이 없습니다.", width / 2,
                listTop + Math.max(0, (listBottom - listTop - font.lineHeight) / 2),
                TEXT_MUTED
            );
        } else {
            graphics.enableScissor(left + 15, listTop, right - 15, listBottom);
            for (int row = 0; row < visibleRows(); row++) {
                int index = scroll + row;
                if (index >= data.rewards().size()) break;
                DungeonGuideNetwork.RewardEntry reward = data.rewards().get(index);
                int y = listTop + row * ROW_HEIGHT;
                boolean rowHovered = mouseX >= left + 16 && mouseX < right - 16
                    && mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
                graphics.fill(
                    left + 16, y, right - 16, y + ROW_HEIGHT - 3,
                    rowHovered ? 0xFF233247 : 0xFF151F2C
                );
                graphics.fill(
                    left + 16, y, left + 19, y + ROW_HEIGHT - 3,
                    rowHovered ? 0xFFFFD970 : 0xFFB84755
                );
                graphics.renderItem(reward.stack(), left + 24, y + 4);
                String count = "×" + reward.count();
                int countWidth = font.width(count);
                String name = font.plainSubstrByWidth(
                    reward.stack().getHoverName().getString(),
                    Math.max(30, panelWidth() - countWidth - 86)
                );
                graphics.drawString(
                    font, name, left + 47, y + 8, TEXT_SECONDARY, false
                );
                graphics.drawString(
                    font, count, right - 25 - countWidth, y + 8, TEXT_GOLD, false
                );
                if (rowHovered) hovered = reward;
            }
            graphics.disableScissor();
            drawScrollbar(graphics, right, listTop, listBottom);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        if (hovered != null) {
            graphics.renderTooltip(font, hovered.stack(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(
        double mouseX, double mouseY, double scrollX, double scrollY
    ) {
        int listTop = panelTop() + 67;
        int listBottom = panelTop() + panelHeight() - 47;
        if (mouseX >= panelLeft() + 16 && mouseX < panelLeft() + panelWidth() - 16
            && mouseY >= listTop && mouseY < listBottom) {
            scroll = Math.max(0, Math.min(
                maxScroll(), scroll + (scrollY < 0 ? 1 : -1)
            ));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int panelWidth() {
        return Math.min(PANEL_MAX_WIDTH, width - 24);
    }

    private int panelHeight() {
        return Math.min(PANEL_MAX_HEIGHT, height - 16);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (height - panelHeight()) / 2;
    }

    private int visibleRows() {
        return Math.max(1, (panelHeight() - 114) / ROW_HEIGHT);
    }

    private int maxScroll() {
        return Math.max(0, data.rewards().size() - visibleRows());
    }

    private void drawPanel(
        GuiGraphics graphics, int left, int top, int right, int bottom
    ) {
        graphics.fill(left + 4, top + 5, right + 4, bottom + 5, 0xA0000000);
        graphics.fill(left, top, right, bottom, 0xFF7D2732);
        graphics.fill(left + 2, top + 3, right - 2, bottom - 2, 0xFF0C121C);
        graphics.fill(left + 2, top + 3, right - 2, top + 6, 0xFFE05666);
        graphics.fill(left + 16, top + 59, right - 16, top + 60, 0xFF354354);
        graphics.fill(left + 2, bottom - 47, right - 2, bottom - 46, 0xFF354354);
    }

    private void drawScrollbar(
        GuiGraphics graphics, int right, int listTop, int listBottom
    ) {
        if (maxScroll() == 0) return;
        int trackTop = listTop;
        int trackBottom = listBottom - 3;
        int trackHeight = trackBottom - trackTop;
        int thumbHeight = Math.max(
            12, trackHeight * visibleRows() / data.rewards().size()
        );
        int thumbTop = trackTop
            + (trackHeight - thumbHeight) * scroll / maxScroll();
        graphics.fill(right - 14, trackTop, right - 12, trackBottom, 0xFF354354);
        graphics.fill(
            right - 14, thumbTop, right - 12, thumbTop + thumbHeight, 0xFFFF7180
        );
    }
}
