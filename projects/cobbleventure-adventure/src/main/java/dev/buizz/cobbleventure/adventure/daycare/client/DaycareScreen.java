package dev.buizz.cobbleventure.adventure.daycare.client;

import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.buizz.cobbleventure.adventure.daycare.DaycareNetwork;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** PC-inspired daycare screen using the global Cobbleventure menu theme. */
final class DaycareScreen extends Screen {
    private static final int PARTY_SLOTS = 6;
    private static final int DAYCARE_SLOTS = 6;

    private DaycareNetwork.ViewPayload payload;
    private final List<PokemonCard> partyCards = new ArrayList<>();
    private final List<PokemonCard> daycareCards = new ArrayList<>();
    private final List<ModelWidget> models = new ArrayList<>();
    private DaycareMenuTheme theme;
    private ActionButton depositButton;
    private ActionButton withdrawButton;
    private ActionButton trainingButton;
    private ActionButton collectButton;
    private int selectedPartySlot = -1;
    private int selectedStoredSlot = -1;
    private boolean requestPending;
    private boolean trainingEnabled;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int partyX;
    private int partyWidth;
    private int storageX;
    private int storageWidth;
    private int gridTop;
    private int storageCardWidth;
    private int storageCardHeight;
    private int cardGap;
    private int gridHeight;
    private int actionY;

    DaycareScreen(DaycareNetwork.ViewPayload payload) {
        super(Component.translatable("screen.cobbleventure_adventure.daycare.title"));
        this.payload = payload;
    }

    boolean apply(DaycareNetwork.ViewPayload next) {
        payload = next;
        requestPending = false;
        selectedPartySlot = validPartySelection(selectedPartySlot) ? selectedPartySlot : -1;
        selectedStoredSlot = selectedStoredSlot < next.storedPokemon().size()
            ? selectedStoredSlot : -1;
        rebuildDaycareWidgets();
        return true;
    }

    @Override
    protected void init() {
        theme = DaycareMenuTheme.load(minecraft);
        DaycareScreenLayout layout = DaycareScreenLayout.calculate(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        panelX = layout.panelX();
        panelY = layout.panelY();
        partyWidth = layout.partyWidth();
        partyX = layout.partyX();
        storageX = layout.storageX();
        storageWidth = layout.storageWidth();
        gridTop = layout.gridTop();
        gridHeight = layout.gridHeight();
        cardGap = layout.cardGap();
        storageCardWidth = layout.storageCardWidth();
        storageCardHeight = layout.storageCardHeight();
        actionY = layout.actionY();
        rebuildDaycareWidgets();
    }

    private void rebuildDaycareWidgets() {
        clearWidgets();
        partyCards.clear();
        daycareCards.clear();
        models.clear();

        int partyCardHeight = Math.max(16, gridHeight / 6);
        for (int slot = 0; slot < PARTY_SLOTS; slot++) {
            DaycareNetwork.PokemonView view = payload.partySlots().get(slot);
            int y = gridTop + slot * partyCardHeight;
            PokemonCard card = addRenderableWidget(new PokemonCard(
                view, slot, false, partyX, y, partyWidth, partyCardHeight - 2
            ));
            partyCards.add(card);
            addModel(view, partyX + 2, y + 1, Math.min(22, partyCardHeight - 3));
        }

        for (int slot = 0; slot < DAYCARE_SLOTS; slot++) {
            DaycareNetwork.PokemonView view = slot < payload.storedPokemon().size()
                ? payload.storedPokemon().get(slot) : DaycareNetwork.PokemonView.empty();
            int x = storageX + (slot % 3) * (storageCardWidth + cardGap);
            int y = gridTop + (slot / 3) * (storageCardHeight + cardGap);
            PokemonCard card = addRenderableWidget(new PokemonCard(
                view, slot, true, x, y, storageCardWidth, storageCardHeight
            ));
            daycareCards.add(card);
            addModel(view, x + 3, y + 5,
                Math.min(Math.max(18, storageCardWidth - 6), Math.min(32, storageCardHeight - 10)));
        }

        int buttonGap = 5;
        int actionWidth = Math.max(34, (storageWidth - buttonGap * 2) / 3);
        trainingButton = addRenderableWidget(new ActionButton(
            storageX, actionY, actionWidth, 21, this::toggleTraining
        ));
        depositButton = addRenderableWidget(new ActionButton(
            storageX + actionWidth + buttonGap, actionY, actionWidth, 21,
            () -> send(DaycareNetwork.Action.DEPOSIT, selectedPartySlot)
        ));
        withdrawButton = addRenderableWidget(new ActionButton(
            storageX + (actionWidth + buttonGap) * 2, actionY, actionWidth, 21,
            () -> send(DaycareNetwork.Action.WITHDRAW, selectedStoredSlot)
        ));
        collectButton = addRenderableWidget(new ActionButton(
            storageX, actionY + 26, storageWidth, 21,
            () -> send(DaycareNetwork.Action.COLLECT, -1)
        ));
        ActionButton close = addRenderableWidget(new ActionButton(
            panelX + panelWidth - 78, panelY + panelHeight - 27, 66, 18, this::onClose
        ));
        close.setMessage(Component.translatable("gui.done"));
        refreshButtons();
    }

    private void addModel(DaycareNetwork.PokemonView view, int x, int y, int size) {
        if (view.emptySlot() || minecraft == null || minecraft.level == null) return;
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

    private boolean validPartySelection(int slot) {
        return slot >= 0 && slot < payload.partySlots().size()
            && !payload.partySlots().get(slot).emptySlot();
    }

    private void toggleTraining() {
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
        refreshButtons();
    }

    private void refreshButtons() {
        partyCards.forEach(PokemonCard::refreshState);
        daycareCards.forEach(PokemonCard::refreshState);
        if (depositButton == null) return;
        trainingButton.setMessage(Component.translatable(trainingEnabled
                ? "screen.cobbleventure_adventure.daycare.training_short_on"
                : "screen.cobbleventure_adventure.daycare.training_short_off"));
        depositButton.setMessage(Component.translatable(
            "screen.cobbleventure_adventure.daycare.deposit_short", format(payload.fee())
        ));
        withdrawButton.setMessage(Component.translatable(
            "screen.cobbleventure_adventure.daycare.withdraw_short"
        ));
        collectButton.setMessage(Component.translatable(
            "screen.cobbleventure_adventure.daycare.collect_short", payload.eggCount()
        ));
        trainingButton.active = !requestPending;
        depositButton.active = validPartySelection(selectedPartySlot)
            && payload.storedPokemon().size() < DAYCARE_SLOTS && !requestPending;
        withdrawButton.active = selectedStoredSlot >= 0 && !requestPending;
        collectButton.active = payload.eggCount() > 0 && !requestPending;
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x66071924);
        DaycareThemedPanel.draw(
            graphics, theme, panelX, panelY, panelWidth, panelHeight, theme.accent
        );
        graphics.drawString(font, title, panelX + 16, panelY + 14,
            theme.selectedTextColor, false);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_adventure.daycare.pc_hint"),
            panelX + 16, panelY + 29, theme.mutedText(), false);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_adventure.daycare.party"),
            partyX, panelY + 44, theme.textColor, false);
        Component storedTitle = Component.translatable(
            "screen.cobbleventure_adventure.daycare.stored",
            payload.storedPokemon().size(), DAYCARE_SLOTS
        );
        graphics.drawString(font, storedTitle, storageX, panelY + 44,
            theme.textColor, false);

