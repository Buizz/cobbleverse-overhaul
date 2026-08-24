package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.buizz.cobbleventure.playermenu.StarterRouletteNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/** A continuously scrolling row of Cobblemon models with a server-validated stop result. */
public final class StarterRouletteScreen extends Screen {
    private static final int VISIBLE_WIDGET_COUNT = 7;
    private static final int WIDGET_COUNT = VISIBLE_WIDGET_COUNT + 1;
    private static final int CENTER_WIDGET = VISIBLE_WIDGET_COUNT / 2;
    private static final int MIN_MODEL_SIZE = 32;
    private static final int MAX_MODEL_SIZE = 96;
    private static final int HORIZONTAL_SAFE_INSET = 24;
    private static final int MODEL_TOP_INSET = 4;
    private static final int NAME_TOP_INSET = 22;
    private static final long STEP_MILLIS = 135L;
    private static final long STOP_DURATION_MILLIS = 1_200L;

    private final UUID token;
    private final List<String> speciesIds;
    private final List<ModelWidget> models = new ArrayList<>();
    private int centerIndex;
    private long stepStartedAt;
    private long stopStartedAt;
    private double stopStartPosition;
    private boolean stopping;
    private boolean stopped;
    private boolean waiting;
    private boolean received;
    private boolean cancelSent;
    private int modelSize;
    private int slotSpacing;
    private int frameY;
    private int nameY;
    private int statusY;
    private MenuTheme menuTheme;
    private Component status = Component.translatable("screen.cobbleventure_player_menu.starter.hint");
    private RouletteButton actionButton;

    public StarterRouletteScreen(UUID token, List<String> speciesIds) {
        super(Component.translatable("screen.cobbleventure_player_menu.starter.title"));
        this.token = token;
        this.speciesIds = List.copyOf(speciesIds);
    }

    @Override
    protected void init() {
        models.clear();
        menuTheme = MenuTheme.load(minecraft);
        int horizontalPadding = HORIZONTAL_SAFE_INSET * 2;
        int minimumGap = 4;
        int widthLimitedSize = Math.max(
            MIN_MODEL_SIZE,
            (width - horizontalPadding - minimumGap * (VISIBLE_WIDGET_COUNT - 1))
                / VISIBLE_WIDGET_COUNT
        );
        modelSize = Math.clamp(
            Math.min((height * 35) / 100, widthLimitedSize),
            MIN_MODEL_SIZE,
            MAX_MODEL_SIZE
        );
        int widthLimitedSpacing = Math.max(
            modelSize,
            (width - horizontalPadding - modelSize) / (VISIBLE_WIDGET_COUNT - 1)
        );
        slotSpacing = Math.min(modelSize + 16, widthLimitedSpacing);
        int contentHeight = modelSize + 90;
        int contentTop = Math.max(8, (height - contentHeight) / 2);
        int titleY = contentTop;
        frameY = titleY + 18;
        int modelY = frameY + MODEL_TOP_INSET;
        nameY = frameY + modelSize + NAME_TOP_INSET;
        statusY = nameY + 16;
        int buttonY = statusY + 16;
        float modelScale = StarterRouletteLayout.modelScaleForSize(modelSize, MAX_MODEL_SIZE);
        for (int index = 0; index < WIDGET_COUNT; index++) {
            ModelWidget widget = CobblemonModelWidgetCompat.create(
                0, modelY, modelSize, modelSize, renderable(relativeSpecies(relativeIndex(index))),
                modelScale, 25.0F, 0.0D, false, false
            );
            models.add(addRenderableWidget(widget));
        }
        stepStartedAt = System.currentTimeMillis();
        int buttonWidth = Math.min(120, width - 24);
        actionButton = addRenderableWidget(new RouletteButton(
            Component.translatable("screen.cobbleventure_player_menu.starter.stop"),
            width / 2 - buttonWidth / 2, buttonY, buttonWidth, 20
        ));
        positionModels(0.0D);
    }

