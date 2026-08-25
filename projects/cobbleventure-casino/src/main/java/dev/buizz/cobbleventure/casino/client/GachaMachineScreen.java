package dev.buizz.cobbleventure.casino.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import dev.buizz.cobbleventure.casino.CasinoItems;
import dev.buizz.cobbleventure.casino.GachaMachineNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/** PokeRogue-inspired reward preview with a short server-authoritative reveal. */
public final class GachaMachineScreen extends Screen {
    private static final int ROW_HEIGHT = 25;
    private static final int THEME_ROW_HEIGHT = 29;
    private static final int MAX_PANEL_WIDTH = 680;
    private static final int MAX_PANEL_HEIGHT = 350;
    private static final int SCREEN_INSET_X = 40;
    private static final int SCREEN_INSET_Y = 36;
    private static final int REVEAL_TICKS = 42;
    private final GachaMachineNetwork.OpenPayload payload;
    private final List<GachaMachineNetwork.ThemeView> themes;
    private List<GachaMachineNetwork.RewardView> rewards;
    private int selectedTheme;
    private int themeScroll;
    private int tickets;
    private int pulls;
    private int hardPity;
    private int selectionPoints;
    private int selectionRequired;
    private int scroll;
    private int animationTicks;
    private GachaMachineNetwork.ResultPayload pendingResult;
    private State state = State.PREVIEW;
    private GachaButton pullButton;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int listLeft;
    private int listTop;
    private int listRight;
    private int listBottom;
    private int themeLeft;
    private int themeTop;
    private int themeRight;
    private int themeBottom;
    private CasinoMenuTheme menuTheme;

    public GachaMachineScreen(GachaMachineNetwork.OpenPayload payload) {
        super(Component.literal(payload.machineName()));
        this.payload = payload;
        this.themes = new ArrayList<>(payload.themes());
        this.rewards = List.of();
        this.tickets = payload.tickets();
        selectTheme(0);
    }

    @Override
    protected void init() {
        menuTheme = CasinoMenuTheme.load(minecraft);
        int panelWidth = Math.min(MAX_PANEL_WIDTH, width - SCREEN_INSET_X);
        int panelHeight = Math.min(MAX_PANEL_HEIGHT, height - SCREEN_INSET_Y);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        panelRight = panelLeft + panelWidth;
        panelBottom = panelTop + panelHeight;
        themeLeft = panelLeft + 14;
        themeTop = panelTop + 58;
        themeRight = Math.min(themeLeft + 142, panelLeft + Math.max(108, panelWidth / 4));
        themeBottom = panelBottom - 16;
        int split = panelRight - Math.max(190, panelWidth / 3);
        listLeft = themeRight + 10;
        listTop = panelTop + 58;
        listRight = split - 8;
        listBottom = panelBottom - 16;
        int buttonWidth = Math.max(120, panelRight - split - 28);
        pullButton = addRenderableWidget(new GachaButton(
            split + 14, panelBottom - 48, buttonWidth, 28
        ));
        updateButton();
    }

