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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** A continuously scrolling row of Cobblemon models with a server-validated stop result. */
public final class StarterRouletteScreen extends Screen {
    private static final int WIDGET_COUNT = 7;
    private static final int CENTER_WIDGET = WIDGET_COUNT / 2;
    private static final int SLOT_SPACING = 112;
    private static final int MODEL_SIZE = 96;
    private static final long STEP_MILLIS = 135L;

    private final UUID token;
    private final List<String> speciesIds;
    private final List<ModelWidget> models = new ArrayList<>();
    private int centerIndex;
    private long stepStartedAt;
    private boolean stopped;
    private boolean waiting;
    private boolean received;
    private Component status = Component.translatable("screen.cobbleventure_player_menu.starter.hint");
    private Button actionButton;

    public StarterRouletteScreen(UUID token, List<String> speciesIds) {
        super(Component.translatable("screen.cobbleventure_player_menu.starter.title"));
        this.token = token;
        this.speciesIds = List.copyOf(speciesIds);
    }

    @Override
    protected void init() {
        models.clear();
        int modelY = height / 2 - 70;
        for (int index = 0; index < WIDGET_COUNT; index++) {
            ModelWidget widget = new ModelWidget(
                0, modelY, MODEL_SIZE, MODEL_SIZE, renderable(relativeSpecies(relativeIndex(index))),
                2.6F, 25.0F, 0.0D, false, false
            );
            models.add(addRenderableWidget(widget));
        }
        stepStartedAt = System.currentTimeMillis();
        actionButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.starter.stop"), button -> pressAction()
        ).bounds(width / 2 - 70, height / 2 + 76, 140, 24).build());
        positionModels(0.0D);
    }

    @Override
    public void tick() {
        if (stopped || speciesIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        while (now - stepStartedAt >= STEP_MILLIS) {
            centerIndex = Math.floorMod(centerIndex + 1, speciesIds.size());
            stepStartedAt += STEP_MILLIS;
            refreshModels();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xB0101720);
        double progress = stopped ? 0.0D : Math.min(1.0D, (System.currentTimeMillis() - stepStartedAt) / (double) STEP_MILLIS);
        positionModels(progress);

        int frameX = width / 2 - MODEL_SIZE / 2 - 8;
        int frameY = height / 2 - 78;
        graphics.fill(frameX, frameY, frameX + MODEL_SIZE + 16, frameY + MODEL_SIZE + 16, 0xD02A3543);
        graphics.fill(frameX, frameY, frameX + MODEL_SIZE + 16, frameY + 3, 0xFFFFC928);
        graphics.fill(frameX, frameY + MODEL_SIZE + 13, frameX + MODEL_SIZE + 16, frameY + MODEL_SIZE + 16, 0xFFFFC928);
        graphics.drawCenteredString(font, title, width / 2, Math.max(18, frameY - 42), 0xFFFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, frameY + MODEL_SIZE + 23, received ? 0xFF8EF0A7 : 0xFFE8EDF2);

        super.render(graphics, mouseX, mouseY, partialTick);
        Species centered = species(sequenceAt(centerIndex));
        if (centered != null) {
            graphics.drawCenteredString(font, centered.getTranslatedName(), width / 2, frameY + MODEL_SIZE - 2, 0xFFFFFFFF);
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

        double progress = Math.min(1.0D, (System.currentTimeMillis() - stepStartedAt) / (double) STEP_MILLIS);
        if (progress >= 0.5D) centerIndex = Math.floorMod(centerIndex + 1, speciesIds.size());
        stopped = true;
        waiting = true;
        refreshModels();
        positionModels(0.0D);
        status = Component.translatable("screen.cobbleventure_player_menu.starter.waiting");
        actionButton.active = false;
        StarterRouletteNetwork.claim(token, centerIndex);
    }

    private void refreshModels() {
        for (int index = 0; index < models.size(); index++) {
            models.get(index).setPokemon(renderable(relativeSpecies(relativeIndex(index))));
        }
    }

    private void positionModels(double progress) {
        int centerX = width / 2 - MODEL_SIZE / 2;
        for (int index = 0; index < models.size(); index++) {
            int relative = relativeIndex(index);
            models.get(index).setX((int) Math.round(centerX + (relative - progress) * SLOT_SPACING));
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
}