    @Override
    public void tick() {
        if (stopped || speciesIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (stopping) {
            updatePosition(stoppingPosition(now));
            if (now - stopStartedAt >= STOP_DURATION_MILLIS) finishStopping();
            return;
        }
        while (now - stepStartedAt >= STEP_MILLIS) {
            centerIndex = Math.floorMod(centerIndex + 1, speciesIds.size());
            stepStartedAt += STEP_MILLIS;
            refreshModels();
            playRouletteTick();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101720);
        double progress;
        if (stopped) {
            progress = 0.0D;
        } else if (stopping) {
            progress = updatePosition(stoppingPosition(System.currentTimeMillis()));
        } else {
            progress = Math.min(1.0D, (System.currentTimeMillis() - stepStartedAt) / (double) STEP_MILLIS);
        }
        positionModels(progress);

        int frameX = width / 2 - modelSize / 2 - 8;
        int frameSize = modelSize + 16;
        ThemedOverlayPanel.draw(
            graphics, menuTheme, frameX, frameY, frameSize, frameSize,
            1.0F, menuTheme.accent
        );
        graphics.drawCenteredString(font, title, width / 2, Math.max(8, frameY - 18), 0xFFFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, statusY, received ? 0xFF8EF0A7 : 0xFFE8EDF2);

        int clipInset = 4;
        graphics.enableScissor(
            frameX + clipInset, frameY + clipInset,
            frameX + frameSize - clipInset, frameY + frameSize - clipInset
        );
        for (ModelWidget model : models) {
            model.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();
        actionButton.render(graphics, mouseX, mouseY, partialTick);
        Species centered = species(sequenceAt(centerIndex));
        if (centered != null) {
            graphics.drawCenteredString(font, centered.getTranslatedName(), width / 2, nameY, 0xFFFFFFFF);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible behind the roulette.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (!received && !cancelSent) {
            cancelSent = true;
            StarterRouletteNetwork.cancel(token);
        }
        super.onClose();
    }

    void handleResult(boolean success, String translationKey, String speciesId) {
        waiting = false;
        received = success;
        Species selected = species(speciesId);
        status = selected == null
            ? Component.translatable(translationKey)
            : Component.translatable(translationKey, selected.getTranslatedName());
        actionButton.setMessage(Component.translatable(success
            ? "screen.cobbleventure_player_menu.starter.close"
            : "screen.cobbleventure_player_menu.starter.retry"));
        actionButton.active = true;
    }

    private void pressAction() {
        if (received) {
            onClose();
            return;
        }
        if (stopped && !waiting) {
            onClose();
            return;
        }
        if (stopping || waiting || speciesIds.isEmpty()) return;

        long now = System.currentTimeMillis();
        double progress = Math.min(1.0D, (now - stepStartedAt) / (double) STEP_MILLIS);
        stopStartPosition = centerIndex + progress;
        stopStartedAt = now;
        stopping = true;
        status = Component.translatable("screen.cobbleventure_player_menu.starter.slowing");
        actionButton.active = false;
    }

    private double stoppingPosition(long now) {
        double elapsedRatio = Math.min(1.0D, Math.max(0.0D,
            (now - stopStartedAt) / (double) STOP_DURATION_MILLIS));
        double slowingDistance = STOP_DURATION_MILLIS / (double) STEP_MILLIS
            * (elapsedRatio - elapsedRatio * elapsedRatio * 0.5D);
        return stopStartPosition + slowingDistance;
    }

    private double updatePosition(double absolutePosition) {
        int wholeSlots = (int) Math.floor(absolutePosition);
        int nextCenterIndex = Math.floorMod(wholeSlots, speciesIds.size());
        if (nextCenterIndex != centerIndex) {
            centerIndex = nextCenterIndex;
            refreshModels();
            playRouletteTick();
        }
        return absolutePosition - wholeSlots;
    }

    private void finishStopping() {
        int selectedIndex = Math.floorMod((int) Math.floor(
            stoppingPosition(stopStartedAt + STOP_DURATION_MILLIS) + 0.5D
        ), speciesIds.size());
        centerIndex = selectedIndex;
        stopping = false;
        stopped = true;
        waiting = true;
        refreshModels();
        positionModels(0.0D);
        status = Component.translatable("screen.cobbleventure_player_menu.starter.waiting");
        StarterRouletteNetwork.claim(token, centerIndex);
    }

    private void refreshModels() {
        for (int index = 0; index < models.size(); index++) {
            models.get(index).setPokemon(renderable(relativeSpecies(relativeIndex(index))));
        }
    }

    private void positionModels(double progress) {
        int centerX = width / 2 - modelSize / 2;
        for (int index = 0; index < models.size(); index++) {
            int relative = relativeIndex(index);
            models.get(index).setX((int) Math.round(centerX + (relative - progress) * slotSpacing));
            models.get(index).active = false;
        }
    }

    private int relativeIndex(int widgetIndex) {
        return widgetIndex - CENTER_WIDGET;
    }

    private String sequenceAt(int index) {
        if (speciesIds.isEmpty()) return "cobblemon:bulbasaur";
        return speciesIds.get(Math.floorMod(index, speciesIds.size()));
    }

    private String relativeSpecies(int relativeIndex) {
        return sequenceAt(centerIndex + relativeIndex);
    }

    private Species species(String id) {
        if (id == null || id.isBlank()) return null;
        return PokemonSpecies.getByIdentifier(ResourceLocation.parse(id));
    }

    private RenderablePokemon renderable(String id) {
        Species value = species(id);
        if (value == null) value = PokemonSpecies.getByName("bulbasaur");
        return new RenderablePokemon(value, java.util.Set.of(), ItemStack.EMPTY);
    }

    private void playRouletteTick() {
        if (minecraft == null) return;
        minecraft.getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.35F, 0.28F)
        );
    }

    private final class RouletteButton extends AbstractButton {
        private RouletteButton(
            Component message, int x, int y, int width, int height
        ) {
            super(x, y, width, height, message);
        }

        @Override
        public void onPress() {
            pressAction();
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            int accent = active && isHovered() ? menuTheme.accent : menuTheme.border;
            ThemedOverlayPanel.draw(
                graphics, menuTheme, getX(), getY(), getWidth(), getHeight(),
                active ? 1.0F : 0.55F, accent
            );
            int textColor = active
                ? isHovered() ? menuTheme.selectedTextColor : menuTheme.textColor
                : ThemedOverlayPanel.withOpacity(menuTheme.textColor, 0.55F);
            graphics.drawCenteredString(
                font,
                font.plainSubstrByWidth(getMessage().getString(), getWidth() - 12),
                getX() + getWidth() / 2,
                getY() + (getHeight() - font.lineHeight) / 2,
                textColor
            );
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

}
