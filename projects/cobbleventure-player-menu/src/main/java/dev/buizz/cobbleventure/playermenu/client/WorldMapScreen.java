package dev.buizz.cobbleventure.playermenu.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 월드맵의 최소 수직 프로토타입. 이후 발견 지역과 HabitatMapPanel을 이 화면에 연결한다.
 */
public final class WorldMapScreen extends Screen {
    private static final int PAGE_BACKGROUND = 0xF01A2028;
    private static final int MAP_BACKGROUND = 0xFF263A35;
    private static final int MAP_GRID = 0x504E7164;
    private static final int MAP_BORDER = 0xFF9AB29C;
    private static final int INFO_BACKGROUND = 0xFF202A35;
    private static final int PLAYER_MARKER = 0xFFFFD166;
    private static final int TEXT = 0xFFF3F5F7;
    private static final int MUTED_TEXT = 0xFFB7C2CC;
    private static final int MARGIN = 16;
    private static final int HEADER_HEIGHT = 34;
    private static final int FOOTER_HEIGHT = 34;
    private static final int PANEL_GAP = 10;
    private static final int INFO_WIDTH = 150;

    private final Screen parent;

    public WorldMapScreen(Screen parent) {
        super(Component.translatable("screen.cobbleventure_player_menu.world_map.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        Component label = Component.translatable(
            parent == null
                ? "screen.cobbleventure_player_menu.world_map.close"
                : "screen.cobbleventure_player_menu.world_map.back"
        );
        addRenderableWidget(Button.builder(label, ignored -> onClose())
            .bounds(width - MARGIN - 72, height - FOOTER_HEIGHT + 5, 72, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, PAGE_BACKGROUND);
        graphics.drawCenteredString(font, title, width / 2, 12, TEXT);

        Layout layout = layout();
        drawMap(graphics, layout);
        drawInfoPanel(graphics, layout);
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.hint"),
            MARGIN,
            height - FOOTER_HEIGHT + 10,
            MUTED_TEXT,
            false
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawMap(GuiGraphics graphics, Layout layout) {
        graphics.fill(layout.mapLeft(), layout.top(), layout.mapRight(), layout.bottom(), MAP_BACKGROUND);
        for (int x = layout.mapLeft() + 24; x < layout.mapRight(); x += 24) {
            graphics.fill(x, layout.top(), x + 1, layout.bottom(), MAP_GRID);
        }
        for (int y = layout.top() + 24; y < layout.bottom(); y += 24) {
            graphics.fill(layout.mapLeft(), y, layout.mapRight(), y + 1, MAP_GRID);
        }
        drawBorder(graphics, layout.mapLeft(), layout.top(), layout.mapRight(), layout.bottom(), MAP_BORDER);

        int centerX = (layout.mapLeft() + layout.mapRight()) / 2;
        int centerY = (layout.top() + layout.bottom()) / 2;
        graphics.fill(centerX - 3, centerY - 3, centerX + 4, centerY + 4, PLAYER_MARKER);
        graphics.drawCenteredString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.current_position"),
            centerX,
            centerY + 9,
            TEXT
        );
        graphics.drawString(font, "N", centerX - 3, layout.top() + 6, TEXT, false);
    }

    private void drawInfoPanel(GuiGraphics graphics, Layout layout) {
        graphics.fill(layout.infoLeft(), layout.top(), layout.infoRight(), layout.bottom(), INFO_BACKGROUND);
        drawBorder(graphics, layout.infoLeft(), layout.top(), layout.infoRight(), layout.bottom(), 0xFF66788A);

        int x = layout.infoLeft() + 10;
        int y = layout.top() + 10;
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.region_title"),
            x,
            y,
            TEXT,
            false
        );
        y += 18;
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.region_placeholder"),
            x,
            y,
            MUTED_TEXT,
            false
        );
        y += 24;
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.spawn_title"),
            x,
            y,
            TEXT,
            false
        );
        y += 18;
        graphics.drawWordWrap(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.spawn_placeholder"),
            x,
            y,
            INFO_WIDTH - 20,
            MUTED_TEXT
        );

        if (minecraft != null && minecraft.player != null) {
            String coordinates = String.format(
                "X %d  Z %d",
                minecraft.player.getBlockX(),
                minecraft.player.getBlockZ()
            );
            graphics.drawString(font, coordinates, x, layout.bottom() - 18, MUTED_TEXT, false);
        }
    }

    private Layout layout() {
        int top = HEADER_HEIGHT;
        int bottom = Math.max(top + 80, height - FOOTER_HEIGHT);
        int infoRight = width - MARGIN;
        int infoLeft = Math.max(MARGIN + 100, infoRight - INFO_WIDTH);
        int mapLeft = MARGIN;
        int mapRight = Math.max(mapLeft + 80, infoLeft - PANEL_GAP);
        return new Layout(mapLeft, mapRight, infoLeft, infoRight, top, bottom);
    }

    private static void drawBorder(
        GuiGraphics graphics,
        int left,
        int top,
        int right,
        int bottom,
        int color
    ) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private record Layout(
        int mapLeft,
        int mapRight,
        int infoLeft,
        int infoRight,
        int top,
        int bottom
    ) {
    }
}
