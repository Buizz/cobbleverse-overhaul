package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.buizz.cobbleventure.playermenu.PlayerOverviewNetwork;
import dev.buizz.cobbleventure.playermenu.BagNetwork;
import dev.buizz.cobbleventure.playermenu.ProgressionNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.lwjgl.glfw.GLFW;

public final class PlayerMenuScreen extends Screen {
    private static final int MENU_MARGIN = 12;
    private static final int MENU_HEADER_HEIGHT = 22;
    private static final int MENU_PADDING = 5;
    private static final int ROW_GAP = 2;
    private static final int PANEL_GAP = 8;
    private static final long OPEN_ANIMATION_MILLIS = 160L;
    private static final long ROW_ANIMATION_MILLIS = 120L;
    private static final long ROW_STAGGER_MILLIS = 11L;
    private static final long CLOSE_ANIMATION_MILLIS = 100L;
    private static final long SELECTION_PULSE_MILLIS = 150L;

    // Cobblemon's summary and party screens use neutral grey panels with a
    // one-pixel light edge and a dark outer border. Keep these colours local so
    // the menu remains usable even when Cobblemon itself is not installed.
    private static final int SHADOW_COLOR = 0x99000000;
    private static final int PANEL_COLOR = 0xF01D2630;
    private static final int PANEL_DARK_COLOR = 0xFF10171E;
    private static final int PANEL_LIGHT_COLOR = 0xFF34444F;
    private static final int ROW_COLOR = 0x001D2630;
    private static final int ROW_HOVER_COLOR = 0xB0374650;
    private static final int ROW_SELECTED_COLOR = 0xFFF0F3F5;
    private static final int ROW_SELECTED_INNER_COLOR = 0xFFD9E0E5;
    private static final int ROW_DISABLED_COLOR = 0xD0374148;
    private static final int ROW_DISABLED_BORDER_COLOR = 0xFF59636A;
    private static final int DISABLED_TEXT_COLOR = 0xFF858D92;
    private static final int DISABLED_ICON_COLOR = 0xFF30383E;
    private static final int PRIMARY_TEXT_COLOR = 0xFFF4F4F4;
    private static final int SECONDARY_TEXT_COLOR = 0xFFD0D0D0;
    private static final int MUTED_TEXT_COLOR = 0xFFA6A6A6;
    private static final int SELECTED_TEXT_COLOR = 0xFF303030;
    private static final int CONNECTED_COLOR = 0xFF5EE4E4;
    private static final int PENDING_COLOR = 0xFF8D8D8D;
    private static final int SEPARATOR_COLOR = 0x553F505B;
    private static final int OVERVIEW_HEIGHT = 110;
    private static final int PARTY_ICON_SIZE = 24;
    private static final int PARTY_ICON_GAP = 3;

    private final List<MenuRow> rows = new ArrayList<>();
    private final List<ModelWidget> partyModels = new ArrayList<>();
    private final List<Pokemon> partyPokemon = new ArrayList<>();
    private final MenuTheme menuTheme;
    private int selectedIndex;
    private int menuX;
    private int menuY;
    private int menuWidth;
    private int menuHeight;
    private int rowHeight;
    private int infoX;
    private int infoWidth;
    private Component statusMessage;
    private FieldMoveToggleButton rockClimbToggleButton;
    private FieldMoveToggleButton flashToggleButton;
    private FieldMoveToggleButton strengthToggleButton;
    private FieldMoveToggleButton rockSmashToggleButton;
    private long transitionStartedAt;
    private long selectionChangedAt;
    private boolean closing;

    public PlayerMenuScreen() {
        super(Component.translatable("screen.cobbleventure_player_menu.title"));
        menuTheme = MenuTheme.load(net.minecraft.client.Minecraft.getInstance());
        BagNetwork.requestSnapshot();
    }

