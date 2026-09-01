package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.QuestSummaryNetwork;
import dev.buizz.cobbleventure.playermenu.QuestSummaryNetwork.QuestEntry;
import dev.buizz.cobbleventure.playermenu.QuestSummaryNetwork.QuestObjective;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Player-facing quest log with active/completed lists and objective details. */
public final class QuestLogScreen extends Screen {
    private static final int SCREEN_MARGIN = 12;
    private static final int HEADER_HEIGHT = 38;
    private static final int FOOTER_HEIGHT = 28;
    private static final int CONTENT_GAP = 8;
    private static final int ROW_HEIGHT = 38;
    private static final int ROW_GAP = 3;

    private final Screen parent;
    private final MenuTheme theme;
    private final List<QuestRow> questRows = new ArrayList<>();
    private Mode mode = Mode.ACTIVE;
    private String selectedId = "";
    private int scrollOffset;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private int detailX;
    private int detailWidth;
    private long observedRevision = -1;
    private boolean snapshotRequested;

    public QuestLogScreen(Screen parent) {
        super(Component.translatable("screen.cobbleventure_player_menu.quest_log.title"));
        this.parent = parent;
        this.theme = MenuTheme.load(Minecraft.getInstance());
    }

    @Override protected void init() {
        panelWidth = Math.min(760, width - SCREEN_MARGIN * 2);
        panelHeight = Math.min(430, height - SCREEN_MARGIN * 2);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        int contentX = panelX + 10;
        int contentY = panelY + HEADER_HEIGHT;
        int contentWidth = panelWidth - 20;
        int contentHeight = panelHeight - HEADER_HEIGHT - FOOTER_HEIGHT;
        listWidth = Math.clamp((int)(contentWidth * .38F), 142, 252);
        listX = contentX;
        listY = contentY;
        listHeight = contentHeight;
        detailX = listX + listWidth + CONTENT_GAP;
        detailWidth = Math.max(94, contentWidth - listWidth - CONTENT_GAP);

        addRenderableWidget(new ThemedButton(
            theme,
            Component.translatable("screen.cobbleventure_player_menu.quest_log.active"),
            panelX + panelWidth - 164, panelY + 10, 74, 20,
            MenuTheme.ButtonVariant.SECONDARY,
            () -> switchMode(Mode.ACTIVE),
            () -> mode == Mode.ACTIVE
        ));
        addRenderableWidget(new ThemedButton(
            theme,
            Component.translatable("screen.cobbleventure_player_menu.quest_log.completed"),
            panelX + panelWidth - 86, panelY + 10, 74, 20,
            MenuTheme.ButtonVariant.SECONDARY,
            () -> switchMode(Mode.COMPLETED),
            () -> mode == Mode.COMPLETED
        ));
        addRenderableWidget(new ThemedButton(
            theme,
            Component.translatable("screen.cobbleventure_player_menu.quest_log.back"),
            panelX + panelWidth - 66, panelY + panelHeight - 23, 54, 18,
            MenuTheme.ButtonVariant.GHOST,
            this::onClose
        ));

        observedRevision = QuestSummaryNetwork.clientRevision();
        rebuildQuestRows();
        if (!snapshotRequested) {
            snapshotRequested = true;
            QuestSummaryNetwork.requestSnapshot();
        }
    }

    @Override public void tick() {
        super.tick();
        if (observedRevision != QuestSummaryNetwork.clientRevision()) {
            observedRevision = QuestSummaryNetwork.clientRevision();
            rebuildWidgets();
        }
    }