    @Override
    public void tick() {
        if (state != State.ROLLING) return;
        animationTicks++;
        if (animationTicks % 4 == 0) playTick();
        if (animationTicks >= REVEAL_TICKS && pendingResult != null) reveal();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB8050A12);
        CasinoThemedPanel.draw(
            graphics, menuTheme,
            panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop,
            1.0F, menuTheme.accent
        );
        graphics.drawString(font, title, panelLeft + 16, panelTop + 11, menuTheme.textColor, false);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_casino.gacha.subtitle"),
            panelLeft + 16, panelTop + 23, menuTheme.mutedText(), false);
        graphics.fill(panelLeft + 12, panelTop + 37, panelRight - 12, panelTop + 39,
            menuTheme.accent);

        renderTicketBadge(graphics);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_casino.gacha.themes"),
            themeLeft, themeTop - 15, menuTheme.accent, false);
        renderThemeList(graphics, mouseX, mouseY);
        graphics.drawString(font,
            Component.translatable("screen.cobbleventure_casino.gacha.pool"),
            listLeft, listTop - 15, menuTheme.accent, false);
        renderRewardList(graphics, mouseX, mouseY);
        renderControlPanel(graphics, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderTicketBadge(GuiGraphics graphics) {
        int right = panelRight - 14;
        int left = Math.max(panelLeft + 180, right - 170);
        CasinoThemedPanel.roundedFill(
            graphics, left, panelTop + 7, right, panelTop + 34,
            menuTheme.rowRadius, menuTheme.selectedBackground
        );
        CasinoThemedPanel.roundedFill(
            graphics, left + 2, panelTop + 10, left + 5, panelTop + 31,
            1, menuTheme.accent
        );
        graphics.renderItem(ticketIcon(), left + 8, panelTop + 13);
        graphics.drawString(font, payload.ticketName(), left + 29, panelTop + 11, menuTheme.mutedText(), false);
        graphics.drawString(font, "× " + tickets, left + 29, panelTop + 22, menuTheme.textColor, false);
    }

    private ItemStack ticketIcon() {
        ItemStack stack = new ItemStack(CasinoItems.GACHA_TICKET.get());
        int modelData = switch (payload.machineType()) {
            case "pokemon" -> 1;
            case "item" -> 2;
            case "technical_machine" -> 3;
            default -> 0;
        };
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelData));
        return stack;
    }

    private GachaMachineNetwork.ThemeView activeTheme() {
        return selectedTheme >= 0 && selectedTheme < themes.size() ? themes.get(selectedTheme) : null;
    }

    private void selectTheme(int index) {
        if (index < 0 || index >= themes.size()) {
            selectedTheme = -1;
            rewards = List.of();
            pulls = hardPity = selectionPoints = selectionRequired = 0;
            return;
        }
        selectedTheme = index;
        GachaMachineNetwork.ThemeView theme = themes.get(index);
        rewards = theme.rewards();
        pulls = theme.pullsSinceTarget();
        hardPity = theme.hardPityCount();
        selectionPoints = theme.selectionPoints();
        selectionRequired = theme.selectionRequired();
        scroll = 0;
        pendingResult = null;
        state = State.PREVIEW;
        updateButton();
    }

    private void renderThemeList(GuiGraphics graphics, int mouseX, int mouseY) {
        CasinoThemedPanel.roundedFill(
            graphics, themeLeft, themeTop, themeRight, themeBottom,
            menuTheme.rowRadius,
            CasinoThemedPanel.withOpacity(menuTheme.selectedBackground, .42F)
        );
        int visible = Math.max(1, (themeBottom - themeTop - 8) / THEME_ROW_HEIGHT);
        themeScroll = Math.clamp(themeScroll, 0, Math.max(0, themes.size() - visible));
        graphics.enableScissor(themeLeft, themeTop, themeRight, themeBottom);
        for (int row = 0; row < visible; row++) {
            int index = themeScroll + row;
            if (index >= themes.size()) break;
            GachaMachineNetwork.ThemeView theme = themes.get(index);
            int y = themeTop + 4 + row * THEME_ROW_HEIGHT;
            boolean selected = index == selectedTheme;
            boolean hovered = mouseX >= themeLeft + 4 && mouseX < themeRight - 4
                && mouseY >= y && mouseY < y + THEME_ROW_HEIGHT - 3;
            CasinoThemedPanel.roundedFill(
                graphics, themeLeft + 4, y, themeRight - 4, y + THEME_ROW_HEIGHT - 3,
                menuTheme.rowRadius,
                selected ? menuTheme.selectedBackground
                    : hovered ? menuTheme.hoverBackground : menuTheme.background
            );
            if (selected) CasinoThemedPanel.roundedFill(
                graphics, themeLeft + 6, y + 3, themeLeft + 9, y + THEME_ROW_HEIGHT - 6,
                1, menuTheme.accent
            );
            int textLeft = themeLeft + (selected ? 13 : 9);
            graphics.drawString(font,
                font.plainSubstrByWidth(theme.name(), Math.max(20, themeRight - textLeft - 8)),
                textLeft, y + 5, selected ? menuTheme.selectedTextColor : menuTheme.textColor, false);
            graphics.drawString(font, theme.ticketCost() + "장",
                textLeft, y + 16, menuTheme.mutedText(), false);
        }
        graphics.disableScissor();
        if (themes.size() > visible) {
            int trackTop = themeTop + 4;
            int trackBottom = themeBottom - 4;
            int thumb = Math.max(14, (trackBottom - trackTop) * visible / themes.size());
            int maxScroll = themes.size() - visible;
            int thumbTop = trackTop + (trackBottom - trackTop - thumb) * themeScroll / maxScroll;
            graphics.fill(themeRight - 3, trackTop, themeRight - 1, trackBottom,
                CasinoThemedPanel.withOpacity(menuTheme.border, .45F));
            graphics.fill(themeRight - 3, thumbTop, themeRight - 1, thumbTop + thumb,
                menuTheme.accent);
        }
    }

    private void renderRewardList(GuiGraphics graphics, int mouseX, int mouseY) {
        CasinoThemedPanel.roundedFill(
            graphics, listLeft, listTop, listRight, listBottom,
            menuTheme.rowRadius,
            CasinoThemedPanel.withOpacity(menuTheme.selectedBackground, .42F)
        );
        int visible = Math.max(1, (listBottom - listTop - 8) / ROW_HEIGHT);
        scroll = Math.clamp(scroll, 0, Math.max(0, rewards.size() - visible));
        graphics.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int row = 0; row < visible; row++) {
            int index = scroll + row;
            if (index >= rewards.size()) break;
            GachaMachineNetwork.RewardView reward = rewards.get(index);
            int y = listTop + 4 + row * ROW_HEIGHT;
            boolean hovered = mouseX >= listLeft + 4 && mouseX < listRight - 4
                && mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
            int rarityColor = rarityColor(reward.rarityId());
            CasinoThemedPanel.roundedFill(
                graphics, listLeft + 4, y, listRight - 4, y + ROW_HEIGHT - 3,
                menuTheme.rowRadius,
                hovered ? menuTheme.hoverBackground : menuTheme.background
            );
            CasinoThemedPanel.roundedFill(
                graphics, listLeft + 6, y + 3, listLeft + 9, y + ROW_HEIGHT - 6,
                1, rarityColor
            );
            graphics.renderItem(rewardIcon(reward.kind(), reward.value()), listLeft + 11, y + 4);
            Component name = rewardName(reward.kind(), reward.value(), reward.rewardId());
            graphics.drawString(font,
                font.plainSubstrByWidth(name.getString(), Math.max(30, listRight - listLeft - 145)),
                listLeft + 32, y + 5, menuTheme.textColor, false);
            graphics.drawString(font, reward.rarityName(), listLeft + 32, y + 16, rarityColor, false);
            String chance = String.format(Locale.ROOT, "%.2f%%", reward.chance() * 100.0D);
            int chanceWidth = font.width(chance);
            graphics.drawString(font, chance, listRight - chanceWidth - 12, y + 9,
                menuTheme.mutedText(), false);
        }
        graphics.disableScissor();
        if (rewards.size() > visible) {
            int trackTop = listTop + 4;
            int trackBottom = listBottom - 4;
            int thumb = Math.max(14, (trackBottom - trackTop) * visible / rewards.size());
            int travel = trackBottom - trackTop - thumb;
            int maxScroll = rewards.size() - visible;
            int thumbTop = trackTop + (maxScroll == 0 ? 0 : travel * scroll / maxScroll);
            graphics.fill(listRight - 3, trackTop, listRight - 1, trackBottom,
                CasinoThemedPanel.withOpacity(menuTheme.border, .45F));
            graphics.fill(listRight - 3, thumbTop, listRight - 1, thumbTop + thumb,
                menuTheme.accent);
        }
    }

    private void renderControlPanel(GuiGraphics graphics, float partialTick) {
        int left = listRight + 12;
        int right = panelRight - 14;
        int top = listTop;
        int bottom = panelBottom - 58;
        CasinoThemedPanel.draw(
            graphics, menuTheme, left, top, right - left, bottom - top,
            1.0F, menuTheme.accent
        );
        int centerX = (left + right) / 2;
        GachaMachineNetwork.ThemeView theme = activeTheme();
        graphics.drawCenteredString(font,
            theme == null ? Component.translatable("screen.cobbleventure_casino.gacha.machine")
                : Component.literal(theme.name()),
            centerX, top + 12, menuTheme.mutedText());

        if (state == State.ROLLING) {
            int pulse = 4 + (animationTicks % 12 < 6 ? animationTicks % 6 : 11 - animationTicks % 12);
            graphics.fill(centerX - 35 - pulse, top + 37 - pulse, centerX + 35 + pulse, top + 107 + pulse, 0x3037A6FF);
            CasinoThemedPanel.roundedFill(
                graphics, centerX - 31, top + 41, centerX + 31, top + 103,
                menuTheme.rowRadius, menuTheme.selectedBackground
            );
            GachaMachineNetwork.RewardView preview = rewards.isEmpty() ? null
                : rewards.get((animationTicks / 3) % rewards.size());
            if (preview != null) {
                graphics.renderItem(rewardIcon(preview.kind(), preview.value()), centerX - 8, top + 58);
                graphics.drawCenteredString(font,
                    font.plainSubstrByWidth(rewardName(preview.kind(), preview.value(), preview.rewardId()).getString(), right - left - 20),
                    centerX, top + 112, rarityColor(preview.rarityId()));
            }
            graphics.drawCenteredString(font,
                Component.translatable("screen.cobbleventure_casino.gacha.rolling"),
                centerX, top + 128, menuTheme.accent);
        } else if (state == State.REVEALED && pendingResult != null && pendingResult.success()) {
            int color = rarityColor(pendingResult.rarityId());
            graphics.fill(centerX - 37, top + 35, centerX + 37, top + 109, color);
            CasinoThemedPanel.roundedFill(
                graphics, centerX - 33, top + 39, centerX + 33, top + 105,
                menuTheme.rowRadius, menuTheme.background
            );
            graphics.renderItem(rewardIcon(pendingResult.kind(), pendingResult.value()), centerX - 8, top + 59);
            graphics.drawCenteredString(font, pendingResult.rarityName(), centerX, top + 115, color);
            graphics.drawCenteredString(font,
                font.plainSubstrByWidth(rewardName(pendingResult.kind(), pendingResult.value(), pendingResult.rewardId()).getString(), right - left - 18),
                centerX, top + 129, menuTheme.textColor);
            graphics.drawCenteredString(font,
                Component.translatable(pendingResult.messageKey()), centerX, top + 143,
                menuTheme.accent);
        } else {
            graphics.renderItem(ticketIcon(), centerX - 8, top + 60);
            Component status = state == State.ERROR && pendingResult != null
                ? Component.translatable(pendingResult.messageKey())
                : Component.translatable("screen.cobbleventure_casino.gacha.ready");
            graphics.drawCenteredString(font, status, centerX, top + 92,
                state == State.ERROR ? 0xFFFF7070 : menuTheme.textColor);
        }

        int infoY = Math.max(top + 160, bottom - 55);
        if (hardPity > 0) graphics.drawCenteredString(font,
            Component.translatable("screen.cobbleventure_casino.gacha.hard_pity", pulls, hardPity),
            centerX, infoY, menuTheme.mutedText());
        if (selectionRequired > 0) graphics.drawCenteredString(font,
            Component.translatable("screen.cobbleventure_casino.gacha.selection", selectionPoints, selectionRequired),
            centerX, infoY + 13, menuTheme.mutedText());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= themeLeft && mouseX < themeRight && mouseY >= themeTop && mouseY < themeBottom) {
            themeScroll = Math.max(0, themeScroll + (scrollY < 0 ? 1 : -1));
            return true;
        }
        if (mouseX >= listLeft && mouseX < listRight && mouseY >= listTop && mouseY < listBottom) {
            scroll = Math.max(0, scroll + (scrollY < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (state != State.ROLLING && button == 0
            && mouseX >= themeLeft && mouseX < themeRight
            && mouseY >= themeTop && mouseY < themeBottom) {
            int row = ((int) mouseY - themeTop - 4) / THEME_ROW_HEIGHT;
            int index = themeScroll + row;
            if (row >= 0 && index >= 0 && index < themes.size()) {
                selectTheme(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }

    public void handleResult(GachaMachineNetwork.ResultPayload result) {
        if (!payload.token().equals(result.token())) return;
        pendingResult = result;
        if (!result.success()) {
            tickets = result.tickets();
            state = State.ERROR;
            updateButton();
        } else if (animationTicks >= REVEAL_TICKS) {
            reveal();
        }
    }

    private void pull() {
        GachaMachineNetwork.ThemeView theme = activeTheme();
        if (state == State.ROLLING || theme == null || tickets < theme.ticketCost()) return;
        pendingResult = null;
        animationTicks = 0;
        state = State.ROLLING;
        updateButton();
        GachaMachineNetwork.pull(payload.token(), theme.id());
        playTick();
    }

    private void reveal() {
        if (pendingResult == null) return;
        tickets = pendingResult.tickets();
        int themeIndex = -1;
        for (int index = 0; index < themes.size(); index++) {
            if (themes.get(index).id().equals(pendingResult.themeId())) { themeIndex = index; break; }
        }
        if (themeIndex >= 0 && pendingResult.success()) {
            GachaMachineNetwork.ThemeView previous = themes.get(themeIndex);
            List<GachaMachineNetwork.RewardView> updatedRewards = pendingResult.rewards().isEmpty()
                ? previous.rewards() : pendingResult.rewards();
            themes.set(themeIndex, new GachaMachineNetwork.ThemeView(
                previous.id(), previous.name(), pendingResult.ticketCost(),
                pendingResult.pullsSinceTarget(), pendingResult.hardPityCount(),
                pendingResult.selectionPoints(), pendingResult.selectionRequired(), updatedRewards
            ));
            if (themeIndex == selectedTheme) {
                rewards = updatedRewards;
                pulls = pendingResult.pullsSinceTarget();
                hardPity = pendingResult.hardPityCount();
                selectionPoints = pendingResult.selectionPoints();
                selectionRequired = pendingResult.selectionRequired();
            }
        }
        state = pendingResult.success() ? State.REVEALED : State.ERROR;
        if (minecraft != null) minecraft.getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.15F, 0.45F)
        );
        updateButton();
    }

    private void updateButton() {
        if (pullButton == null) return;
        GachaMachineNetwork.ThemeView theme = activeTheme();
        pullButton.active = state != State.ROLLING && theme != null && tickets >= theme.ticketCost();
        pullButton.setMessage(state == State.ROLLING
            ? Component.translatable("screen.cobbleventure_casino.gacha.rolling_button")
            : Component.translatable("screen.cobbleventure_casino.gacha.pull_button_cost",
                theme == null ? 1 : theme.ticketCost()));
    }

    private void playTick() {
        if (minecraft == null) return;
        minecraft.getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.1F + animationTicks * .008F, .22F)
        );
    }

    private Component rewardName(String kind, String value, String fallback) {
        try {
            if ("pokemon".equals(kind)) {
                String speciesName = value.strip().split("\\s+", 2)[0];
                Species species = PokemonSpecies.getByName(speciesName);
                if (species != null) return species.getTranslatedName();
            } else {
                Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(value)).orElse(Items.AIR);
                if (item != Items.AIR) return item.getDefaultInstance().getHoverName();
            }
        } catch (RuntimeException ignored) {}
        return Component.literal(fallback.replace('_', ' '));
    }

    private ItemStack rewardIcon(String kind, String value) {
        if ("pokemon".equals(kind)) {
            Item ball = BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball")
            ).orElse(Items.PAPER);
            return new ItemStack(ball);
        }
        try {
            Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(value)).orElse(Items.PAPER);
            return new ItemStack(item);
        } catch (RuntimeException ignored) {
            return new ItemStack(Items.PAPER);
        }
    }

    private static int rarityColor(String rarity) {
        String id = rarity.toLowerCase(Locale.ROOT);
        if (id.contains("legend") || id.contains("myth")) return 0xFFFFC857;
        if (id.contains("ultra") || id.contains("epic")) return 0xFFD67BFF;
        if (id.contains("rare")) return 0xFF62A7FF;
        if (id.contains("uncommon")) return 0xFF63D98A;
        return 0xFFB7C2CE;
    }

    private enum State { PREVIEW, ROLLING, REVEALED, ERROR }

    private final class GachaButton extends AbstractButton {
        private GachaButton(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }
        @Override public void onPress() { pull(); }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int background = !active ? menuTheme.background
                : isHovered() ? menuTheme.hoverBackground : menuTheme.selectedBackground;
            CasinoThemedPanel.roundedFill(
                graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                menuTheme.rowRadius, menuTheme.border
            );
            CasinoThemedPanel.roundedFill(
                graphics, getX() + 2, getY() + 2,
                getX() + getWidth() - 2, getY() + getHeight() - 2,
                Math.max(0, menuTheme.rowRadius - 2), background
            );
            if (active) CasinoThemedPanel.roundedFill(
                graphics, getX() + 4, getY() + 4, getX() + 7, getY() + getHeight() - 4,
                1, menuTheme.accent
            );
            graphics.drawCenteredString(font, getMessage(), getX() + getWidth() / 2,
                getY() + (getHeight() - font.lineHeight) / 2,
                active ? menuTheme.selectedTextColor : menuTheme.mutedText());
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