        Component breeding = payload.storedPokemon().size() < 2
            ? Component.translatable("screen.cobbleventure_adventure.daycare.need_more")
            : payload.compatiblePair()
                ? Component.translatable(
                    "screen.cobbleventure_adventure.daycare.discovery_check",
                    payload.remainingMinutes()
                )
                : Component.translatable("screen.cobbleventure_adventure.daycare.no_pair");
        int statusY = panelY + panelHeight - 39;
        graphics.drawString(font,
            font.plainSubstrByWidth(breeding.getString(), panelWidth - 112),
            panelX + 14, statusY,
            payload.compatiblePair() ? theme.accent : theme.mutedText(), false);
        if (!payload.feedback().getString().isBlank()) {
            graphics.drawString(font,
                font.plainSubstrByWidth(payload.feedback().getString(), panelWidth - 112),
                panelX + 14, statusY + 13, 0xFFE45C5C, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void renderBackground(
        GuiGraphics graphics, int mouseX, int mouseY, float partialTick
    ) {}

    @Override public boolean isPauseScreen() { return false; }

    private static String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(amount);
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
            refreshState();
        }

        private void refreshState() { active = !pokemon.emptySlot() && !requestPending; }

        private boolean selected() {
            return stored ? selectedStoredSlot == slot : selectedPartySlot == slot;
        }

        @Override public void onPress() { select(slot, stored); }

        @Override protected void renderWidget(
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
            int modelSpace = stored ? Math.min(38, getHeight()) : Math.min(25, getHeight());
            int textX = getX() + modelSpace;
            int textWidth = Math.max(20, getWidth() - modelSpace - 5);
            if (!stored && getHeight() < 27) {
                String level = "Lv." + pokemon.level();
                int levelWidth = font.width(level);
                int textY = getY() + Math.max(3, (getHeight() - 8) / 2);
                graphics.drawString(font,
                    font.plainSubstrByWidth(pokemon.name(), Math.max(8, textWidth - levelWidth - 3)),
                    textX, textY,
                    selected ? theme.selectedTextColor : theme.textColor, false);
                graphics.drawString(font, level,
                    getX() + getWidth() - levelWidth - 4, textY, theme.mutedText(), false);
                return;
            }
            boolean compactStorage = stored && getHeight() < 56;
            graphics.drawString(font,
                font.plainSubstrByWidth(pokemon.name(), textWidth),
                textX, getY() + (compactStorage ? 7 : stored ? 12 : 5),
                selected ? theme.selectedTextColor : theme.textColor, false);
            graphics.drawString(font, "Lv." + pokemon.level(), textX,
                getY() + (compactStorage ? getHeight() - 13 : stored ? 27 : 16),
                theme.mutedText(), false);
            if (stored && pokemon.training()) {
                if (compactStorage) {
                    graphics.drawString(font, "★", getX() + getWidth() - 11,
                        getY() + 5, theme.accent, false);
                } else {
                    graphics.drawString(font,
                        Component.translatable("screen.cobbleventure_adventure.daycare.training_badge"),
                        textX, getY() + 42, theme.accent, false);
                }
            }
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class ActionButton extends AbstractButton {
        private final Runnable action;

        private ActionButton(int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, Component.empty());
            this.action = action;
        }

        @Override public void onPress() { if (active) action.run(); }

        @Override protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            int border = active ? theme.accent : theme.border;
            int fill = !active ? DaycareThemedPanel.withOpacity(theme.background, .58F)
                : isHovered() ? theme.hoverBackground : theme.selectedBackground;
            DaycareThemedPanel.roundedFill(graphics, getX(), getY(),
                getX() + getWidth(), getY() + getHeight(), theme.rowRadius, border);
            DaycareThemedPanel.roundedFill(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, theme.rowRadius - 1), fill);
            drawCenteredNoShadow(graphics,
                font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2,
                active ? theme.selectedTextColor : theme.mutedText());
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private void drawCenteredNoShadow(
        GuiGraphics graphics, String text, int centerX, int y, int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }
}
