package dev.buizz.cobbleventure.adventure.event.client;

import dev.buizz.cobbleventure.adventure.event.EventDialogueNetwork;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Typed prompt followed by keyboard and mouse operated structured choices. */
public final class EventChoiceScreen extends Screen {
    private static final long INPUT_GUARD_MILLIS = 120L;
    private final EventDialogueNetwork.ChoiceOpenPayload payload;
    private final String prompt;
    private final List<String> options;
    private final EventDialogueTheme theme;
    private final long openedAt = System.currentTimeMillis();
    private DialoguePlayback promptPlayback;
    private Layout layout;
    private int selected;
    private int firstVisible;
    private boolean promptReady;
    private boolean replied;

    EventChoiceScreen(
        EventDialogueNetwork.ChoiceOpenPayload payload,
        String prompt,
        List<String> options
    ) {
        super(Component.translatable("screen.cobbleventure_adventure.choice.title"));
        this.payload = payload;
        this.prompt = prompt;
        this.options = List.copyOf(options);
        this.theme = EventDialogueTheme.parse(payload.themeJson());
        if (this.options.isEmpty()) throw new IllegalArgumentException("선택지가 필요합니다.");
    }

    @Override
    protected void init() {
        super.init();
        layout = calculateLayout();
        promptPlayback = new DialoguePlayback(DialoguePaginator.paginate(
            prompt,
            layout.promptLines(),
            value -> font.split(theme.text(value), unscaledWidth(layout.contentWidth())).size()
        ));
    }

