package dev.buizz.cobbleventure.adventure.event.client;

import dev.buizz.cobbleventure.adventure.event.EventDialogueNetwork;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Modal integer editor used by the CVES number_input await command. */
public final class EventNumberInputScreen extends Screen {
    private final EventDialogueNetwork.NumberInputOpenPayload payload;
    private final EventDialogueTheme theme;
    private EditBox input;
    private Layout layout;
    private boolean valid;
    private boolean replied;

    EventNumberInputScreen(EventDialogueNetwork.NumberInputOpenPayload payload) {
        super(Component.translatable("screen.cobbleventure_adventure.number_input.title"));
        this.payload = payload;
        this.theme = EventDialogueTheme.parse(payload.themeJson());
    }

    @Override
    protected void init() {
        layout = calculateLayout();
        input = new EditBox(font, layout.inputLeft() + 7, layout.inputTop() + 2,
            layout.inputRight() - layout.inputLeft() - 14,
            layout.inputBottom() - layout.inputTop() - 4,
            Component.translatable("screen.cobbleventure_adventure.number_input.field"));
        input.setFilter(value -> value.matches("-?\\d*"));
        input.setMaxLength(11);
        input.setResponder(ignored -> refresh());
        input.setBordered(false);
        input.setTextShadow(false);
        input.setTextColor(theme.textColor);
        input.setTextColorUneditable(theme.hintColor);
        addRenderableWidget(input);
        setInitialFocus(input);
        refresh();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (layout == null) return;
        EventPanelRenderer.dialogue(
            graphics, layout.left(), layout.top(), layout.right(), layout.bottom(), theme
        );
        drawScaled(graphics, theme.text(title), layout.left() + 14, layout.top() + 12,
            theme.speakerColor, theme.speakerScale);
        drawScaled(graphics, theme.text(Component.translatable(
            "screen.cobbleventure_adventure.number_input.range",
            payload.minimum(), payload.maximum()
        )), layout.left() + 14, layout.top() + 30, theme.hintColor, theme.hintScale);
        EventPanelRenderer.roundedFill(
            graphics, layout.inputLeft(), layout.inputTop(), layout.inputRight(),
            layout.inputBottom(), theme.menuRowRadius, theme.menuAccent
        );
        EventPanelRenderer.roundedFill(
            graphics, layout.inputLeft() + 2, layout.inputTop() + 2,
            layout.inputRight() - 2, layout.inputBottom() - 2,
            Math.max(0, theme.menuRowRadius - 2), theme.menuBackground
        );
        if (payload.hasPriceSummary()) renderPriceSummary(graphics);
        renderButton(graphics, layout.confirmLeft(), layout.buttonTop(),
            layout.confirmRight(), layout.buttonBottom(),
            Component.translatable("screen.cobbleventure_adventure.number_input.confirm"),
            valid, contains(mouseX, mouseY, layout.confirmLeft(), layout.buttonTop(),
                layout.confirmRight(), layout.buttonBottom()));
        renderButton(graphics, layout.cancelLeft(), layout.buttonTop(),
            layout.cancelRight(), layout.buttonBottom(),
            Component.translatable("screen.cobbleventure_adventure.number_input.cancel"),
            true, contains(mouseX, mouseY, layout.cancelLeft(), layout.buttonTop(),
                layout.cancelRight(), layout.buttonBottom()));
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { complete(0, true); }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && layout != null) {
            if (valid && contains(mouseX, mouseY, layout.confirmLeft(), layout.buttonTop(),
                layout.confirmRight(), layout.buttonBottom())) {
                submit();
                return true;
            }
            if (contains(mouseX, mouseY, layout.cancelLeft(), layout.buttonTop(),
                layout.cancelRight(), layout.buttonBottom())) {
                complete(0, true);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && validValue() != null) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void refresh() {
        valid = validValue() != null;
    }

    private void renderPriceSummary(GuiGraphics graphics) {
        Integer amount = parsedValue();
        long total = amount == null ? 0L : (long) amount * payload.unitPrice();
        long remaining = Math.max(0L, (long) payload.currentBalance() - total);
        int x = layout.inputLeft() + 2;
        int y = layout.inputBottom() + 7;
        int gap = Math.max(10, Math.round(font.lineHeight * theme.hintScale) + 2);
        if (layout.twoColumnSummary()) {
            int secondColumn = layout.inputLeft()
                + (layout.inputRight() - layout.inputLeft() + 12) / 2;
            drawScaled(graphics, theme.text(Component.translatable(
                "screen.cobbleventure_adventure.number_input.current",
                formatted(payload.currentBalance())
            )), x, y, theme.hintColor, theme.hintScale);
            drawScaled(graphics, theme.text(Component.translatable(
                "screen.cobbleventure_adventure.number_input.unit_price",
                formatted(payload.unitPrice())
            )), secondColumn, y, theme.hintColor, theme.hintScale);
            drawScaled(graphics, theme.text(Component.translatable(
                "screen.cobbleventure_adventure.number_input.total",
                formatted(total)
            )), x, y + gap, theme.textColor, theme.bodyScale);
            drawScaled(graphics, theme.text(Component.translatable(
                "screen.cobbleventure_adventure.number_input.remaining",
                formatted(remaining)
            )), secondColumn, y + gap, theme.textColor, theme.bodyScale);
            return;
        }
        drawScaled(graphics, theme.text(Component.translatable(
            "screen.cobbleventure_adventure.number_input.current",
            formatted(payload.currentBalance())
        )), x, y, theme.hintColor, theme.hintScale);
        drawScaled(graphics, theme.text(Component.translatable(
            "screen.cobbleventure_adventure.number_input.unit_price",
            formatted(payload.unitPrice())
        )), x, y + gap, theme.hintColor, theme.hintScale);
        drawScaled(graphics, theme.text(Component.translatable(
            "screen.cobbleventure_adventure.number_input.total",
            formatted(total)
        )), x, y + gap * 2, theme.textColor, theme.bodyScale);
        drawScaled(graphics, theme.text(Component.translatable(
            "screen.cobbleventure_adventure.number_input.remaining",
            formatted(remaining)
        )), x, y + gap * 3, theme.textColor, theme.bodyScale);
    }

    private static String formatted(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private void renderButton(
        GuiGraphics graphics, int left, int top, int right, int bottom,
        Component label, boolean enabled, boolean hovered
    ) {
        int background = !enabled ? theme.choiceBackground
            : hovered ? theme.choiceHoverBackground : theme.choiceSelectedBackground;
        EventPanelRenderer.roundedFill(
            graphics, left, top, right, bottom, theme.menuRowRadius, background
        );
        if (enabled) {
            EventPanelRenderer.roundedFill(
                graphics, left + 3, top + 3, left + 6, bottom - 3,
                1, theme.choiceSelectedAccent
            );
        }
        Component text = theme.text(label);
        float scale = theme.bodyScale;
        int textWidth = Math.round(font.width(text) * scale);
        int lineHeight = Math.max(1, Math.round(font.lineHeight * scale));
        drawScaled(graphics, text, left + (right - left - textWidth) / 2,
            top + (bottom - top - lineHeight) / 2,
            enabled ? theme.menuSelectedTextColor : theme.hintColor, scale);
    }

    private Layout calculateLayout() {
        int availableWidth = Math.max(1, width - 24);
        int contentWidth = Math.max(
            scaledWidth(theme.text(title), theme.speakerScale),
            scaledWidth(theme.text(Component.translatable(
                "screen.cobbleventure_adventure.number_input.range",
                payload.minimum(), payload.maximum()
            )), theme.hintScale)
        ) + 28;
        int desiredWidth = Math.max(360, Math.round(width * 0.56F));
        desiredWidth = Math.max(desiredWidth, contentWidth);
        int panelWidth = Math.min(availableWidth, Math.min(620, desiredWidth));
        boolean twoColumnSummary = payload.hasPriceSummary() && panelWidth >= 400;
        int summaryRows = payload.hasPriceSummary() ? (twoColumnSummary ? 2 : 4) : 0;
        int summaryGap = Math.max(10, Math.round(font.lineHeight * theme.hintScale) + 2);
        int buttonTopOffset = payload.hasPriceSummary()
            ? 74 + summaryRows * summaryGap + 7
            : 78;
        int panelHeight = buttonTopOffset + 36;
        panelHeight = Math.min(panelHeight, Math.max(1, height - 24));
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        int top = (height - panelHeight) / 2;
        int bottom = top + panelHeight;
        int inputLeft = left + 14;
        int inputRight = right - 14;
        int inputTop = top + 45;
        int inputBottom = inputTop + 22;
        int buttonTop = top + buttonTopOffset;
        int gap = 8;
        int half = (inputRight - inputLeft - gap) / 2;
        return new Layout(
            left, top, right, bottom,
            inputLeft, inputTop, inputRight, inputBottom,
            inputLeft, inputLeft + half,
            inputLeft + half + gap, inputRight,
            buttonTop, buttonTop + 24,
            twoColumnSummary
        );
    }

    private int scaledWidth(Component component, float scale) {
        return Math.round(font.width(component) * scale);
    }

    private boolean contains(
        double x, double y, int left, int top, int right, int bottom
    ) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    private void drawScaled(
        GuiGraphics graphics, Component component, int x, int y, int color, float scale
    ) {
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(
            font, component, Math.round(x / scale), Math.round(y / scale), color, false
        );
        graphics.pose().popPose();
    }

    private Integer validValue() {
        Integer value = parsedValue();
        if (value == null) return null;
        return value >= payload.minimum() && value <= payload.maximum() ? value : null;
    }

    private Integer parsedValue() {
        if (input == null || input.getValue().isBlank() || "-".equals(input.getValue())) return null;
        try {
            return Integer.parseInt(input.getValue());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void submit() {
        Integer value = validValue();
        if (value != null) complete(value, false);
    }

    private void complete(int value, boolean cancelled) {
        if (replied) return;
        replied = true;
        PacketDistributor.sendToServer(new EventDialogueNetwork.NumberInputCompletePayload(
            payload.token(), payload.npcId(), payload.scriptId(), payload.triggerInstance(),
            value, cancelled
        ));
        if (minecraft != null) minecraft.setScreen(null);
    }

    private record Layout(
        int left, int top, int right, int bottom,
        int inputLeft, int inputTop, int inputRight, int inputBottom,
        int confirmLeft, int confirmRight, int cancelLeft, int cancelRight,
        int buttonTop, int buttonBottom,
        boolean twoColumnSummary
    ) {}
}
