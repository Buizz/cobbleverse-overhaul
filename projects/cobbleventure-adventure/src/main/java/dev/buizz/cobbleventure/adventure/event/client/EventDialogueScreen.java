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

/** Non-pausing CVES dialogue with live NPC portrait, paging, and typewriter reveal. */
public final class EventDialogueScreen extends Screen {
    private static final long INPUT_GUARD_MILLIS = 120L;
    private final EventDialogueNetwork.OpenPayload payload;
    private final String dialogue;
    private final long openedAt = System.currentTimeMillis();
    private DialoguePlayback playback;
    private Layout layout;
    private boolean replied;

    EventDialogueScreen(EventDialogueNetwork.OpenPayload payload, String dialogue) {
        super(Component.translatable("screen.cobbleventure_adventure.dialogue.title"));
        this.payload = payload;
        this.dialogue = dialogue;
    }

    @Override
    protected void init() {
        super.init();
        layout = calculateLayout();
        List<String> pages = DialoguePaginator.paginate(
            dialogue,
            layout.maximumLines(),
            value -> font.split(Component.literal(value), layout.textWidth()).size()
        );
        playback = new DialoguePlayback(pages);
    }

    @Override
    public void tick() {
        super.tick();
        if (playback != null) playback.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (layout == null || playback == null) return;
        graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), 0xE8101720);
        graphics.fill(layout.left(), layout.top(), layout.right(), layout.top() + 2, 0xFFE8EDF2);

        LivingEntity npc = portraitEntity();
        if (layout.portraitWidth() > 0) {
            EventDialoguePortrait.render(
                graphics, npc,
                layout.left() + 8, layout.top() + 8,
                layout.textLeft() - 8, layout.bottom() - 8,
                mouseX, mouseY
            );
        }

        int textY = layout.top() + 13;
        if (!payload.narration() && !payload.speaker().isBlank()) {
            graphics.drawString(font, payload.speaker(), layout.textLeft(), textY,
                0xFFFFD166, false);
            textY += 16;
        }
        List<FormattedCharSequence> lines = font.split(
            Component.literal(playback.visibleText()), layout.textWidth()
        );
        for (int index = 0; index < Math.min(lines.size(), layout.maximumLines()); index++) {
            graphics.drawString(
                font, lines.get(index), layout.textLeft(),
                textY + index * font.lineHeight, 0xFFFFFFFF, false
            );
        }

        Component hint = Component.translatable(hintKey());
        graphics.drawString(
            font, hint,
            layout.right() - 12 - font.width(hint), layout.bottom() - 17,
            0xFFAAB7C4, false
        );
        if (playback.pageCount() > 1) {
            String page = playback.pageNumber() + "/" + playback.pageCount();
            graphics.drawString(
                font, page, layout.textLeft(), layout.bottom() - 17,
                0xFF78909F, false
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible behind the dialogue.
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && inputReady()) {
            advance();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE)
            && inputReady()) {
            advance();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        complete(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void advance() {
        if (playback.advance() == DialoguePlayback.AdvanceResult.COMPLETED) {
            complete(false);
        }
    }

    private String hintKey() {
        if (!playback.pageRevealed()) {
            return "screen.cobbleventure_adventure.dialogue.reveal";
        }
        return playback.lastPage()
            ? "screen.cobbleventure_adventure.dialogue.continue"
            : "screen.cobbleventure_adventure.dialogue.next_page";
    }

    private boolean inputReady() {
        return !replied && System.currentTimeMillis() - openedAt >= INPUT_GUARD_MILLIS;
    }

    private Layout calculateLayout() {
        int margin = Math.max(12, width / 18);
        int boxHeight = Math.min(166, Math.max(112, height / 3));
        int top = height - boxHeight - 14;
        boolean showPortrait = (payload.speakerKind().equals("npc")
            || payload.speakerKind().equals("player")) && width >= 300;
        int portraitWidth = showPortrait ? Math.min(104, boxHeight - 16) : 0;
        int textLeft = margin + 14 + portraitWidth;
        int textWidth = Math.max(70, width - margin - 14 - textLeft);
        int header = !payload.narration() && !payload.speaker().isBlank() ? 29 : 13;
        int maximumLines = Math.max(2, (boxHeight - header - 30) / font.lineHeight);
        return new Layout(
            margin, top, width - margin, height - 14,
            portraitWidth, textLeft, textWidth, maximumLines
        );
    }

    private void complete(boolean cancelled) {
        if (replied) return;
        replied = true;
        PacketDistributor.sendToServer(new EventDialogueNetwork.CompletePayload(
            payload.token(), payload.npcId(), payload.scriptId(),
            payload.triggerInstance(), cancelled
        ));
        if (minecraft != null) minecraft.setScreen(null);
    }

    private LivingEntity portraitEntity() {
        if (payload.speakerKind().equals("player")) {
            return minecraft == null ? null : minecraft.player;
        }
        return payload.speakerKind().equals("npc")
            ? EventDialoguePortrait.find(minecraft, payload.npcId())
            : null;
    }

    private record Layout(
        int left, int top, int right, int bottom,
        int portraitWidth, int textLeft, int textWidth, int maximumLines
    ) {}
}
