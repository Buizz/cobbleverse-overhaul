package dev.buizz.cobbleventure.adventure.daycare.client;

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.buizz.cobbleventure.adventure.daycare.DaycareNetwork;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Daycare screen with a deposited row, a PC-like party grid, and a detail pane. */
final class DaycareScreen extends Screen {
    private static final int PARTY_SLOTS = 6;
    private static final int DAYCARE_SLOTS = 6;

    private DaycareNetwork.ViewPayload payload;
    private final List<PokemonCard> partyCards = new ArrayList<>();
    private final List<PokemonCard> daycareCards = new ArrayList<>();
    private final List<ModelWidget> models = new ArrayList<>();
    private DaycareMenuTheme theme;
    private DaycareScreenLayout layout;
    private ActionButton depositButton;
    private ActionButton withdrawButton;
    private ActionButton trainingButton;
    private ActionButton collectButton;
    private ActionButton closeButton;
    private ActionButton confirmWithdrawButton;
    private ActionButton cancelWithdrawButton;
    private int selectedPartySlot = -1;
    private int selectedStoredSlot = -1;
    private int confirmingStoredSlot = -1;
    private int pendingConfirmationSlot = -1;
    private long payloadReceivedAtMillis = System.currentTimeMillis();
    private boolean requestPending;
    private boolean trainingEnabled;

    DaycareScreen(DaycareNetwork.ViewPayload payload) {
        super(Component.translatable("screen.cobbleventure_adventure.daycare.title"));
        this.payload = payload;
    }

    boolean apply(DaycareNetwork.ViewPayload next) {
        payload = next;
        payloadReceivedAtMillis = System.currentTimeMillis();
        requestPending = false;
        selectedPartySlot = validPartySelection(selectedPartySlot) ? selectedPartySlot : -1;
        selectedStoredSlot = selectedStoredSlot < next.storedPokemon().size()
            ? selectedStoredSlot : -1;
        confirmingStoredSlot = confirmingStoredSlot < next.storedPokemon().size()
            ? confirmingStoredSlot : -1;
        if (pendingConfirmationSlot >= 0) {
            confirmingStoredSlot = pendingConfirmationSlot < next.storedPokemon().size()
                ? pendingConfirmationSlot : -1;
            pendingConfirmationSlot = -1;
        }
        rebuildDaycareWidgets();
        return true;
    }

    @Override
    protected void init() {
        theme = DaycareMenuTheme.load(minecraft);
        layout = DaycareScreenLayout.calculate(width, height);
        rebuildDaycareWidgets();
    }

