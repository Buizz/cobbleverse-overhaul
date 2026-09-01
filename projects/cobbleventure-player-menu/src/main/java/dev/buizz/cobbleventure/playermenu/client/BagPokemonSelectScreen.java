package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Party picker used for held-item assignment and Pokémon-targeting bag items. */
final class BagPokemonSelectScreen extends Screen {
    enum Action { USE, GIVE }
    private static final int HP_GREEN = 0xFF64D66D;
    private static final int HP_YELLOW = 0xFFE6C84F;
    private static final int HP_RED = 0xFFE86666;
    private static final int EXP_BLUE = 0xFF59BCE8;

    private final BagScreen parent;
    private final boolean extended;
    private final int sourceSlot;
    private final ItemStack stack;
    private final Action action;
    private final MenuTheme theme;
    private final List<ModelWidget> models = new ArrayList<>();
    private final List<PokemonButton> pokemonButtons = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int cardWidth;
    private int cardHeight;

    BagPokemonSelectScreen(BagScreen parent, boolean extended, int sourceSlot, ItemStack stack, Action action) {
        super(Component.translatable("screen.cobbleventure_player_menu.bag.pokemon_select."
            + (action == Action.USE ? "use_title" : "give_title")));
        this.parent = parent;
        this.extended = extended;
        this.sourceSlot = sourceSlot;
        this.stack = stack;
        this.action = action;
        this.theme = MenuTheme.load(Minecraft.getInstance());
    }