    @Override
    protected void init() {
        super.init();
        rows.clear();
        partyModels.clear();
        partyPokemon.clear();
        transitionStartedAt = System.currentTimeMillis();
        selectionChangedAt = 0L;
        closing = false;
        playUiSound(CobblemonSounds.PC_ON, 1.0F, 0.55F);

        PlayerMenuEntry[] entries = PlayerMenuEntry.values();
        selectedIndex = clamp(selectedIndex, 0, entries.length - 1);

        menuWidth = clamp(width / 4, 172, 208);
        rowHeight = clamp(
            (height - MENU_MARGIN * 2 - MENU_HEADER_HEIGHT - MENU_PADDING * 2
                - ROW_GAP * (entries.length - 1)) / entries.length,
            19,
            27
        );
        menuHeight = MENU_HEADER_HEIGHT + MENU_PADDING * 2
            + rowHeight * entries.length + ROW_GAP * (entries.length - 1);
        menuX = width - menuWidth - MENU_MARGIN;
        menuY = (height - menuHeight) / 2;
        infoWidth = clamp(width / 5, 180, 240);
        infoX = Math.max(MENU_MARGIN, menuX - PANEL_GAP - infoWidth);
        infoWidth = Math.max(112, menuX - PANEL_GAP - infoX);

        int rowX = menuX + MENU_PADDING;
        int rowY = menuY + MENU_HEADER_HEIGHT + MENU_PADDING;
        int rowWidth = menuWidth - MENU_PADDING * 2;
        for (int index = 0; index < entries.length; index++) {
            MenuRow row = new MenuRow(
                index,
                entries[index],
                rowX,
                rowY + index * (rowHeight + ROW_GAP),
                rowWidth,
                rowHeight
            );
            addRenderableWidget(row);
            rows.add(row);
        }
        PlayerOverviewNetwork.requestSnapshot();
        ProgressionNetwork.requestSnapshot();
        initPartyModels();
        int fieldMoveToggleWidth = Math.max(30, (infoWidth - 26) / 4);
        rockClimbToggleButton = addRenderableWidget(new FieldMoveToggleButton(
            "rock_climb", infoX + 8, trainerPanelY() + 89, fieldMoveToggleWidth, 17
        ));
        rockClimbToggleButton.visible = false;
        flashToggleButton = addRenderableWidget(new FieldMoveToggleButton(
            "flash", infoX + 10 + fieldMoveToggleWidth, trainerPanelY() + 89,
            fieldMoveToggleWidth, 17
        ));
        flashToggleButton.visible = false;
        strengthToggleButton = addRenderableWidget(new FieldMoveToggleButton(
            "strength", infoX + 12 + fieldMoveToggleWidth * 2, trainerPanelY() + 89,
            fieldMoveToggleWidth, 17
        ));
        strengthToggleButton.visible = false;
        rockSmashToggleButton = addRenderableWidget(new FieldMoveToggleButton(
            "rock_smash", infoX + 14 + fieldMoveToggleWidth * 3, trainerPanelY() + 89,
            fieldMoveToggleWidth, 17
        ));
        rockSmashToggleButton.visible = false;
        setFocused(rows.get(selectedIndex));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float transition = transitionProgress();
        if (!closing && openingFinished()) {
            for (MenuRow row : rows) {
                if (row.isMouseOver(mouseX, mouseY)) {
                    select(row.index());
                    break;
                }
            }
        }

        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, transition);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate((1.0F - transition) * 16.0F, 0.0F, 0.0F);
            renderTrainerPanel(graphics);
            renderInfoPanel(graphics);
            renderMenuPanel(graphics);
            super.render(graphics, mouseX, mouseY, partialTick);
            renderPartyTooltip(graphics, mouseX, mouseY);
            renderControls(graphics);
        } finally {
            graphics.pose().popPose();
            graphics.flush();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (closing && System.currentTimeMillis() - transitionStartedAt >= CLOSE_ANIMATION_MILLIS
            && minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closing || !openingFinished()) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally empty: the compact panels provide their own contrast and
        // the field must remain visible without Minecraft's fullscreen blur.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closing || !openingFinished()) return true;
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        PlayerMenuEntry shortcutEntry = PlayerMenuKeyMappings.matchingEntry(keyCode, scanCode);
        if (shortcutEntry != null) {
            select(shortcutEntry.ordinal());
            activate(shortcutEntry);
            return true;
        }

        int numberIndex = keyCode - GLFW.GLFW_KEY_1;
        if (numberIndex >= 0 && numberIndex < PlayerMenuEntry.values().length) {
            select(numberIndex);
            activateSelected();
            return true;
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT -> moveSelection(-1);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT -> moveSelection(1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> activateSelected();
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        transitionStartedAt = System.currentTimeMillis();
        playUiSound(CobblemonSounds.PC_OFF, 1.0F, 0.48F);
    }

    private void renderTrainerPanel(GuiGraphics graphics) {
        int x = infoX;
        int y = trainerPanelY();
        drawRibbonPanel(graphics, x, y, infoWidth, OVERVIEW_HEIGHT);

        String playerName = minecraft != null && minecraft.player != null
            ? minecraft.player.getGameProfile().getName()
            : Component.translatable("screen.cobbleventure_player_menu.header.adventurer").getString();
        Component playerLine = Component.translatable(
            "screen.cobbleventure_player_menu.header.player",
            playerName
        );
        graphics.drawString(font, playerLine, x + 8, y + 7, menuTheme.textColor, false);

        int partySize = partySize();
        Component summary = partySize >= 0
            ? Component.translatable("screen.cobbleventure_player_menu.header.party", partySize, 6)
            : Component.translatable("screen.cobbleventure_player_menu.header.party_unavailable");
        graphics.drawString(font, summary, x + 8, y + 22, menuTheme.accent, false);

        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.header.field_moves"),
            x + 8, y + 66, MUTED_TEXT_COLOR, false
        );
        List<String> moves = PlayerOverviewNetwork.clientFieldMoves();
        boolean rockClimbOwned = moves.contains("rock_climb");
        boolean flashOwned = moves.contains("flash");
        boolean strengthOwned = moves.contains("strength");
        boolean rockSmashOwned = moves.contains("rock_smash");
        if (rockClimbToggleButton != null) {
            rockClimbToggleButton.visible = rockClimbOwned;
            rockClimbToggleButton.setMessage(fieldMoveToggleLabel(
                "rock_climb", PlayerOverviewNetwork.isActive("rock_climb")
            ));
        }
        if (flashToggleButton != null) {
            flashToggleButton.visible = flashOwned;
            flashToggleButton.setMessage(fieldMoveToggleLabel(
                "flash", PlayerOverviewNetwork.isActive("flash")
            ));
        }
        if (strengthToggleButton != null) {
            strengthToggleButton.visible = strengthOwned;
            strengthToggleButton.setMessage(fieldMoveToggleLabel(
                "strength", PlayerOverviewNetwork.isActive("strength")
            ));
        }
        if (rockSmashToggleButton != null) {
            rockSmashToggleButton.visible = rockSmashOwned;
            rockSmashToggleButton.setMessage(fieldMoveToggleLabel(
                "rock_smash", PlayerOverviewNetwork.isActive("rock_smash")
            ));
        }
        Component moveSummary = moves.isEmpty()
            ? Component.translatable("screen.cobbleventure_player_menu.header.field_moves.empty")
            : Component.literal(String.join(" · ", moves.stream().map(PlayerMenuScreen::fieldMoveName).toList()));
        List<FormattedCharSequence> lines = font.split(moveSummary, infoWidth - 16);
        int maximumLines = rockClimbOwned || flashOwned || strengthOwned || rockSmashOwned ? 1 : 2;
        for (int index = 0; index < Math.min(maximumLines, lines.size()); index++) {
            graphics.drawString(font, lines.get(index), x + 8, y + 79 + index * 11, menuTheme.textColor, false);
        }
    }

    private void renderInfoPanel(GuiGraphics graphics) {
        int panelHeight = 96;
        int x = infoX;
        int y = trainerPanelY() + OVERVIEW_HEIGHT + PANEL_GAP;
        drawRibbonPanel(graphics, x, y, infoWidth, panelHeight);

        if (!rows.isEmpty()) {
            MenuRow selectedRow = rows.get(clamp(selectedIndex, 0, rows.size() - 1));
            int startX = x + infoWidth;
            int startY = y + panelHeight / 2;
            int targetX = selectedRow.getX() - 9;
            int targetY = selectedRow.getY() + selectedRow.getHeight() / 2;
            if (targetX > startX) {
                int bendX = startX + Math.max(2, (targetX - startX) / 2);
                graphics.fill(startX, startY, bendX + 1, startY + 1, menuTheme.accent);
                graphics.fill(bendX, Math.min(startY, targetY), bendX + 1, Math.max(startY, targetY) + 1, menuTheme.accent);
                graphics.fill(bendX, targetY, targetX + 1, targetY + 1, menuTheme.accent);
                fillCircle(graphics, startX, startY, 2, menuTheme.accent);
            }
        }

        PlayerMenuEntry entry = PlayerMenuEntry.values()[selectedIndex];
        renderIcon(graphics, entry.icon(), x + 17, y + 11, 1.5F, entry.enabled());
        graphics.drawString(font, entry.title(), x + 39, y + 10, menuTheme.textColor, false);

        Component availability = Component.translatable(
            entry.connected()
                ? "screen.cobbleventure_player_menu.status.available"
                : "screen.cobbleventure_player_menu.status.pending"
        );
        graphics.drawString(
            font,
            availability,
            x + 39,
            y + 24,
            entry.connected() ? menuTheme.accent : MUTED_TEXT_COLOR,
            false
        );

        Component shortcut = Component.translatable(
            "screen.cobbleventure_player_menu.shortcut",
            PlayerMenuKeyMappings.keyName(entry)
        );
        graphics.drawString(font, shortcut, x + 39, y + 36, menuTheme.accent, false);

        Component detail = statusMessage != null ? statusMessage : entry.description();
        List<FormattedCharSequence> lines = font.split(detail, infoWidth - 16);
        int textY = y + 52;
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            graphics.drawString(
                font,
                lines.get(index),
                x + 8,
                textY + index * 11,
                menuTheme.textColor,
                false
            );
        }
    }

    private void renderMenuPanel(GuiGraphics graphics) {
        drawRibbonPanel(graphics, menuX, menuY, menuWidth, menuHeight);
        graphics.drawString(font, title,
            menuX + (menuWidth - font.width(title)) / 2,
            menuY + 7, menuTheme.textColor, false);
        graphics.fill(
            menuX + 12,
            menuY + MENU_HEADER_HEIGHT - 2,
            menuX + menuWidth - 12,
            menuY + MENU_HEADER_HEIGHT - 1,
            menuTheme.innerBorder
        );
        graphics.fill(menuX + 12, menuY + 2, menuX + 36, menuY + 4, menuTheme.accent);
    }

    private void renderControls(GuiGraphics graphics) {
        int panelHeight = 35;
        int x = infoX;
        int y = Math.min(
            height - MENU_MARGIN - panelHeight,
            trainerPanelY() + OVERVIEW_HEIGHT + PANEL_GAP + 96 + PANEL_GAP
        );
        drawRibbonPanel(graphics, x, y, infoWidth, panelHeight);
        graphics.drawString(
            font,
            Component.translatable(
                "screen.cobbleventure_player_menu.controls.primary",
                minecraft == null ? Component.literal("E") : minecraft.options.keyInventory.getTranslatedKeyMessage()
            ),
            x + 8,
            y + 7,
            menuTheme.textColor,
            false
        );
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.controls.secondary"),
            x + 8,
            y + 20,
            MUTED_TEXT_COLOR,
            false
        );
    }

    private void moveSelection(int delta) {
        select(Math.floorMod(selectedIndex + delta, PlayerMenuEntry.values().length));
    }

    private void select(int index) {
        int nextIndex = clamp(index, 0, PlayerMenuEntry.values().length - 1);
        if (nextIndex == selectedIndex) {
            return;
        }
        selectedIndex = nextIndex;
        statusMessage = null;
        selectionChangedAt = System.currentTimeMillis();
        playUiSound(CobblemonSounds.POKEDEX_CLICK_SHORT, 1.08F, 0.18F);
        if (selectedIndex < rows.size()) {
            setFocused(rows.get(selectedIndex));
        }
    }

    private void activateSelected() {
        activate(PlayerMenuEntry.values()[selectedIndex]);
    }

    private void activate(PlayerMenuEntry entry) {
        playUiSound(CobblemonSounds.PC_CLICK, 1.0F, 0.38F);
        statusMessage = switch (entry.open()) {
            case OPENED -> statusMessage;
            case NO_POKEMON -> Component.translatable(
                "screen.cobbleventure_player_menu.status.no_pokemon"
            );
            case MISSING_POKEDEX -> Component.translatable(
                "screen.cobbleventure_player_menu.status.missing_pokedex"
            );
            case MISSING_POKENAV -> Component.translatable(
                "screen.cobbleventure_player_menu.status.missing_pokenav"
            );
            case ACTION_FAILED -> Component.translatable(
                "screen.cobbleventure_player_menu.status.action_failed"
            );
            case LOCKED -> Component.translatable(
                "screen.cobbleventure_player_menu.status.locked", entry.title()
            );
            case UNAVAILABLE -> Component.translatable(
                "screen.cobbleventure_player_menu.status.coming_soon",
                entry.title()
            );
        };
    }

    private static int partySize() {
        if (!ModList.get().isLoaded("cobblemon")) {
            return -1;
        }
        return CobblemonMenuIntegration.partySize();
    }

    private int trainerPanelY() {
        return Math.max(MENU_MARGIN, menuY);
    }

    private void initPartyModels() {
        if (!ModList.get().isLoaded("cobblemon")) return;
        partyPokemon.addAll(CobblemonMenuIntegration.partyPokemon());
        int totalWidth = 6 * PARTY_ICON_SIZE + 5 * PARTY_ICON_GAP;
        int startX = infoX + Math.max(7, (infoWidth - totalWidth) / 2);
        int modelY = trainerPanelY() + 34;
        for (int index = 0; index < partyPokemon.size(); index++) {
            ModelWidget model = CobblemonModelWidgetCompat.create(
                startX + index * (PARTY_ICON_SIZE + PARTY_ICON_GAP), modelY,
                PARTY_ICON_SIZE, PARTY_ICON_SIZE, partyPokemon.get(index).asRenderablePokemon(),
                0.65F, 25.0F, 0.0D, false, false
            );
            model.active = false;
            partyModels.add(addRenderableWidget(model));
        }
    }

    private void renderPartyTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int index = 0; index < partyModels.size(); index++) {
            ModelWidget model = partyModels.get(index);
            if (mouseX >= model.getX() && mouseX < model.getX() + PARTY_ICON_SIZE
                && mouseY >= model.getY() && mouseY < model.getY() + PARTY_ICON_SIZE) {
                graphics.renderTooltip(font, partyPokemon.get(index).getDisplayName(false), mouseX, mouseY);
                return;
            }
        }
    }

    private static String fieldMoveName(String move) {
        return Component.translatable("field_move.cobbleventure_player_menu." + move).getString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private boolean openingFinished() {
        long total = Math.max(
            OPEN_ANIMATION_MILLIS,
            ROW_ANIMATION_MILLIS
                + Math.max(0, PlayerMenuEntry.values().length - 1) * ROW_STAGGER_MILLIS
        );
        return !closing && System.currentTimeMillis() - transitionStartedAt >= total;
    }

    private float transitionProgress() {
        long duration = closing ? CLOSE_ANIMATION_MILLIS : OPEN_ANIMATION_MILLIS;
        float linear = clamp01((System.currentTimeMillis() - transitionStartedAt) / (float) duration);
        return closing ? 1.0F - easeInOutCubic(linear) : easeOutCubic(linear);
    }

    private float rowTransitionProgress(int index) {
        if (closing) return transitionProgress();
        long elapsed = System.currentTimeMillis() - transitionStartedAt - index * ROW_STAGGER_MILLIS;
        return easeOutCubic(clamp01(elapsed / (float) ROW_ANIMATION_MILLIS));
    }

    private float selectionPulse(int index) {
        if (index != selectedIndex || selectionChangedAt == 0L) return 1.0F;
        float progress = clamp01((System.currentTimeMillis() - selectionChangedAt) / (float) SELECTION_PULSE_MILLIS);
        return 1.0F + (float) Math.sin(progress * Math.PI) * 0.08F;
    }

    private void playUiSound(SoundEvent sound, float pitch, float volume) {
        if (minecraft == null) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private static float easeInOutCubic(float value) {
        if (value < 0.5F) {
            return 4.0F * value * value * value;
        }
        float inverse = -2.0F * value + 2.0F;
        return 1.0F - inverse * inverse * inverse / 2.0F;
    }

    private static int blendColor(int from, int to, float amount) {
        float clamped = clamp01(amount);
        int alpha = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * clamped);
        int red = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * clamped);
        int green = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * clamped);
        int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * clamped);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private void drawRibbonPanel(
        GuiGraphics graphics,
        int x,
        int y,
        int panelWidth,
        int panelHeight
    ) {
        int radius = menuTheme.cornerRadius;
        int shadowOffset = menuTheme.shadowOffset;
        if (shadowOffset > 0) {
            fillRoundedRect(graphics, x + shadowOffset, y + shadowOffset,
                x + panelWidth + shadowOffset, y + panelHeight + shadowOffset,
                radius, menuTheme.shadow);
        }
        fillRoundedRect(graphics, x, y, x + panelWidth, y + panelHeight, radius, menuTheme.border);
        fillRoundedRect(graphics, x + 2, y + 2, x + panelWidth - 2, y + panelHeight - 2,
            Math.max(0, radius - 2), menuTheme.innerBorder);
        fillRoundedRect(graphics, x + 4, y + 4, x + panelWidth - 4, y + panelHeight - 4,
            Math.max(0, radius - 4), menuTheme.background);
        graphics.fill(x + panelWidth - 14, y + panelHeight - 4,
            x + panelWidth - 5, y + panelHeight - 2, menuTheme.accent);
    }

    private static void fillRoundedRect(
        GuiGraphics graphics,
        int left,
        int top,
        int right,
        int bottom,
        int radius,
        int color
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

    private static void fillCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        if (radius <= 0) return;
        for (int row = 0; row < radius * 2; row++) {
            double offsetY = row + 0.5D - radius;
            double halfWidth = Math.sqrt(Math.max(0.0D, radius * radius - offsetY * offsetY));
            int left = (int)Math.ceil(centerX - halfWidth - 0.5D);
            int right = (int)Math.floor(centerX + halfWidth - 0.5D) + 1;
            graphics.fill(left, centerY - radius + row, right, centerY - radius + row + 1, color);
        }
    }

    private final class FieldMoveToggleButton extends AbstractButton {
        private final String move;

        private FieldMoveToggleButton(String move, int x, int y, int width, int height) {
            super(x, y, width, height, fieldMoveToggleLabel(move, false));
            this.move = move;
        }

        @Override
        public void onPress() {
            PlayerOverviewNetwork.requestToggle(move);
            playUiSound(CobblemonSounds.POKEDEX_CLICK_SHORT, 1.0F, 0.25F);
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            boolean toggled = PlayerOverviewNetwork.isActive(move);
            setMessage(fieldMoveToggleLabel(move, toggled));
            int radius = Math.min(menuTheme.rowRadius, getHeight() / 2);
            int border = toggled || isHovered() ? menuTheme.accent : menuTheme.border;
            int background = toggled ? menuTheme.selectedBackground
                : isHovered() ? menuTheme.hoverBackground : menuTheme.background;
            int text = toggled ? menuTheme.selectedTextColor : menuTheme.textColor;
            fillRoundedRect(
                graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                radius, border
            );
            fillRoundedRect(
                graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, radius - 1), background
            );
            if (toggled) {
                graphics.fill(
                    getX() + 7, getY() + getHeight() - 2,
                    getX() + getWidth() - 7, getY() + getHeight() - 1,
                    menuTheme.accent
                );
            }
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8);
            graphics.drawString(
                font, label,
                getX() + (getWidth() - font.width(label)) / 2,
                getY() + (getHeight() - 8) / 2,
                text, false
            );
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static Component fieldMoveToggleLabel(String move, boolean toggled) {
        return Component.translatable(
            "screen.cobbleventure_player_menu.field_move." + move + "_toggle",
            toggled ? "ON" : "OFF"
        );
    }

    private final class MenuRow extends AbstractButton {
        private final int index;
        private final PlayerMenuEntry entry;
        private final ItemStack icon;
        private float hoverProgress;
        private long lastAnimationAt = System.currentTimeMillis();

        private MenuRow(
            int index,
            PlayerMenuEntry entry,
            int x,
            int y,
            int rowWidth,
            int rowHeight
        ) {
            super(x, y, rowWidth, rowHeight, entry.title());
            this.index = index;
            this.entry = entry;
            this.icon = entry.icon();
        }

        int index() {
            return index;
        }

        @Override
        public void onPress() {
            select(index);
            activate(entry);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean enabled = entry.enabled();
            active = enabled;
            boolean selected = index == selectedIndex && enabled;
            long now = System.currentTimeMillis();
            float step = Math.min(1.0F, (now - lastAnimationAt) / 80.0F);
            lastAnimationAt = now;
            float hoverTarget = enabled && isHovered() && !selected ? 1.0F : 0.0F;
            hoverProgress += (hoverTarget - hoverProgress) * step;
            int fillColor = !enabled ? ROW_DISABLED_COLOR
                : selected ? menuTheme.selectedBackground
                : blendColor(0x00FFFFFF, menuTheme.hoverBackground, hoverProgress);
            int textColor = !enabled ? DISABLED_TEXT_COLOR
                : selected ? menuTheme.selectedTextColor : menuTheme.textColor;

            float rowTransition = rowTransitionProgress(index);
            graphics.pose().pushPose();
            graphics.pose().translate((1.0F - rowTransition) * 10.0F, 0.0F, 0.0F);

            int centerY = getY() + getHeight() / 2;
            int visualLeft = selected ? getX() - 8 : getX();
            int visualRight = getX() + getWidth() - 3;
            if (!enabled) {
                fillRoundedRect(graphics, visualLeft, getY(), visualRight, getY() + getHeight(),
                    getHeight() / 2, ROW_DISABLED_BORDER_COLOR);
                fillRoundedRect(graphics, visualLeft + 1, getY() + 1, visualRight - 1,
                    getY() + getHeight() - 1, Math.max(1, getHeight() / 2 - 1), fillColor);
            } else if (selected) {
                fillRoundedRect(graphics, visualLeft, getY(), visualRight, getY() + getHeight(),
                    menuTheme.rowRadius, menuTheme.accent);
                fillRoundedRect(graphics, visualLeft + 1, getY() + 1, visualRight - 1,
                    getY() + getHeight() - 1, Math.max(0, menuTheme.rowRadius - 1), fillColor);
                graphics.fill(visualRight - 8, centerY - 1, visualRight + 8, centerY + 1, menuTheme.accent);
            } else if (hoverProgress > 0.01F) {
                fillRoundedRect(graphics, visualLeft, getY(), visualRight, getY() + getHeight(),
                    menuTheme.rowRadius, fillColor);
            }
            graphics.fill(getX() + 28, getY() + getHeight() - 1,
                getX() + getWidth() - 14, getY() + getHeight(), SEPARATOR_COLOR);

            float iconScale = selectionPulse(index);
            int iconCenterX = selected ? getX() + 14 : getX() + 18;
            int iconRadius = selected ? Math.min(11, getHeight() / 2 + 1) : Math.min(9, getHeight() / 2 - 1);
            fillCircle(graphics, iconCenterX, centerY, iconRadius + 1,
                !enabled ? ROW_DISABLED_BORDER_COLOR
                    : selected ? menuTheme.accent : menuTheme.innerBorder);
            fillCircle(graphics, iconCenterX, centerY, iconRadius,
                !enabled ? DISABLED_ICON_COLOR
                    : selected ? menuTheme.selectedBackground : menuTheme.background);
            float iconY = centerY - 8.0F * iconScale;
            renderIcon(graphics, icon, iconCenterX, Math.round(iconY), iconScale, enabled);

            String shortcut = PlayerMenuKeyMappings.keyName(entry).getString();
            String clippedShortcut = font.plainSubstrByWidth(shortcut, 30);
            int shortcutWidth = font.width(clippedShortcut);
            int shortcutX = getX() + getWidth() - 13 - shortcutWidth;
            graphics.drawString(font, clippedShortcut, shortcutX,
                getY() + (getHeight() - 8) / 2,
                !enabled ? DISABLED_TEXT_COLOR
                    : selected ? menuTheme.selectedTextColor : menuTheme.accent, false);

            int titleX = getX() + 34;
            int titleWidth = Math.max(12, shortcutX - titleX - 4);
            String clippedTitle = font.plainSubstrByWidth(entry.title().getString(), titleWidth);
            graphics.drawString(
                font,
                clippedTitle,
                titleX,
                getY() + (getHeight() - 8) / 2,
                textColor,
                false
            );

            int dotColor = !enabled ? ROW_DISABLED_BORDER_COLOR
                : entry.connected() ? CONNECTED_COLOR : PENDING_COLOR;
            int dotY = getY() + getHeight() / 2 - 2;
            graphics.fill(
                getX() + getWidth() - 9,
                dotY,
                getX() + getWidth() - 5,
                dotY + 4,
                dotColor
            );
            graphics.pose().popPose();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void renderIcon(
        GuiGraphics graphics,
        ItemStack icon,
        int centerX,
        int topY,
        float scale,
        boolean enabled
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - 8.0F * scale, topY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        if (!enabled) RenderSystem.setShaderColor(0.48F, 0.48F, 0.48F, 0.72F);
        graphics.renderItem(icon, 0, 0);
        if (!enabled) RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.pose().popPose();
    }
}