    private void rebuildDaycareWidgets() {
        clearWidgets();
        partyCards.clear();
        daycareCards.clear();
        models.clear();
        confirmWithdrawButton = null;
        cancelWithdrawButton = null;

        for (int slot = 0; slot < DAYCARE_SLOTS; slot++) {
            DaycareNetwork.PokemonView view = slot < payload.storedPokemon().size()
                ? payload.storedPokemon().get(slot) : DaycareNetwork.PokemonView.empty();
            int x = layout.storedX() + slot * (layout.storedCardWidth() + layout.storedGap());
            PokemonCard card = addRenderableWidget(new PokemonCard(
                view, slot, true, x, layout.storedY(),
                layout.storedCardWidth(), layout.storedCardHeight()
            ));
            daycareCards.add(card);
            int modelSize = Math.min(52, Math.min(
                layout.storedCardWidth() - 8, layout.storedCardHeight() - 24
            ));
            addModel(view, x + (layout.storedCardWidth() - modelSize) / 2,
                layout.storedY() + 3, modelSize);
        }

        for (int slot = 0; slot < PARTY_SLOTS; slot++) {
            DaycareNetwork.PokemonView view = payload.partySlots().get(slot);
            int column = slot % 2;
            int row = slot / 2;
            int x = layout.partyGridX()
                + column * (layout.partyCardWidth() + layout.partyGap());
            int y = layout.partyGridY()
                + row * (layout.partyCardHeight() + layout.partyGap());
            PokemonCard card = addRenderableWidget(new PokemonCard(
                view, slot, false, x, y,
                layout.partyCardWidth(), layout.partyCardHeight()
            ));
            partyCards.add(card);
            int modelSize = Math.min(32, layout.partyCardHeight() - 4);
            addModel(view, x + 3, y + 2, modelSize);
        }

        DaycareNetwork.PokemonView selected = selectedPokemon();
        if (selected != null) {
            addModel(selected, layout.detailModelX(), layout.detailModelY(),
                layout.detailModelSize());
        }

        int detailRight = layout.detailPanelX() + layout.detailPanelWidth() - layout.padding();
        int buttonX = layout.detailInfoX();
        if (detailRight - buttonX < 128) {
            buttonX = layout.detailPanelX() + layout.padding();
        }
        int actionGap = Math.max(4, layout.gap() / 2);
        int actionWidth = Math.max(48, (detailRight - buttonX - actionGap) / 2);
        trainingButton = addRenderableWidget(new ActionButton(
            layout.detailInfoX(), layout.detailInfoY() + 32,
            Math.max(64, Math.min(112, detailRight - layout.detailInfoX())),
            Math.max(18, layout.actionHeight() - 4), this::toggleTraining
        ));
        depositButton = addRenderableWidget(new ActionButton(
            buttonX, layout.actionY(), actionWidth, layout.actionHeight(),
            () -> send(DaycareNetwork.Action.DEPOSIT, selectedPartySlot)
        ));
        withdrawButton = addRenderableWidget(new ActionButton(
            buttonX + actionWidth + actionGap, layout.actionY(), actionWidth,
            layout.actionHeight(),
            this::openWithdrawConfirmation
        ));
        collectButton = addRenderableWidget(new ActionButton(
            layout.eggX(), layout.eggY(), layout.eggWidth(), layout.eggHeight(),
            () -> send(DaycareNetwork.Action.COLLECT, -1)
        ));
        closeButton = addRenderableWidget(new ActionButton(
            layout.closeX(), layout.closeY(), layout.closeWidth(), layout.closeHeight(),
            this::onClose
        ));
        closeButton.setMessage(Component.translatable("gui.done"));
        if (validConfirmation()) {
            addRenderableWidget(new ModalBackdrop());
            addConfirmationModel(payload.storedPokemon().get(confirmingStoredSlot));
            int modalWidth = confirmationWidth();
            int modalX = confirmationX();
            int buttonWidth = Math.max(80, (modalWidth - 42) / 2);
            int buttonY = confirmationY() + confirmationHeight() - 34;
            confirmWithdrawButton = addRenderableWidget(new ActionButton(
                modalX + 14, buttonY, buttonWidth, 22, this::confirmWithdraw
            ));
            confirmWithdrawButton.setMessage(Component.translatable(
                "screen.cobbleventure_adventure.daycare.withdraw_confirm_action"
            ));
            cancelWithdrawButton = addRenderableWidget(new ActionButton(
                modalX + modalWidth - 14 - buttonWidth, buttonY, buttonWidth, 22,
                this::cancelWithdrawConfirmation
            ));
            cancelWithdrawButton.setMessage(Component.translatable("gui.cancel"));
        }
        refreshButtons();
    }

    private void addModel(DaycareNetwork.PokemonView view, int x, int y, int size) {
        if (validConfirmation() || view.emptySlot() || size < 16
            || minecraft == null || minecraft.level == null) {
            return;
        }
        addModelWidget(view, x, y, size);
    }

    private void addConfirmationModel(DaycareNetwork.PokemonView view) {
        addModelWidget(
            view, confirmationModelX(), confirmationModelY(), confirmationModelSize()
        );
    }

    private void addModelWidget(
        DaycareNetwork.PokemonView view, int x, int y, int size
    ) {
        if (view.emptySlot() || size < 16 || minecraft == null || minecraft.level == null) {
            return;
        }
        try {
            Pokemon pokemon = new Pokemon().loadFromNBT(
                minecraft.level.registryAccess(), view.data().copy()
            );
            ModelWidget model = new ModelWidget(
                x, y, size, size, pokemon.asRenderablePokemon(),
                Math.max(.65F, size / 42F), 25F, 0D, false, false
            );
            model.active = false;
            models.add(addRenderableWidget(model));
        } catch (RuntimeException ignored) {
            // The text card remains usable if a resource pack removes a rendered form.
        }
    }

    private DaycareNetwork.PokemonView selectedPokemon() {
        if (validPartySelection(selectedPartySlot)) {
            return payload.partySlots().get(selectedPartySlot);
        }
        if (selectedStoredSlot >= 0 && selectedStoredSlot < payload.storedPokemon().size()) {
            return payload.storedPokemon().get(selectedStoredSlot);
        }
        return null;
    }

