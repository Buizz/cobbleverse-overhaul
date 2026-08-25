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
    private int selectedAmount;
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
        if (payload.hasPriceSummary()) {
            selectedAmount = payload.minimum();
            valid = true;
            return;
        }
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
        if (payload.hasPriceSummary()) renderQuantityPicker(graphics, mouseX, mouseY);
        else renderInputFrame(graphics);
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
            if (payload.hasPriceSummary() && handlePickerClick(mouseX, mouseY)) return true;
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
        if (payload.hasPriceSummary()) {
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                adjustAmount(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                adjustAmount(1);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && validValue() != null) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void refresh() {
        valid = validValue() != null;
    }

    private void renderInputFrame(GuiGraphics graphics) {
        EventPanelRenderer.roundedFill(
            graphics, layout.inputLeft(), layout.inputTop(), layout.inputRight(),
            layout.inputBottom(), theme.menuRowRadius, theme.menuAccent
        );
        EventPanelRenderer.roundedFill(
            graphics, layout.inputLeft() + 2, layout.inputTop() + 2,
            layout.inputRight() - 2, layout.inputBottom() - 2,
            Math.max(0, theme.menuRowRadius - 2), theme.menuBackground
        );
    }

    private void renderQuantityPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        PickerLayout picker = pickerLayout();
        renderStepButton(graphics, picker.minusHundredLeft(), picker.minusHundredRight(), "-100",
            selectedAmount > payload.minimum(), mouseX, mouseY);
        renderStepButton(graphics, picker.minusTenLeft(), picker.minusTenRight(), "-10",
            selectedAmount > payload.minimum(), mouseX, mouseY);
        renderStepButton(graphics, picker.minusOneLeft(), picker.minusOneRight(), "-1",
            selectedAmount > payload.minimum(), mouseX, mouseY);

        EventPanelRenderer.roundedFill(
            graphics, picker.valueLeft(), layout.inputTop(), picker.valueRight(),
            layout.inputBottom(), theme.menuRowRadius, theme.menuAccent
        );
        EventPanelRenderer.roundedFill(
            graphics, picker.valueLeft() + 2, layout.inputTop() + 2,
            picker.valueRight() - 2, layout.inputBottom() - 2,
            Math.max(0, theme.menuRowRadius - 2), theme.menuBackground
        );
        String amount = formatted(selectedAmount);
        graphics.drawCenteredString(font, amount,
            (picker.valueLeft() + picker.valueRight()) / 2,
            layout.inputTop() + (layout.inputBottom() - layout.inputTop() - font.lineHeight) / 2,
            theme.textColor);

        renderStepButton(graphics, picker.plusOneLeft(), picker.plusOneRight(), "+1",
            selectedAmount < payload.maximum(), mouseX, mouseY);
        renderStepButton(graphics, picker.plusTenLeft(), picker.plusTenRight(), "+10",
            selectedAmount < payload.maximum(), mouseX, mouseY);
        renderStepButton(graphics, picker.plusHundredLeft(), picker.plusHundredRight(), "+100",
            selectedAmount < payload.maximum(), mouseX, mouseY);
    }

    private void renderStepButton(
        GuiGraphics graphics, int left, int right, String label,
        boolean enabled, int mouseX, int mouseY
    ) {
        boolean hovered = enabled && contains(
            mouseX, mouseY, left, layout.inputTop(), right, layout.inputBottom()
        );
        int background = !enabled ? theme.choiceBackground
            : hovered ? theme.choiceHoverBackground : theme.choiceSelectedBackground;
        EventPanelRenderer.roundedFill(
            graphics, left, layout.inputTop(), right, layout.inputBottom(),
            theme.menuRowRadius, background
        );
        graphics.drawCenteredString(font, label, (left + right) / 2,
            layout.inputTop() + (layout.inputBottom() - layout.inputTop() - font.lineHeight) / 2,
            enabled ? theme.menuSelectedTextColor : theme.hintColor);
    }

    private boolean handlePickerClick(double mouseX, double mouseY) {
        PickerLayout picker = pickerLayout();
        if (contains(mouseX, mouseY, picker.minusHundredLeft(), layout.inputTop(),
            picker.minusHundredRight(), layout.inputBottom())) {
            adjustAmount(-100);
            return true;
        }
        if (contains(mouseX, mouseY, picker.minusTenLeft(), layout.inputTop(),
            picker.minusTenRight(), layout.inputBottom())) {
            adjustAmount(-10);
            return true;
        }
        if (contains(mouseX, mouseY, picker.minusOneLeft(), layout.inputTop(),
            picker.minusOneRight(), layout.inputBottom())) {
            adjustAmount(-1);
            return true;
        }
        if (contains(mouseX, mouseY, picker.plusOneLeft(), layout.inputTop(),
            picker.plusOneRight(), layout.inputBottom())) {
            adjustAmount(1);
            return true;
        }
        if (contains(mouseX, mouseY, picker.plusTenLeft(), layout.inputTop(),
            picker.plusTenRight(), layout.inputBottom())) {
            adjustAmount(10);
            return true;
        }
        if (contains(mouseX, mouseY, picker.plusHundredLeft(), layout.inputTop(),
            picker.plusHundredRight(), layout.inputBottom())) {
            adjustAmount(100);
            return true;
        }
        return false;
    }

    private void adjustAmount(int delta) {
        long adjusted = (long) selectedAmount + delta;
        selectedAmount = (int) Math.max(payload.minimum(), Math.min(payload.maximum(), adjusted));
        valid = true;
    }

    private PickerLayout pickerLayout() {
        int availableWidth = layout.inputRight() - layout.inputLeft();
        int gap = availableWidth >= 300 ? 4 : 2;
        int valueWidth = Math.min(76, Math.max(56, availableWidth / 4));
        int buttonWidth = Math.max(24, (availableWidth - valueWidth - gap * 6) / 6);
        int totalWidth = buttonWidth * 6 + valueWidth + gap * 6;
        int left = layout.inputLeft() + (layout.inputRight() - layout.inputLeft() - totalWidth) / 2;
        int minusHundredLeft = left;
        int minusTenLeft = minusHundredLeft + buttonWidth + gap;
        int minusOneLeft = minusTenLeft + buttonWidth + gap;
        int valueLeft = minusOneLeft + buttonWidth + gap;
        int plusOneLeft = valueLeft + valueWidth + gap;
        int plusTenLeft = plusOneLeft + buttonWidth + gap;
        int plusHundredLeft = plusTenLeft + buttonWidth + gap;
        return new PickerLayout(
            minusHundredLeft, minusHundredLeft + buttonWidth,
            minusTenLeft, minusTenLeft + buttonWidth,
            minusOneLeft, minusOneLeft + buttonWidth,
            valueLeft, valueLeft + valueWidth,
            plusOneLeft, plusOneLeft + buttonWidth,
            plusTenLeft, plusTenLeft + buttonWidth,
            plusHundredLeft, plusHundredLeft + buttonWidth
        );
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
        if (payload.hasPriceSummary()) return selectedAmount;
        Integer value = parsedValue();
        if (value == null) return null;
        return value >= payload.minimum() && value <= payload.maximum() ? value : null;
    }

    private Integer parsedValue() {
        if (payload.hasPriceSummary()) return selectedAmount;
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

    private record PickerLayout(
        int minusHundredLeft, int minusHundredRight,
        int minusTenLeft, int minusTenRight,
        int minusOneLeft, int minusOneRight,
        int valueLeft, int valueRight,
        int plusOneLeft, int plusOneRight,
        int plusTenLeft, int plusTenRight,
        int plusHundredLeft, int plusHundredRight
    ) {}
}
