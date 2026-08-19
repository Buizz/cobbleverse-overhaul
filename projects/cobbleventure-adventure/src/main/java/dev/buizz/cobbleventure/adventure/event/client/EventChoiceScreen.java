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
    private static final int ROW_HEIGHT = 24;
    private static final long INPUT_GUARD_MILLIS = 120L;
    private final EventDialogueNetwork.ChoiceOpenPayload payload;
    private final String prompt;
    private final List<String> options;
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
        if (this.options.isEmpty()) throw new IllegalArgumentException("선택지가 필요합니다.");
    }

    @Override
    protected void init() {
        super.init();
        layout = calculateLayout();
        promptPlayback = new DialoguePlayback(DialoguePaginator.paginate(
            prompt,
            layout.promptLines(),
            value -> font.split(Component.literal(value), layout.contentWidth()).size()
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
        graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), 0xE8101720);
        graphics.fill(layout.left(), layout.top(), layout.right(), layout.top() + 2, 0xFFE8EDF2);

        LivingEntity npc = EventDialoguePortrait.find(minecraft, payload.npcId());
        if (layout.portraitWidth() > 0) {
            EventDialoguePortrait.render(
                graphics, npc,
                layout.left() + 8, layout.top() + 8,
                layout.contentLeft() - 8, layout.bottom() - 8,
                mouseX, mouseY
            );
        }

        List<FormattedCharSequence> promptLines = font.split(
            Component.literal(promptPlayback.visibleText()), layout.contentWidth()
        );
        for (int index = 0; index < Math.min(promptLines.size(), layout.promptLines()); index++) {
            graphics.drawString(
                font, promptLines.get(index), layout.contentLeft(),
                layout.top() + 13 + index * font.lineHeight,
                0xFFFFFFFF, false
            );
        }

        if (promptReady) renderOptions(graphics, mouseX, mouseY);
        Component controls = Component.translatable(
            promptReady
                ? "screen.cobbleventure_adventure.choice.controls"
                : "screen.cobbleventure_adventure.choice.reveal"
        );
        graphics.drawString(
            font, controls, layout.contentLeft(), layout.bottom() - 16,
            0xFFAAB7C4, false
        );
        if (promptPlayback.pageCount() > 1 && !promptReady) {
            String page = promptPlayback.pageNumber() + "/" + promptPlayback.pageCount();
            graphics.drawString(
                font, page, layout.right() - 12 - font.width(page), layout.bottom() - 16,
                0xFF78909F, false
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOptions(GuiGraphics graphics, int mouseX, int mouseY) {
        int visible = visibleRows();
        keepSelectedVisible(visible);
        for (int row = 0; row < visible; row++) {
            int optionIndex = firstVisible + row;
            if (optionIndex >= options.size()) break;
            int y = layout.listTop() + row * ROW_HEIGHT;
            boolean active = optionIndex == selected;
            boolean hovered = mouseX >= layout.contentLeft() && mouseX < layout.right() - 12
                && mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
            int background = active ? 0xFF335C81 : hovered ? 0xCC263747 : 0xAA1A2733;
            graphics.fill(
                layout.contentLeft(), y, layout.right() - 12,
                y + ROW_HEIGHT - 3, background
            );
            if (active) {
                graphics.fill(
                    layout.contentLeft(), y, layout.contentLeft() + 3,
                    y + ROW_HEIGHT - 3, 0xFFFFD166
                );
            }
            String prefix = active ? "▶ " : "  ";
            String label = font.plainSubstrByWidth(
                prefix + options.get(optionIndex), layout.contentWidth() - 18
            );
            graphics.drawString(
                font, Component.literal(label), layout.contentLeft() + 8,
                y + (ROW_HEIGHT - 3 - font.lineHeight) / 2,
                active ? 0xFFFFFFFF : 0xFFD7E0E8, false
            );
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
        if (mouseX < layout.contentLeft() || mouseX >= layout.right() - 12
            || mouseY < layout.listTop()) return true;
        int row = (int)(mouseY - layout.listTop()) / ROW_HEIGHT;
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
    @Override public boolean isPauseScreen() { return false; }

    private void advancePrompt() {
        DialoguePlayback.AdvanceResult result = promptPlayback.advance();
        if (result == DialoguePlayback.AdvanceResult.COMPLETED) promptReady = true;
    }

    private int visibleRows() {
        return Math.max(
            1,
            Math.min(options.size(), (layout.bottom() - layout.listTop() - 24) / ROW_HEIGHT)
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
        int boxHeight = Math.min(224, Math.max(156, height / 2));
        int top = Math.max(12, height - boxHeight - 14);
        int portraitWidth = width >= 320 ? Math.min(104, boxHeight - 16) : 0;
        int contentLeft = margin + 14 + portraitWidth;
        int contentWidth = Math.max(90, width - margin - 14 - contentLeft);
        int promptLines = Math.max(2, Math.min(4, (boxHeight - 92) / font.lineHeight));
        int listTop = top + 17 + promptLines * font.lineHeight;
        return new Layout(
            margin, top, width - margin, height - 14,
            portraitWidth, contentLeft, contentWidth, promptLines, listTop
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

    private record Layout(
        int left, int top, int right, int bottom,
        int portraitWidth, int contentLeft, int contentWidth,
        int promptLines, int listTop
    ) {}
}