    private boolean validPartySelection(int slot) {
        return slot >= 0 && slot < payload.partySlots().size()
            && !payload.partySlots().get(slot).emptySlot();
    }

    private void toggleTraining() {
        if (!validPartySelection(selectedPartySlot)) return;
        trainingEnabled = !trainingEnabled;
        refreshButtons();
    }

    private void select(int slot, boolean stored) {
        if (requestPending) return;
        if (stored) {
            if (slot >= payload.storedPokemon().size()) return;
            selectedStoredSlot = selectedStoredSlot == slot ? -1 : slot;
            selectedPartySlot = -1;
        } else {
            if (payload.partySlots().get(slot).emptySlot()) return;
            selectedPartySlot = selectedPartySlot == slot ? -1 : slot;
            selectedStoredSlot = -1;
        }
        rebuildDaycareWidgets();
    }

    private void openWithdrawConfirmation() {
        if (selectedStoredSlot < 0 || requestPending) return;
        pendingConfirmationSlot = selectedStoredSlot;
        send(DaycareNetwork.Action.REFRESH, -1);
    }

    private void cancelWithdrawConfirmation() {
        confirmingStoredSlot = -1;
        rebuildDaycareWidgets();
    }

    private void confirmWithdraw() {
        if (!validConfirmation()) return;
        int slot = confirmingStoredSlot;
        confirmingStoredSlot = -1;
        rebuildDaycareWidgets();
        send(DaycareNetwork.Action.WITHDRAW, slot);
    }

    private boolean validConfirmation() {
        return confirmingStoredSlot >= 0
            && confirmingStoredSlot < payload.storedPokemon().size();
    }

    private void refreshButtons() {
        partyCards.forEach(PokemonCard::refreshState);
        daycareCards.forEach(PokemonCard::refreshState);
        if (depositButton == null) return;

        DaycareNetwork.PokemonView selected = selectedPokemon();
        boolean shownTraining = selectedStoredSlot >= 0 && selected != null
            ? selected.training() : trainingEnabled;
        trainingButton.setMessage(Component.translatable(shownTraining
            ? "screen.cobbleventure_adventure.daycare.training_short_on"
            : "screen.cobbleventure_adventure.daycare.training_short_off"));
        trainingButton.setTooltip(Tooltip.create(Component.translatable(
            "screen.cobbleventure_adventure.daycare.training_tooltip",
            format(payload.trainingCostPerExperience()),
            format(payload.maxTrainingExperience())
        )));
        depositButton.setMessage(Component.translatable(
            "screen.cobbleventure_adventure.daycare.deposit_action"
        ));
        withdrawButton.setMessage(Component.translatable(
            "screen.cobbleventure_adventure.daycare.withdraw_short"
        ));
        collectButton.setMessage(Component.translatable(
            "screen.cobbleventure_adventure.daycare.egg_count", payload.eggCount()
        ));

        boolean modalOpen = validConfirmation();
        trainingButton.active = validPartySelection(selectedPartySlot)
            && !requestPending && !modalOpen;
        depositButton.active = validPartySelection(selectedPartySlot)
            && payload.storedPokemon().size() < DAYCARE_SLOTS
            && !requestPending && !modalOpen;
        withdrawButton.active = selectedStoredSlot >= 0 && !requestPending && !modalOpen;
        collectButton.active = payload.eggCount() > 0 && !requestPending && !modalOpen;
        closeButton.active = !modalOpen;
        if (confirmWithdrawButton != null) confirmWithdrawButton.active = !requestPending;
        if (cancelWithdrawButton != null) cancelWithdrawButton.active = !requestPending;
    }

    private void send(DaycareNetwork.Action action, int slot) {
        requestPending = true;
        refreshButtons();
        PacketDistributor.sendToServer(new DaycareNetwork.ActionPayload(
            payload.npcId(), action, slot,
            action == DaycareNetwork.Action.DEPOSIT && trainingEnabled
        ));
    }

