package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Party picker used to give a held item directly from the bag. */
final class BagPokemonSelectScreen extends Screen {
    private static final int PANEL_WIDTH = 310;
    private static final int PANEL_HEIGHT = 224;
    private static final int PANEL = 0xF01D2630;
    private static final int DARK = 0xFF10171E;
    private static final int LIGHT = 0xFF34444F;
    private static final int ACCENT = 0xFF5EE4E4;
    private static final int TEXT = 0xFFF4F4F4;
    private static final int MUTED = 0xFFA6A6A6;

    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack stack;
    private int panelX;
    private int panelY;

    BagPokemonSelectScreen(BagScreen parent, boolean extended, int sourceSlot, ItemStack stack) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag.pokemon_select.title"));
        this.parent = parent;
        this.extended = extended;
        this.sourceSlot = sourceSlot;
        this.stack = stack;
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = CobblemonClient.INSTANCE.getStorage().getParty().get(slot);
            if (pokemon == null) continue;
            int column = slot % 2;
            int row = slot / 2;
            addRenderableWidget(new PokemonButton(
                pokemon, slot,
                panelX + 10 + column * 146,
                panelY + 47 + row * 44,
                140, 38
            ));
        }
        addRenderableWidget(new ActionButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.cancel"),
            panelX + PANEL_WIDTH - 74, panelY + PANEL_HEIGHT - 27, 64, 19, this::onClose
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        fillRoundedRect(graphics, panelX + 4, panelY + 5,
            panelX + PANEL_WIDTH + 4, panelY + PANEL_HEIGHT + 5, 9, 0x99000000);
        fillRoundedRect(graphics, panelX, panelY,
            panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 9, DARK);
        fillRoundedRect(graphics, panelX + 1, panelY + 1,
            panelX + PANEL_WIDTH - 1, panelY + PANEL_HEIGHT - 1, 8, PANEL);
        graphics.fill(panelX + 12, panelY + 1, panelX + 54, panelY + 3, ACCENT);
        graphics.renderItem(stack, panelX + 11, panelY + 12);
        graphics.drawString(font, title, panelX + 35, panelY + 11, TEXT, false);
        graphics.drawString(font,
            font.plainSubstrByWidth(stack.getHoverName().getString(), PANEL_WIDTH - 46),
            panelX + 35, panelY + 25, MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void select(Pokemon pokemon, int partySlot) {
        PlayerMenuClient.giveBagItemToPokemon(extended, sourceSlot, partySlot);
        parent.pokemonGiveRequested(pokemon.getDisplayName(false));
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private final class PokemonButton extends AbstractButton {
        private final Pokemon pokemon;
        private final int partySlot;

        private PokemonButton(Pokemon pokemon, int partySlot, int x, int y, int width, int height) {
            super(x, y, width, height, pokemon.getDisplayName(false));
            this.pokemon = pokemon;
            this.partySlot = partySlot;
        }

        @Override
        public void onPress() { select(pokemon, partySlot); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHovered() ? ACCENT : LIGHT;
            int fill = isHovered() ? 0xE0374650 : DARK;
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 8, border);
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1, 7, fill);
            graphics.drawString(font,
                font.plainSubstrByWidth(pokemon.getDisplayName(false).getString(), getWidth() - 34),
                getX() + 8, getY() + 7, isHovered() ? ACCENT : TEXT, false);
            ItemStack held = pokemon.heldItem();
            if (held.isEmpty()) {
                graphics.drawString(font,
                    Component.translatable("screen.cobbleventure_player_menu.bag.no_held_item"),
                    getX() + 8, getY() + 21, MUTED, false);
            } else {
                graphics.renderItem(held, getX() + getWidth() - 23, getY() + 11);
                graphics.drawString(font,
                    font.plainSubstrByWidth(held.getHoverName().getString(), getWidth() - 38),
                    getX() + 8, getY() + 21, MUTED, false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class ActionButton extends AbstractButton {
        private final Runnable action;

        private ActionButton(Component message, int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
        }

        @Override
        public void onPress() { action.run(); }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                getHeight() / 2, isHovered() ? ACCENT : LIGHT);
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(1, getHeight() / 2 - 1), DARK);
            graphics.drawCenteredString(font, getMessage(), getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2, isHovered() ? ACCENT : TEXT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void fillRoundedRect(
        GuiGraphics graphics, int left, int top, int right, int bottom, int radius, int color
    ) {
        int width = Math.max(0, right - left);
        int height = Math.max(0, bottom - top);
        int effectiveRadius = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        for (int row = 0; row < height; row++) {
            int edgeDistance = Math.min(row, height - 1 - row);
            int inset = 0;
            if (edgeDistance < effectiveRadius) {
                double vertical = effectiveRadius - edgeDistance - 0.5D;
                inset = effectiveRadius - (int) Math.floor(Math.sqrt(
                    Math.max(0.0D, effectiveRadius * effectiveRadius - vertical * vertical)
                ));
            }
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
        }
    }
}