    @Override
    protected void init() {
        models.clear();
        pokemonButtons.clear();
        panelWidth = Math.min(540, Math.max(300, width - 24));
        panelHeight = Math.min(310, Math.max(210, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int gap = 7;
        cardWidth = (panelWidth - 24 - gap) / 2;
        cardHeight = Math.max(48, (panelHeight - 96 - gap * 2) / 3);
        int cardTop = panelY + 53;
        for (int slot = 0; slot < 6; slot++) {
            Pokemon pokemon = CobblemonClient.INSTANCE.getStorage().getParty().get(slot);
            if (pokemon == null) continue;
            int column = slot % 2;
            int row = slot / 2;
            int cardX = panelX + 8 + column * (cardWidth + gap);
            int cardY = cardTop + row * (cardHeight + gap);
            PokemonButton button = addRenderableWidget(new PokemonButton(
                pokemon, slot,
                cardX, cardY, cardWidth, cardHeight
            ));
            pokemonButtons.add(button);
            int modelSize = Math.min(46, cardHeight - 6);
            ModelWidget model = CobblemonModelWidgetCompat.create(
                cardX + 3, cardY + (cardHeight - modelSize) / 2,
                modelSize, modelSize, pokemon.asRenderablePokemon(),
                Math.max(0.85F, modelSize / 42.0F), 25.0F, 0.0D, false, false
            );
            model.active = false;
            models.add(addRenderableWidget(model));
        }
        addRenderableWidget(new ActionButton(
            Component.translatable("screen.cobbleventure_player_menu.bag.cancel"),
            panelX + panelWidth - 76, panelY + panelHeight - 27, 66, 19, this::onClose
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ThemedOverlayPanel.draw(graphics, theme, panelX, panelY, panelWidth, panelHeight);
        graphics.fill(panelX + 12, panelY + 1, panelX + 54, panelY + 3, theme.accent);
        theme.drawText(graphics, font, title, panelX + 12, panelY + 10, MenuTheme.TextRole.HEADING);
        theme.drawText(graphics, font,
            Component.translatable("screen.cobbleventure_player_menu.bag.pokemon_select."
                + (action == Action.USE ? "use_hint" : "give_hint"), stack.getHoverName()),
            panelX + 12, panelY + 26, MenuTheme.TextRole.CAPTION);
        graphics.renderItem(stack, panelX + panelWidth - 30, panelY + 9);
        graphics.renderItemDecorations(font, stack, panelX + panelWidth - 30, panelY + 9);
        graphics.fill(panelX + 8, panelY + 45, panelX + panelWidth - 8, panelY + 46, theme.innerBorder);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (PokemonButton button : pokemonButtons) button.renderHeldItemTooltip(graphics, mouseX, mouseY);
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
        if (action == Action.USE) {
            PlayerMenuClient.useBagItemOnPokemon(extended, sourceSlot, partySlot);
            parent.pokemonUseRequested();
        } else {
            PlayerMenuClient.giveBagItemToPokemon(extended, sourceSlot, partySlot);
            parent.pokemonGiveRequested(pokemon.getDisplayName(false));
        }
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
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.SECONDARY, active, isHovered(), false
            );
            int border = style.border();
            int fill = style.background();
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 8, border);
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1, 7, fill);
            int detailsX = getX() + Math.min(50, getHeight());
            int detailsWidth = Math.max(38, getWidth() - (detailsX - getX()) - 7);
            graphics.drawString(font,
                font.plainSubstrByWidth(pokemon.getDisplayName(false).getString(), detailsWidth - 42),
                detailsX, getY() + 6, style.text(), false);
            String level = Component.translatable(
                "screen.cobbleventure_player_menu.bag.pokemon_select.level", pokemon.getLevel()
            ).getString();
            graphics.drawString(font, level, getX() + getWidth() - 7 - font.width(level),
                getY() + 6, theme.mutedTextColor, false);

            int maximumHealth = Math.max(1, pokemon.getMaxHealth());
            int currentHealth = Math.max(0, pokemon.getCurrentHealth());
            float healthRatio = Math.min(1.0F, currentHealth / (float) maximumHealth);
            boolean compact = getHeight() < 62;
            int healthBarY = getY() + (compact ? 18 : 27);
            graphics.fill(detailsX, healthBarY, detailsX + detailsWidth, healthBarY + 4, 0xFF0B1116);
            int healthWidth = Math.round((detailsWidth - 2) * healthRatio);
            int healthColor = healthRatio > 0.5F ? HP_GREEN : healthRatio > 0.2F ? HP_YELLOW : HP_RED;
            if (healthWidth > 0) {
                graphics.fill(detailsX + 1, healthBarY + 1,
                    detailsX + 1 + healthWidth, healthBarY + 3, healthColor);
            }

            int levelStartExperience = pokemon.getExperienceGroup().getExperience(pokemon.getLevel());
            int currentLevelExperience = Math.max(0, pokemon.getExperience() - levelStartExperience);
            boolean maximumLevel = !pokemon.canLevelUpFurther();
            int requiredExperience = maximumLevel ? 1 : Math.max(
                1, pokemon.getExperienceGroup().getExperience(pokemon.getLevel() + 1) - levelStartExperience
            );
            float experienceRatio = maximumLevel
                ? 1.0F
                : Math.min(1.0F, currentLevelExperience / (float) requiredExperience);
            int experienceBarY = getY() + (compact ? 25 : 42);
            graphics.fill(detailsX, experienceBarY,
                detailsX + detailsWidth, experienceBarY + 4, 0xFF0B1116);
            int experienceWidth = Math.round((detailsWidth - 2) * experienceRatio);
            if (experienceWidth > 0) {
                graphics.fill(detailsX + 1, experienceBarY + 1,
                    detailsX + 1 + experienceWidth, experienceBarY + 3, EXP_BLUE);
            }
            if (!compact) {
                String health = Component.translatable(
                    "screen.cobbleventure_player_menu.bag.pokemon_select.hp", currentHealth, maximumHealth
                ).getString();
                graphics.drawString(font, health, detailsX, getY() + 17, theme.mutedTextColor, false);
                String experience = maximumLevel
                    ? Component.translatable("screen.cobbleventure_player_menu.bag.pokemon_select.exp_max").getString()
                    : Component.translatable(
                        "screen.cobbleventure_player_menu.bag.pokemon_select.exp",
                        currentLevelExperience, requiredExperience
                    ).getString();
                graphics.drawString(font,
                    font.plainSubstrByWidth(experience, detailsWidth),
                    detailsX, getY() + 32, theme.mutedTextColor, false);
            }

            ItemStack held = pokemon.heldItem();
            int heldY = getY() + getHeight() - 18;
            if (held.isEmpty()) {
                graphics.drawString(font,
                    Component.translatable("screen.cobbleventure_player_menu.bag.no_held_item"),
                    detailsX, heldY + 5, theme.mutedTextColor, false);
            } else {
                graphics.renderItem(held, detailsX, heldY);
                graphics.drawString(font,
                    font.plainSubstrByWidth(held.getHoverName().getString(), detailsWidth - 19),
                    detailsX + 18, heldY + 5, theme.mutedTextColor, false);
            }
        }

        private void renderHeldItemTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
            ItemStack held = pokemon.heldItem();
            int detailsX = getX() + Math.min(50, getHeight());
            int heldY = getY() + getHeight() - 18;
            if (!held.isEmpty() && mouseX >= detailsX && mouseX < detailsX + 16
                && mouseY >= heldY && mouseY < heldY + 16) {
                graphics.renderTooltip(font, held, mouseX, mouseY);
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
            MenuTheme.ButtonStyle style = theme.button(
                MenuTheme.ButtonVariant.GHOST, active, isHovered(), false
            );
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                getHeight() / 2, style.border());
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(1, getHeight() / 2 - 1), style.background());
            theme.drawCenteredText(graphics, font, getMessage(), getX() + getWidth() / 2,
                getY() + (getHeight() - 8) / 2, MenuTheme.TextRole.LABEL, style.text());
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