    @Override
    public void onClose() {
        if (validConfirmation()) {
            cancelWithdrawConfirmation();
            return;
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x66071924);
        DaycareThemedPanel.draw(
            graphics, theme, layout.panelX(), layout.panelY(),
            layout.panelWidth(), layout.panelHeight(), theme.accent
        );
        drawHeader(graphics);
        drawSectionPanel(graphics, layout.partyPanelX(), layout.contentY(),
            layout.partyPanelWidth(), layout.contentHeight());
        drawSectionPanel(graphics, layout.detailPanelX(), layout.contentY(),
            layout.detailPanelWidth(), layout.contentHeight());

        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_adventure.daycare.stored",
                payload.storedPokemon().size(), DAYCARE_SLOTS),
            layout.storedX(), layout.storedLabelY(), theme.textColor, false);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_adventure.daycare.party"),
            layout.partyPanelX() + layout.padding(), layout.contentY() + 7,
            theme.textColor, false);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_adventure.daycare.selected"),
            layout.detailPanelX() + layout.padding(), layout.contentY() + 7,
            theme.textColor, false);
        drawSelectedDetails(graphics);
        drawFooter(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphics graphics) {
        int iconX = layout.panelX() + layout.padding();
        int iconY = layout.panelY() + Math.max(6, (layout.headerHeight() - 18) / 2);
        drawDaycareIcon(graphics, iconX, iconY);
        int titleX = iconX + 24;
        int titleY = layout.panelY() + Math.max(7, (layout.headerHeight() - 9) / 2);
        graphics.drawString(font, title, titleX, titleY, theme.selectedTextColor, false);
        graphics.fill(layout.panelX() + 1, layout.panelY() + layout.headerHeight() - 1,
            layout.panelX() + layout.panelWidth() - 1,
            layout.panelY() + layout.headerHeight(), theme.border);
    }

    private void drawDaycareIcon(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 2, y + 7, x + 17, y + 17, 0xFFF3E3BE);
        graphics.fill(x + 7, y + 11, x + 11, y + 17, 0xFF9D7448);
        graphics.fill(x + 8, y, x + 12, y + 2, theme.accent);
        graphics.fill(x + 6, y + 2, x + 14, y + 4, theme.accent);
        graphics.fill(x + 4, y + 4, x + 16, y + 6, theme.accent);
        graphics.fill(x + 2, y + 6, x + 18, y + 8, theme.accent);
        graphics.fill(x + 3, y + 7, x + 17, y + 9, 0xFF4F8FC7);
    }

    private void drawSectionPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        DaycareThemedPanel.roundedFill(graphics, x, y, x + width, y + height,
            theme.rowRadius, theme.border);
        DaycareThemedPanel.roundedFill(graphics, x + 1, y + 1,
            x + width - 1, y + height - 1, Math.max(0, theme.rowRadius - 1),
            DaycareThemedPanel.withOpacity(theme.background, .82F));
    }

    private void drawSelectedDetails(GuiGraphics graphics) {
        DaycareNetwork.PokemonView selected = selectedPokemon();
        if (selected == null) {
            Component prompt = Component.translatable(
                "screen.cobbleventure_adventure.daycare.select_prompt"
            );
            int available = layout.detailPanelWidth() - layout.padding() * 2;
            String text = font.plainSubstrByWidth(prompt.getString(), available);
            drawCenteredNoShadow(graphics, text,
                layout.detailPanelX() + layout.detailPanelWidth() / 2,
                layout.detailInfoY() + 18, theme.mutedText());
            return;
        }

        int infoRight = layout.detailPanelX() + layout.detailPanelWidth() - layout.padding();
        int infoWidth = Math.max(36, infoRight - layout.detailInfoX());
        graphics.drawString(font,
            font.plainSubstrByWidth(selected.name(), infoWidth),
            layout.detailInfoX(), layout.detailInfoY(), theme.selectedTextColor, false);
        graphics.drawString(font, "Lv." + selected.level(),
            layout.detailInfoX(), layout.detailInfoY() + 13, theme.mutedText(), false);
        graphics.fill(layout.detailInfoX(), layout.detailInfoY() + 25,
            infoRight, layout.detailInfoY() + 26, theme.border);

        if (selectedPartySlot >= 0) {
            String fee = Component.translatable(
                "screen.cobbleventure_adventure.daycare.deposit_fee", format(payload.fee())
            ).getString();
            graphics.drawString(font, font.plainSubstrByWidth(fee, infoWidth),
                layout.detailInfoX(), layout.detailInfoY() + 57,
                theme.mutedText(), false);
        } else if (selected.training()) {
            graphics.drawString(font,
                Component.translatable("screen.cobbleventure_adventure.daycare.training_badge"),
                layout.detailInfoX(), layout.detailInfoY() + 57, theme.accent, false);
            graphics.drawString(font,
                Component.translatable(
                    "screen.cobbleventure_adventure.daycare.training_progress",
                    format(visibleTrainingExperience(selected))
                ),
                layout.detailInfoX(), layout.detailInfoY() + 69,
                theme.mutedText(), false);
        }
    }

    private void drawFooter(GuiGraphics graphics) {
        Component breeding = payload.storedPokemon().size() < 2
            ? Component.translatable("screen.cobbleventure_adventure.daycare.need_more")
            : payload.compatiblePair()
                ? Component.translatable(
                    "screen.cobbleventure_adventure.daycare.discovery_check",
                    payload.remainingMinutes()
                )
                : Component.translatable("screen.cobbleventure_adventure.daycare.no_pair");
        int available = layout.closeX() - layout.panelX() - layout.padding() * 2;
        graphics.drawString(font,
            font.plainSubstrByWidth(breeding.getString(), Math.max(40, available)),
            layout.panelX() + layout.padding(), layout.statusY(),
            payload.compatiblePair() ? theme.accent : theme.mutedText(), false);
        if (!payload.feedback().getString().isBlank()) {
            graphics.drawString(font,
                font.plainSubstrByWidth(payload.feedback().getString(), Math.max(40, available)),
                layout.panelX() + layout.padding(), layout.statusY() + 11,
                0xFFE45C5C, false);
        }
    }

    private int confirmationWidth() {
        return layout.panelWidth();
    }

    private int confirmationHeight() {
        return layout.panelHeight();
    }

    private int confirmationX() {
        return layout.panelX();
    }

    private int confirmationY() {
        return layout.panelY();
    }

    private int confirmationContentY() {
        return confirmationY() + 53;
    }

    private int confirmationContentHeight() {
        return confirmationHeight() - 96;
    }

    private int confirmationLeftWidth() {
        return Math.clamp(Math.round(confirmationWidth() * .34F), 104, 238);
    }

    private int confirmationRightX() {
        return confirmationX() + 14 + confirmationLeftWidth() + layout.gap();
    }

    private int confirmationRightWidth() {
        return confirmationX() + confirmationWidth() - 14 - confirmationRightX();
    }

    private int confirmationModelSize() {
        return Math.clamp(Math.min(
            confirmationLeftWidth() - 20, confirmationContentHeight() - 54
        ), 42, 140);
    }

    private int confirmationModelX() {
        return confirmationX() + 14
            + (confirmationLeftWidth() - confirmationModelSize()) / 2;
    }

    private int confirmationModelY() {
        return confirmationContentY() + 24;
    }

    private void drawWithdrawConfirmation(GuiGraphics graphics) {
        if (!validConfirmation()) return;
        DaycareNetwork.PokemonView pokemon = payload.storedPokemon().get(confirmingStoredSlot);
        int visibleExperience = visibleTrainingExperience(pokemon);
        BigInteger cost = BigInteger.valueOf(payload.trainingCostPerExperience())
            .multiply(BigInteger.valueOf(visibleExperience));
        BigInteger balance = parseBalance(payload.balance());
        BigInteger remaining = balance.subtract(cost);
        int modalWidth = confirmationWidth();
        int modalHeight = confirmationHeight();
        int modalX = confirmationX();
        int modalY = confirmationY();

        graphics.fill(0, 0, width, height, 0x99030B12);
        DaycareThemedPanel.draw(
            graphics, theme, modalX, modalY, modalWidth, modalHeight, theme.accent
        );
        graphics.drawString(font,
            Component.translatable(
                "screen.cobbleventure_adventure.daycare.withdraw_confirm_title"
            ), modalX + 14, modalY + 13, theme.selectedTextColor, false);
        graphics.drawString(font,
            font.plainSubstrByWidth(Component.translatable(
                "screen.cobbleventure_adventure.daycare.withdraw_confirm_question",
                pokemon.name()
            ).getString(), modalWidth - 28),
            modalX + 14, modalY + 30, theme.textColor, false);
        graphics.fill(modalX + 14, modalY + 45, modalX + modalWidth - 14,
            modalY + 46, theme.border);

        int contentY = confirmationContentY();
        int contentHeight = confirmationContentHeight();
        int leftX = modalX + 14;
        int leftWidth = confirmationLeftWidth();
        int rightX = confirmationRightX();
        int rightWidth = confirmationRightWidth();
        drawSectionPanel(graphics, leftX, contentY, leftWidth, contentHeight);
        drawSectionPanel(graphics, rightX, contentY, rightWidth, contentHeight);
        graphics.drawString(font,
            Component.translatable(
                "screen.cobbleventure_adventure.daycare.withdraw_pokemon"
            ), leftX + 10, contentY + 9, theme.textColor, false);
        graphics.drawString(font,
            Component.translatable(
                "screen.cobbleventure_adventure.daycare.withdraw_summary"
            ), rightX + 12, contentY + 9, theme.textColor, false);
        graphics.fill(rightX + 12, contentY + 24, rightX + rightWidth - 12,
            contentY + 25, theme.border);

        drawCenteredNoShadow(graphics,
            font.plainSubstrByWidth(pokemon.name(), leftWidth - 18),
            leftX + leftWidth / 2, contentY + contentHeight - 27,
            theme.selectedTextColor);
        drawCenteredNoShadow(graphics, "Lv." + pokemon.level(),
            leftX + leftWidth / 2, contentY + contentHeight - 15,
            theme.mutedText());

        int rowY = contentY + 34;
        drawSettlementRow(graphics, rightX, rightWidth, rowY,
            "screen.cobbleventure_adventure.daycare.withdraw_level",
            pokemon.originalLevel() + " → " + pokemon.level(), theme.textColor);
        drawSettlementRow(graphics, rightX, rightWidth, rowY + 19,
            "screen.cobbleventure_adventure.daycare.withdraw_experience",
            "+" + format(visibleExperience) + " XP", theme.accent);
        drawSettlementRow(graphics, rightX, rightWidth, rowY + 38,
            "screen.cobbleventure_adventure.daycare.withdraw_cost",
            money(cost), cost.signum() > 0 ? 0xFFFF8A65 : theme.mutedText());
        drawSettlementRow(graphics, rightX, rightWidth, rowY + 57,
            "screen.cobbleventure_adventure.daycare.withdraw_balance",
            money(balance), theme.textColor);
        drawSettlementRow(graphics, rightX, rightWidth, rowY + 76,
            "screen.cobbleventure_adventure.daycare.withdraw_balance_after",
            money(remaining), remaining.signum() < 0 ? 0xFFE45C5C : theme.selectedTextColor);
        if (remaining.signum() < 0) {
            graphics.drawString(font,
                Component.translatable(
                    "screen.cobbleventure_adventure.daycare.withdraw_negative_notice"
                ), rightX + 12, rowY + 95, 0xFFE45C5C, false);
        }
    }

    private void drawSettlementRow(
        GuiGraphics graphics, int modalX, int modalWidth, int y,
        String labelKey, String value, int valueColor
    ) {
        Component label = Component.translatable(labelKey);
        graphics.drawString(font, label, modalX + 12, y, theme.mutedText(), false);
        int valueY = font.width(label) + font.width(value) + 32 > modalWidth
            ? y + 9 : y;
        graphics.drawString(font, value,
            modalX + modalWidth - 12 - font.width(value), valueY, valueColor, false);
    }

    private String money(BigInteger amount) {
        return Component.translatable(
            "screen.cobbleventure_adventure.daycare.money", format(amount)
        ).getString();
    }

    private int visibleTrainingExperience(DaycareNetwork.PokemonView pokemon) {
        if (!pokemon.training()) return 0;
        long elapsedSeconds = Math.max(
            0L, (System.currentTimeMillis() - payloadReceivedAtMillis) / 1_000L
        );
        long gainedSinceSnapshot = elapsedSeconds * payload.trainingExperiencePerSecond();
        long visible = pokemon.trainingExperience() + gainedSinceSnapshot;
        return (int) Math.min(pokemon.trainingExperienceLimit(), visible);
    }

    @Override
    public void renderBackground(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {}

    @Override
    public boolean isPauseScreen() { return false; }

    private static String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(amount);
    }

    private static String format(BigInteger amount) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(amount);
    }

    private static BigInteger parseBalance(String balance) {
        try {
            return new BigInteger(balance);
        } catch (NumberFormatException ignored) {
            return BigInteger.ZERO;
        }
    }

    private final class ModalBackdrop extends AbstractWidget {
        private ModalBackdrop() {
            super(0, 0, DaycareScreen.this.width, DaycareScreen.this.height, Component.empty());
            active = false;
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            drawWithdrawConfirmation(graphics);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

    private final class PokemonCard extends AbstractButton {
        private final DaycareNetwork.PokemonView pokemon;
        private final int slot;
        private final boolean stored;

        private PokemonCard(
            DaycareNetwork.PokemonView pokemon, int slot, boolean stored,
            int x, int y, int width, int height
        ) {
            super(x, y, width, height, pokemon.emptySlot()
                ? Component.translatable("screen.cobbleventure_adventure.daycare.empty_slot")
                : Component.literal(pokemon.name()));
            this.pokemon = pokemon;
            this.slot = slot;
            this.stored = stored;
            if (!pokemon.emptySlot()) {
                setTooltip(Tooltip.create(Component.literal(
                    pokemon.name() + " · Lv." + pokemon.level()
                        + (pokemon.training() ? " · ★" : "")
                )));
            }
            refreshState();
        }

        private void refreshState() {
            active = !pokemon.emptySlot() && !requestPending && !validConfirmation();
        }

        private boolean selected() {
            return stored ? selectedStoredSlot == slot : selectedPartySlot == slot;
        }

        @Override
        public void onPress() { select(slot, stored); }

        @Override
        protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean selected = selected();
            int border = selected ? theme.accent : theme.border;
            int fill = selected ? theme.selectedBackground
                : isHovered() ? theme.hoverBackground : theme.background;
            DaycareThemedPanel.roundedFill(graphics, getX(), getY(),
                getX() + getWidth(), getY() + getHeight(), theme.rowRadius, border);
            DaycareThemedPanel.roundedFill(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), fill);
            if (pokemon.emptySlot()) {
                drawCenteredNoShadow(graphics, "·", getX() + getWidth() / 2,
                    getY() + getHeight() / 2 - 4, theme.mutedText());
                return;
            }
            if (stored) {
                int nameY = getY() + getHeight() - 20;
                drawCenteredNoShadow(graphics,
                    font.plainSubstrByWidth(pokemon.name(), getWidth() - 8),
                    getX() + getWidth() / 2, nameY,
                    selected ? theme.selectedTextColor : theme.textColor);
                drawCenteredNoShadow(graphics, "Lv." + pokemon.level(),
                    getX() + getWidth() / 2, getY() + getHeight() - 10,
                    theme.mutedText());
                if (pokemon.training()) {
                    graphics.drawString(font, "★", getX() + getWidth() - 10,
                        getY() + 4, theme.accent, false);
                }
                return;
            }

            int modelSpace = Math.min(35, getHeight() + 2);
            int textX = getX() + modelSpace;
            int textWidth = Math.max(14, getWidth() - modelSpace - 4);
            graphics.drawString(font,
                font.plainSubstrByWidth(pokemon.name(), textWidth),
                textX, getY() + Math.max(3, getHeight() / 2 - 10),
                selected ? theme.selectedTextColor : theme.textColor, false);
            graphics.drawString(font, "Lv." + pokemon.level(), textX,
                getY() + Math.max(13, getHeight() / 2 + 1), theme.mutedText(), false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class ActionButton extends AbstractButton {
        private final Runnable action;

        private ActionButton(int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, Component.empty());
            this.action = action;
        }

        @Override
        public void onPress() { if (active) action.run(); }

        @Override
        protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean primary = active && (this == depositButton || this == withdrawButton
                || this == confirmWithdrawButton);
            int border = primary ? 0xFFE85D58 : active ? theme.accent : theme.border;
            int fill = !active ? DaycareThemedPanel.withOpacity(theme.background, .58F)
                : primary ? (isHovered() ? 0xFFFF9189 : 0xFFFF746B)
                : isHovered() ? theme.hoverBackground : theme.selectedBackground;
            DaycareThemedPanel.roundedFill(graphics, getX(), getY(),
                getX() + getWidth(), getY() + getHeight(), theme.rowRadius, border);
            DaycareThemedPanel.roundedFill(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), fill);
            drawCenteredNoShadow(graphics,
                font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                primary ? 0xFFFFFFFF
                    : active ? theme.selectedTextColor : theme.mutedText());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private void drawCenteredNoShadow(
        GuiGraphics graphics, String text, int centerX, int y, int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }
}