    @Override public void render(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {
        graphics.fill(0, 0, width, height, theme.scrim);
        ThemedOverlayPanel.draw(
            graphics, theme, panelX, panelY, panelWidth, panelHeight
        );
        ThemedOverlayPanel.draw(
            graphics, theme, listX, listY, listWidth, listHeight, 1, theme.border
        );
        ThemedOverlayPanel.draw(
            graphics, theme, detailX, listY, detailWidth, listHeight, 1, theme.accent
        );

        theme.drawText(
            graphics, font, title, panelX + 14, panelY + 11,
            MenuTheme.TextRole.TITLE, theme.text(MenuTheme.TextRole.TITLE).color()
        );
        renderEmptyList(graphics);
        renderDetail(graphics);
        renderScrollStatus(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {}

    @Override public boolean mouseScrolled(
        double mouseX, double mouseY, double scrollX, double scrollY
    ) {
        if (mouseX >= listX && mouseX < listX + listWidth
            && mouseY >= listY && mouseY < listY + listHeight) {
            List<QuestEntry> entries = visibleEntries();
            int maximum = Math.max(0, entries.size() - visibleRowCount());
            int next = Math.clamp(scrollOffset - (int)Math.signum(scrollY), 0, maximum);
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }

    private void switchMode(Mode next) {
        if (mode == next) return;
        mode = next;
        selectedId = "";
        scrollOffset = 0;
        rebuildWidgets();
    }

    private void rebuildQuestRows() {
        questRows.clear();
        List<QuestEntry> entries = visibleEntries();
        if (entries.stream().noneMatch(entry -> entry.id().equals(selectedId))) {
            selectedId = entries.isEmpty() ? "" : entries.getFirst().id();
        }
        int visible = visibleRowCount();
        int maximum = Math.max(0, entries.size() - visible);
        scrollOffset = Math.clamp(scrollOffset, 0, maximum);
        for (int index = scrollOffset; index < Math.min(entries.size(), scrollOffset + visible); index++) {
            QuestEntry entry = entries.get(index);
            QuestRow row = new QuestRow(
                entry,
                listX + 6,
                listY + 7 + (index - scrollOffset) * (ROW_HEIGHT + ROW_GAP),
                listWidth - 12,
                ROW_HEIGHT
            );
            addRenderableWidget(row);
            questRows.add(row);
        }
    }

    private List<QuestEntry> visibleEntries() {
        boolean completed = mode == Mode.COMPLETED;
        return QuestSummaryNetwork.clientEntries().stream()
            .filter(entry -> completed == "completed".equals(entry.state()))
            .toList();
    }

    private int visibleRowCount() {
        return Math.max(1, (listHeight - 14 + ROW_GAP) / (ROW_HEIGHT + ROW_GAP));
    }

    private QuestEntry selectedEntry() {
        return QuestSummaryNetwork.clientEntries().stream()
            .filter(entry -> entry.id().equals(selectedId))
            .findFirst().orElse(null);
    }

    private void renderEmptyList(GuiGraphics graphics) {
        if (!visibleEntries().isEmpty()) return;
        theme.drawWrappedText(
            graphics, font,
            Component.translatable(
                mode == Mode.ACTIVE
                    ? "screen.cobbleventure_player_menu.quest_log.empty.active"
                    : "screen.cobbleventure_player_menu.quest_log.empty.completed"
            ),
            listX + 12, listY + 14, listWidth - 24,
            MenuTheme.TextRole.BODY, theme.mutedTextColor, 5
        );
    }

    private void renderDetail(GuiGraphics graphics) {
        QuestEntry entry = selectedEntry();
        int x = detailX + 12;
        int y = listY + 12;
        int width = detailWidth - 24;
        if (entry == null) {
            theme.drawWrappedText(
                graphics, font,
                Component.translatable("screen.cobbleventure_player_menu.quest_log.select"),
                x, y, width, MenuTheme.TextRole.BODY, theme.mutedTextColor, 4
            );
            return;
        }

        Component category = Component.translatable(
            "screen.cobbleventure_player_menu.quest_log.category." + entry.category()
        );
        theme.drawText(graphics, font, category, x, y, MenuTheme.TextRole.LABEL, theme.accent);
        Component state = Component.translatable(
            "screen.cobbleventure_player_menu.quest_summary.state." + entry.state()
        );
        theme.drawText(
            graphics, font, state,
            detailX + detailWidth - 12 - theme.textWidth(font, state, MenuTheme.TextRole.LABEL),
            y, MenuTheme.TextRole.LABEL,
            "completed".equals(entry.state()) || "ready".equals(entry.state())
                ? theme.success : theme.secondaryTextColor
        );
        y += theme.textHeight(font, MenuTheme.TextRole.LABEL) + 6;
        y += theme.drawWrappedText(
            graphics, font, Component.literal(entry.title()), x, y, width,
            MenuTheme.TextRole.HEADING, theme.textColor, 3
        ) + 5;
        Component summary = entry.id().startsWith("default:gym/")
            ? Component.translatable("screen.cobbleventure_player_menu.quest_summary.gym_default")
            : Component.literal(entry.summary());
        if (!summary.getString().isBlank()) {
            y += theme.drawWrappedText(
                graphics, font, summary, x, y, width,
                MenuTheme.TextRole.BODY, theme.secondaryTextColor, 5
            ) + 8;
        }

        theme.drawText(
            graphics, font,
            Component.translatable("screen.cobbleventure_player_menu.quest_log.objectives"),
            x, y, MenuTheme.TextRole.HEADING
        );
        y += theme.textHeight(font, MenuTheme.TextRole.HEADING) + 6;
        int bottom = listY + listHeight - 30;
        for (QuestObjective objective : entry.objectives()) {
            if (y >= bottom) break;
            Component marker = Component.literal(objective.completed() ? "✓" : "○");
            theme.drawText(
                graphics, font, marker, x, y, MenuTheme.TextRole.BODY,
                objective.completed() ? theme.success : theme.mutedTextColor
            );
            int used = theme.drawWrappedText(
                graphics, font, Component.literal(objective.text()), x + 14, y,
                width - 14, MenuTheme.TextRole.BODY,
                objective.completed() ? theme.secondaryTextColor : theme.textColor, 3
            );
            y += Math.max(theme.textHeight(font, MenuTheme.TextRole.BODY), used) + 5;
        }

        Component completion = Component.translatable(
            "screen.cobbleventure_player_menu.quest_log.completion." + entry.completionMode()
        );
        theme.drawWrappedText(
            graphics, font, completion, x, listY + listHeight - 22, width,
            MenuTheme.TextRole.CAPTION, theme.mutedTextColor, 1
        );
    }

    private void renderScrollStatus(GuiGraphics graphics) {
        List<QuestEntry> entries = visibleEntries();
        if (entries.size() <= visibleRowCount()) return;
        Component status = Component.literal(
            (scrollOffset + 1) + "–" + Math.min(entries.size(), scrollOffset + visibleRowCount())
                + " / " + entries.size()
        );
        theme.drawText(
            graphics, font, status,
            listX + listWidth - 8 - theme.textWidth(font, status, MenuTheme.TextRole.CAPTION),
            listY + listHeight - 13, MenuTheme.TextRole.CAPTION, theme.mutedTextColor
        );
    }

    private enum Mode { ACTIVE, COMPLETED }

    private final class QuestRow extends AbstractButton {
        private final QuestEntry entry;

        private QuestRow(QuestEntry entry, int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(entry.title()));
            this.entry = entry;
        }

        @Override public void onPress() { selectedId = entry.id(); }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = entry.id().equals(selectedId);
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHoveredOrFocused(), selected
            );
            ThemedOverlayPanel.fillRoundedRect(
                graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                theme.rowRadius, style.border()
            );
            ThemedOverlayPanel.fillRoundedRect(
                graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), style.background()
            );
            Component category = Component.translatable(
                "screen.cobbleventure_player_menu.quest_log.category." + entry.category()
            );
            theme.drawText(
                graphics, font, category, getX() + 7, getY() + 5,
                MenuTheme.TextRole.CAPTION, selected ? theme.selectedTextColor : theme.secondaryTextColor
            );
            theme.drawWrappedText(
                graphics, font, Component.literal(entry.title()), getX() + 7, getY() + 18,
                getWidth() - 14, MenuTheme.TextRole.BODY, style.text(), 1
            );
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