    @Override
    public void tick() {
        super.tick();
        if (promptPlayback == null || promptReady) return;
        promptPlayback.tick();
        if (promptPlayback.lastPage() && promptPlayback.pageRevealed()) promptReady = true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (layout == null || promptPlayback == null) return;
        EventPanelRenderer.dialogue(
            graphics, layout.left(), layout.top(), layout.right(), layout.bottom(), theme
        );

        LivingEntity npc = EventDialoguePortrait.find(minecraft, payload.npcId());
        if (layout.portraitWidth() > 0) {
            EventDialoguePortrait.render(
                graphics, npc,
                layout.left() + 8, layout.top() + 8,
                layout.contentLeft() - 8, layout.bottom() - 8, theme
            );
        }

        List<FormattedCharSequence> promptLines = font.split(
            theme.text(promptPlayback.visibleText()), unscaledWidth(layout.contentWidth())
        );
        for (int index = 0; index < Math.min(promptLines.size(), layout.promptLines()); index++) {
            drawScaled(graphics, promptLines.get(index), layout.contentLeft(),
                layout.top() + 13 + index * visualLineHeight(), theme.textColor);
        }

        if (promptReady) {
            EventPanelRenderer.choice(
                graphics, layout.choiceLeft(), layout.choiceTop(),
                layout.choiceRight(), layout.choiceBottom(), theme
            );
            renderOptions(graphics, mouseX, mouseY);
        }
        Component controls = theme.text(Component.translatable(
            promptReady
                ? "screen.cobbleventure_adventure.choice.controls"
                : "screen.cobbleventure_adventure.choice.reveal"
        ));
        drawScaled(graphics, controls, layout.contentLeft(), layout.bottom() - 16,
            theme.hintColor, theme.hintScale);
        if (promptPlayback.pageCount() > 1 && !promptReady) {
            Component page = theme.text(promptPlayback.pageNumber() + "/" + promptPlayback.pageCount());
            drawScaled(graphics, page,
                layout.right() - 12 - Math.round(font.width(page) * theme.hintScale),
                layout.bottom() - 16, theme.pageColor, theme.hintScale);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOptions(GuiGraphics graphics, int mouseX, int mouseY) {
        int visible = visibleRows();
        keepSelectedVisible(visible);
        for (int row = 0; row < visible; row++) {
            int optionIndex = firstVisible + row;
            if (optionIndex >= options.size()) break;
            int y = layout.listTop() + row * theme.choiceRowHeight;
            boolean active = optionIndex == selected;
            boolean hovered = mouseX >= layout.optionLeft() && mouseX < layout.optionRight()
                && mouseY >= y && mouseY < y + theme.choiceRowHeight - 3;
            int background = active ? theme.choiceSelectedBackground
                : hovered ? theme.choiceHoverBackground : theme.choiceBackground;
            EventPanelRenderer.roundedFill(
                graphics, layout.optionLeft(), y, layout.optionRight(),
                y + theme.choiceRowHeight - 3, theme.menuRowRadius, background
            );
            if (active) {
                EventPanelRenderer.roundedFill(
                    graphics, layout.optionLeft() + 3, y + 3, layout.optionLeft() + 6,
                    y + theme.choiceRowHeight - 6, 1, theme.choiceSelectedAccent
                );
            }
            String prefix = active ? "▶ " : "  ";
            String label = font.plainSubstrByWidth(
                prefix + options.get(optionIndex), unscaledWidth(layout.optionWidth() - 14)
            );
            drawScaled(graphics, theme.text(label), layout.optionLeft() + 8,
                y + (theme.choiceRowHeight - 3 - visualLineHeight()) / 2,
                active ? theme.textColor : theme.choiceTextColor);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible behind the choice.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !inputReady()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (!promptReady) {
            advancePrompt();
            return true;
        }
        if (mouseX < layout.optionLeft() || mouseX >= layout.optionRight()
            || mouseY < layout.listTop()) return true;
        int row = (int)(mouseY - layout.listTop()) / theme.choiceRowHeight;
        int optionIndex = firstVisible + row;
        if (row >= 0 && row < visibleRows() && optionIndex < options.size()) {
            selected = optionIndex;
            complete(false);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!inputReady()) return super.keyPressed(keyCode, scanCode, modifiers);
        if (!promptReady) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                advancePrompt();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_LEFT) {
            move(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_RIGHT) {
            move(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            complete(false);
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int index = keyCode - GLFW.GLFW_KEY_1;
            if (index < options.size()) {
                selected = index;
                complete(false);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(
        double mouseX, double mouseY, double scrollX, double scrollY
    ) {
        if (promptReady && scrollY != 0.0D) move(scrollY > 0 ? -1 : 1);
        return true;
    }

    @Override public void onClose() { complete(true); }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }

    private void advancePrompt() {
        DialoguePlayback.AdvanceResult result = promptPlayback.advance();
        if (result == DialoguePlayback.AdvanceResult.COMPLETED) promptReady = true;
    }

    private int visibleRows() {
        return Math.max(
            1,
            layout.visibleRows()
        );
    }

    private void move(int amount) {
        selected = Math.floorMod(selected + amount, options.size());
    }

    private void keepSelectedVisible(int visible) {
        if (selected < firstVisible) firstVisible = selected;
        if (selected >= firstVisible + visible) firstVisible = selected - visible + 1;
    }

    private boolean inputReady() {
        return !replied && System.currentTimeMillis() - openedAt >= INPUT_GUARD_MILLIS;
    }

    private Layout calculateLayout() {
        int margin = Math.max(12, width / 18);
        int boxHeight = Math.min(theme.panelMaxHeight,
            Math.max(theme.panelMinHeight, Math.round(height * theme.panelHeightRatio)));
        int top = Math.max(12, height - boxHeight - 14);
        int portraitWidth = width >= 320 ? Math.min(104, boxHeight - 20) : 0;
        int contentLeft = margin + 14 + portraitWidth;
        int contentWidth = Math.max(90, width - margin - 14 - contentLeft);
        int promptLines = Math.max(2, (boxHeight - 48) / visualLineHeight());
        int choiceRight = width - margin;
        int choiceWidth = Math.min(theme.choicePanelWidth, Math.max(110, width / 2));
        int choiceBottom = Math.max(36, top - theme.choicePanelGap);
        int maximumChoiceHeight = Math.max(
            theme.choiceRowHeight + theme.choicePanelPadding * 2,
            choiceBottom - 10
        );
        int visibleRows = Math.max(1, Math.min(
            options.size(),
            (maximumChoiceHeight - theme.choicePanelPadding * 2) / theme.choiceRowHeight
        ));
        int choiceHeight = visibleRows * theme.choiceRowHeight + theme.choicePanelPadding * 2;
        int choiceTop = Math.max(8, choiceBottom - choiceHeight);
        int choiceLeft = choiceRight - choiceWidth;
        int optionLeft = choiceLeft + theme.choicePanelPadding;
        int optionRight = choiceRight - theme.choicePanelPadding;
        int listTop = choiceTop + theme.choicePanelPadding;
        return new Layout(
            margin, top, width - margin, height - 14,
            portraitWidth, contentLeft, contentWidth, promptLines,
            choiceLeft, choiceTop, choiceRight, choiceBottom,
            optionLeft, optionRight, listTop, visibleRows
        );
    }

    private void complete(boolean cancelled) {
        if (replied) return;
        replied = true;
        PacketDistributor.sendToServer(new EventDialogueNetwork.ChoiceCompletePayload(
            payload.token(), payload.npcId(), payload.scriptId(), payload.triggerInstance(),
            cancelled ? -1 : selected, cancelled
        ));
        if (minecraft != null) minecraft.setScreen(null);
    }

    private int visualLineHeight() {
        return Math.max(1, Math.round(font.lineHeight * theme.bodyScale));
    }

    private int unscaledWidth(int visualWidth) {
        return Math.max(1, (int)Math.floor(visualWidth / theme.bodyScale));
    }

    private void drawScaled(
        GuiGraphics graphics, Component component, int x, int y, int color, float scale
    ) {
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, component, Math.round(x / scale), Math.round(y / scale), color, false);
        graphics.pose().popPose();
    }

    private void drawScaled(
        GuiGraphics graphics, Component component, int x, int y, int color
    ) {
        drawScaled(graphics, component, x, y, color, theme.bodyScale);
    }

    private void drawScaled(
        GuiGraphics graphics, FormattedCharSequence text, int x, int y, int color
    ) {
        float scale = theme.bodyScale;
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, Math.round(x / scale), Math.round(y / scale), color, false);
        graphics.pose().popPose();
    }

    private record Layout(
        int left, int top, int right, int bottom,
        int portraitWidth, int contentLeft, int contentWidth,
        int promptLines,
        int choiceLeft, int choiceTop, int choiceRight, int choiceBottom,
        int optionLeft, int optionRight, int listTop, int visibleRows
    ) {
        int optionWidth() { return optionRight - optionLeft; }
    }
}
