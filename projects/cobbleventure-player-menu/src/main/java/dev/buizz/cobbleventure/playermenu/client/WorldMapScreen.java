package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.buizz.cobbleventure.playermenu.MapContent;
import dev.buizz.cobbleventure.playermenu.MapNetwork;
import dev.buizz.cobbleventure.playermenu.ProgressionNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/** Interactive hex world map backed by the same content used by world generation. */
public final class WorldMapScreen extends Screen {
    private static final int PAGE_BACKGROUND = 0x44000000;
    private static final int ROUTE_COLOR = 0xFFD92E73;
    private static final int ROUTE_OUTLINE = 0xFFFFF1BC;
    private static final int WATER_ROUTE_COLOR = 0xFF247FB3;
    private static final int TOWN_BORDER = 0xFFE85B57;
    private static final int CAVE_BORDER = 0xFF9AA8B0;
    private static final int CAVE_OPENING = 0xFF080B0E;
    private static final int FOREST_BORDER = 0xFF6FB66A;
    private static final int FOREST_OPENING = 0xFF17331E;
    private static final int SELECTED_BORDER = 0xFFFFFFFF;
    private static final int PLAYER_MARKER = 0xFFFFD34E;
    private static final int OTHER_PLAYER_MARKER = 0xFF53E1D4;
    private static final int PLAYER_POSITION_REFRESH_TICKS = 20;
    private static final int MAP_LABEL_BACKGROUND = 0xF5FFF4D6;
    private static final int MAP_LABEL_HOVER = 0xFFFFD87A;
    private static final int WARNING_TEXT = 0xFFF0A43B;
    private static final int MARGIN = 14;
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 32;
    private static final int PANEL_GAP = 9;
    private static final int POKEMON_ICON_SIZE = 16;
    private static final int POKEMON_ICON_GAP = 2;
    private static final int POKEMON_CELL_SIZE = POKEMON_ICON_SIZE + POKEMON_ICON_GAP;
    private static final int MAX_POKEMON_MODELS = 96;
    private static final int MAP_CONTENT_TOP_INSET = 22;
    private static final int MAP_CONTROL_SIZE = 16;
    private static final int MAP_RESET_WIDTH = 36;
    private static final int MAP_CONTROL_GAP = 2;

    private final Screen parent;
    private final String selectionToken;
    private final MenuTheme menuTheme;
    private final int SHADOW_COLOR;
    private final int PANEL_COLOR;
    private final int PANEL_DARK_COLOR;
    private final int PANEL_LIGHT_COLOR;
    private final int MAP_BACKGROUND;
    private final int INFO_BACKGROUND;
    private final int TEXT;
    private final int MUTED_TEXT;
    private final int ACCENT_COLOR;
    private final int SUCCESS_TEXT;
    private final MenuOpeningEffect openingEffect = new MenuOpeningEffect();
    private MapContent content = MapContent.instance();
    private MapContent.Hex selected = new MapContent.Hex(0, 0);
    private AbstractButton teleportButton;
    private AbstractButton previousGenerationButton;
    private AbstractButton nextGenerationButton;
    private AbstractButton zoomOutButton;
    private AbstractButton zoomInButton;
    private AbstractButton resetViewButton;
    private long stateRevision = -1L;
    private int playerPositionRefreshTicks = PLAYER_POSITION_REFRESH_TICKS;
    private long selectionRevision = -1L;
    private boolean selectionCompleted;
    private boolean selectionCancelled;
    private int pokemonScroll;
    private String encounterMethod;
    private MapContent.Hex encounterSelection;
    private int encounterGeneration;
    private AbstractButton encounterMethodButton;
    private int zoomLevel;
    private int panX;
    private int panY;
    private boolean generationInitialized;
    private final List<ModelWidget> pokemonModels = new ArrayList<>();
    private final List<String> pokemonModelIds = new ArrayList<>();
    private final List<PokemonHover> pokemonHovers = new ArrayList<>();

    public WorldMapScreen(Screen parent) {
        this(parent, null);
    }

    public WorldMapScreen(Screen parent, String selectionToken) {
        super(Component.translatable("screen.cobbleventure_player_menu.world_map.title"));
        this.parent = parent;
        this.selectionToken = selectionToken;
        this.menuTheme = MenuTheme.load(net.minecraft.client.Minecraft.getInstance());
        // The world map keeps global sizing while using a cartographic palette:
        // teal water, green land, warm routes, and cream information cards.
        SHADOW_COLOR = 0x7A153B46;
        PANEL_COLOR = 0xF5FFF4D6;
        PANEL_DARK_COLOR = 0xFF286B7A;
        PANEL_LIGHT_COLOR = 0xFFFFD36A;
        MAP_BACKGROUND = 0xF22AAFC0;
        INFO_BACKGROUND = 0xF5FFF4D6;
        TEXT = 0xFF17343D;
        MUTED_TEXT = 0xFF49666C;
        ACCENT_COLOR = 0xFFE96D45;
        SUCCESS_TEXT = 0xFF277A4B;
    }

    @Override
    protected void init() {
        super.init();
        if (minecraft != null && !minecraft.getMainRenderTarget().isStencilEnabled()) {
            minecraft.getMainRenderTarget().enableStencil();
        }
        openingEffect.start(minecraft, CobblemonSounds.POKEDEX_CLICK_SHORT, 0.9F, 0.28F);
        if (!generationInitialized) {
            selectPlayerGeneration();
            generationInitialized = true;
        }
        selected = playerHex();
        Layout layout = layout();
        int generationHeaderWidth = generationHeaderWidth();
        int generationControlsLeft = (width - generationHeaderWidth - 46) / 2;
        previousGenerationButton = addRenderableWidget(new RibbonButton(
            Component.literal("<"), generationControlsLeft, 5, 20, 20,
            () -> switchGeneration(-1)));
        nextGenerationButton = addRenderableWidget(new RibbonButton(
            Component.literal(">"), generationControlsLeft + generationHeaderWidth + 26, 5, 20, 20,
            () -> switchGeneration(1)));
        int mapControlsRight = layout.mapRight() - 7;
        int mapControlsY = layout.top() + 5;
        int resetX = mapControlsRight - MAP_RESET_WIDTH;
        int zoomOutX = resetX - MAP_CONTROL_GAP - MAP_CONTROL_SIZE;
        int zoomInX = zoomOutX - MAP_CONTROL_GAP - MAP_CONTROL_SIZE;
        zoomOutButton = addRenderableWidget(new RibbonButton(
            Component.literal("−"), zoomOutX, mapControlsY, MAP_CONTROL_SIZE, MAP_CONTROL_SIZE,
            () -> changeZoom(-1, layout.mapCenterX(), layout.mapCenterY())));
        resetViewButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.world_map.reset_view"),
            resetX, mapControlsY, MAP_RESET_WIDTH, MAP_CONTROL_SIZE, this::resetView));
        zoomInButton = addRenderableWidget(new RibbonButton(
            Component.literal("+"), zoomInX, mapControlsY, MAP_CONTROL_SIZE, MAP_CONTROL_SIZE,
            () -> changeZoom(1, layout.mapCenterX(), layout.mapCenterY())));
        teleportButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.world_map.teleport"),
            layout.infoLeft() + 9, layout.bottom() - 28, layout.infoWidth() - 18, 20,
            this::requestDestination));
        encounterMethodButton = addRenderableWidget(new ThemedButton(
            menuTheme, Component.empty(), layout.infoLeft() + 10, layout.top(),
            layout.infoWidth() - 20, 18, MenuTheme.ButtonVariant.SECONDARY, () -> {
                MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());
                List<String> methods = content.encounterMethods(tile);
                encounterMethod = methods.get((methods.indexOf(selectedEncounterMethod(tile)) + 1) % methods.size());
                pokemonScroll = 0;
            }));
        encounterMethodButton.visible = false;
        Component closeLabel = Component.translatable(
            parent == null
                ? "screen.cobbleventure_player_menu.world_map.close"
                : "screen.cobbleventure_player_menu.world_map.back"
        );
        addRenderableWidget(new RibbonButton(closeLabel,
            width - MARGIN - 72, height - FOOTER_HEIGHT + 5, 72, 20, this::onClose));
        initPokemonModels(layout);
        MapNetwork.requestSnapshot();
        playerPositionRefreshTicks = PLAYER_POSITION_REFRESH_TICKS;
        updateNavigationButtons();
        updateTeleportButton();
    }

    @Override
    public void tick() {
        super.tick();
        if (--playerPositionRefreshTicks <= 0) {
            playerPositionRefreshTicks = PLAYER_POSITION_REFRESH_TICKS;
            MapNetwork.requestSnapshot();
        }
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        if (snapshot.revision() != stateRevision) {
            stateRevision = snapshot.revision();
            if (snapshot.teleportSucceeded() && minecraft != null) {
                minecraft.setScreen(null);
                return;
            }
            updateTeleportButton();
        }
        if (selectionToken != null) {
            MapNetwork.SelectionSnapshot selection = MapNetwork.selectionSnapshot();
            if (selection.revision() != selectionRevision
                && selectionToken.equals(selection.token())) {
                selectionRevision = selection.revision();
                selectionCompleted = selection.accepted();
                if (selectionCompleted && minecraft != null) {
                    minecraft.setScreen(parent);
                    return;
                }
                updateTeleportButton();
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        openingEffect.begin(graphics, width, height);
        try {
            graphics.fill(0, 0, width, height, PAGE_BACKGROUND);
            Component generationHeader = generationHeader();
            int generationHeaderWidth = generationHeaderWidth();
            int generationHeaderLeft = (width - generationHeaderWidth) / 2;
            drawRibbonPanel(graphics, generationHeaderLeft, 4, generationHeaderWidth, 23, PANEL_COLOR);
            graphics.fill(generationHeaderLeft + 12, 5,
                generationHeaderLeft + 42, 7, ACCENT_COLOR);
            drawCenteredNoShadow(graphics, generationHeader, width / 2, 11, TEXT);
            Layout layout = layout();
            MapContent.CaveEntrance hoveredCave = caveAtMouse(layout, mouseX, mouseY);
            MapContent.ForestEntrance hoveredForest = hoveredCave == null
                ? forestAtMouse(layout, mouseX, mouseY) : null;
            drawMap(graphics, layout, mouseX, mouseY, hoveredCave, hoveredForest);
            drawInfoPanel(graphics, layout, hoveredCave, hoveredForest);
            graphics.drawString(
                font,
                Component.translatable("screen.cobbleventure_player_menu.world_map.hint"),
                MARGIN,
                height - FOOTER_HEIGHT + 10,
                MUTED_TEXT,
                false
            );
            super.render(graphics, mouseX, mouseY, partialTick);
            if (openingEffect.finished()) {
                for (PokemonHover hover : pokemonHovers) {
                    if (hover.contains(mouseX, mouseY)) {
                        graphics.renderTooltip(font, Component.literal(hover.name()), mouseX, mouseY);
                        break;
                    }
                }
            }
        } finally {
            openingEffect.end(graphics);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!openingEffect.finished()) return true;
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        Layout layout = layout();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && layout.mapContains(mouseX, mouseY)) {
            MapContent.Hex candidate = screenToHex(layout, mouseX, mouseY);
            if (isPopulated(candidate.q(), candidate.r())) {
                selected = candidate;
                pokemonScroll = 0;
                updateTeleportButton();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!openingEffect.finished()) return true;
        MapContent.Hex next = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> new MapContent.Hex(selected.q() - 1, selected.r());
            case GLFW.GLFW_KEY_RIGHT -> new MapContent.Hex(selected.q() + 1, selected.r());
            case GLFW.GLFW_KEY_UP -> new MapContent.Hex(selected.q(), selected.r() - 1);
            case GLFW.GLFW_KEY_DOWN -> new MapContent.Hex(selected.q(), selected.r() + 1);
            default -> null;
        };
        if (next != null && isPopulated(next.q(), next.r())) {
            selected = next;
            pokemonScroll = 0;
            updateTeleportButton();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!openingEffect.finished()) return true;
        Layout layout = layout();
        if (layout.mapContains(mouseX, mouseY) && scrollY != 0.0D) {
            changeZoom(scrollY > 0.0D ? 1 : -1, mouseX, mouseY);
            return true;
        }
        if (mouseX >= layout.infoLeft() && mouseX < layout.infoRight()
            && mouseY >= layout.top() && mouseY < layout.bottom()
            && content.townAt(selected.q(), selected.r()) == null
            && content.objectAt(selected.q(), selected.r()) == null) {
            MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());
            if (tile != null) {
                int count = content.encounterBiome(tile, selectedEncounterMethod(tile)).pokemon().size();
                int columns = pokemonColumns(layout.infoWidth() - 20);
                int maximum = maxPokemonScroll(count, columns);
                int direction = scrollY > 0.0D ? -1 : 1;
                pokemonScroll = Math.max(0, Math.min(maximum, pokemonScroll + direction * columns));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!openingEffect.finished()) return true;
        Layout layout = layout();
        if ((button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            && layout.mapContains(mouseX, mouseY)) {
            panX += (int) Math.round(dragX);
            panY += (int) Math.round(dragY);
            updateNavigationButtons();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible behind the map instead of applying Screen's blur pass.
    }

    @Override
    public void onClose() {
        if (selectionToken != null && !selectionCompleted && !selectionCancelled) {
            selectionCancelled = true;
            MapNetwork.cancelSelection(selectionToken);
        }
        if (minecraft != null) {
            minecraft.setScreen(
                selectionToken == null || selectionCompleted ? parent : null
            );
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawMap(
        GuiGraphics graphics, Layout layout, int mouseX, int mouseY,
        MapContent.CaveEntrance hoveredCave, MapContent.ForestEntrance hoveredForest
    ) {
        drawRibbonPanel(graphics, layout.mapLeft(), layout.top(), layout.mapWidth(), layout.height(),
            MAP_BACKGROUND);
        graphics.fill(layout.mapLeft() + 14, layout.top() + 1,
            layout.mapLeft() + 58, layout.top() + 3, ACCENT_COLOR);
        boolean roundedClip = beginRoundedMapClip(graphics, layout);
        int size = hexSize(layout);
        ScreenPoint center = mapCenter(layout);

        for (int r = -content.mapRadiusCells(); r <= content.mapRadiusCells(); r++) {
            int minQ = Math.max(-content.mapRadiusCells(), -r - content.mapRadiusCells());
            int maxQ = Math.min(content.mapRadiusCells(), -r + content.mapRadiusCells());
            for (int q = minQ; q <= maxQ; q++) {
                MapContent.Town town = content.townAt(q, r);
                MapContent.BiomeTile tile = content.tileAt(q, r);
                int terrainColor = town == null && tile == null
                    ? emptyTerrainColor(content.emptyTerrainAt(q, r))
                    : biomeColor(town != null ? town.biome() : tile.biome());
                drawMapCell(graphics, mapCellBounds(center, size, q, r), terrainColor);
            }
        }

        for (MapContent.Route route : content.routes()) {
            List<MapContent.Hex> path = route.path();
            for (int index = 1; index < path.size(); index++) {
                MapContent.Hex fromHex = path.get(index - 1);
                MapContent.Hex toHex = path.get(index);
                ScreenPoint from = hexCenter(center, size, fromHex.q(), fromHex.r());
                ScreenPoint to = hexCenter(center, size, toHex.q(), toHex.r());
                if ("water".equals(route.surfaceStyle())) {
                    drawDashedLine(graphics, from.x(), from.y(), to.x(), to.y(), ROUTE_OUTLINE, 4, 4, 3);
                    drawDashedLine(graphics, from.x(), from.y(), to.x(), to.y(), WATER_ROUTE_COLOR, 2, 4, 3);
                } else {
                    drawLine(graphics, from.x(), from.y(), to.x(), to.y(), ROUTE_OUTLINE, 4);
                    drawLine(graphics, from.x(), from.y(), to.x(), to.y(), ROUTE_COLOR, 2);
                }
            }
        }

        for (MapContent.Town town : content.towns()) {
            ScreenPoint point = hexCenter(center, size, town.hex().q(), town.hex().r());
            int marker = Math.max(2, size / 3);
            graphics.fill(point.x() - marker, point.y() - marker, point.x() + marker + 1, point.y() + marker + 1, TOWN_BORDER);
            graphics.fill(point.x() - 1, point.y() - 1, point.x() + 2, point.y() + 2, 0xFF2A1D0E);
            String label = font.plainSubstrByWidth(town.name(), Math.max(42, size * 7));
            int labelWidth = font.width(label);
            int labelX = point.x() - labelWidth / 2;
            int labelY = point.y() - marker - 11;
            graphics.fill(labelX - 2, labelY - 1, labelX + labelWidth + 2, labelY + 9,
                MAP_LABEL_BACKGROUND);
            graphics.drawString(font, label, labelX, labelY, TEXT, false);
        }

        for (MapContent.MapObject object : content.objects()) {
            ScreenPoint point = hexCenter(center, size, object.hex().q(), object.hex().r());
            int marker = Math.max(3, size / 3);
            graphics.fill(point.x() - marker, point.y() - marker,
                point.x() + marker + 1, point.y() + marker + 1, ACCENT_COLOR);
            graphics.fill(point.x() - marker + 2, point.y() - marker + 2,
                point.x() + marker - 1, point.y() + marker - 1, 0xFF30251A);
            String label = font.plainSubstrByWidth(object.name(), Math.max(42, size * 7));
            int labelWidth = font.width(label);
            int labelX = point.x() - labelWidth / 2;
            int labelY = point.y() - marker - 11;
            graphics.fill(labelX - 2, labelY - 1, labelX + labelWidth + 2, labelY + 9,
                MAP_LABEL_BACKGROUND);
            graphics.drawString(font, label, labelX, labelY, TEXT, false);
        }

        for (MapContent.CaveEntrance entrance : content.caveEntrances()) {
            ScreenPoint point = hexCenter(center, size, entrance.hex().q(), entrance.hex().r());
            boolean hovered = entrance.equals(hoveredCave);
            int markerRadius = Math.max(6, size * 2 / 3);
            drawCaveMarker(graphics, point.x(), point.y(), markerRadius, hovered);
            MapContent.CaveInfo cave = content.cave(entrance.caveId());
            drawMapLocationLabel(
                graphics, point.x(), point.y(), markerRadius,
                cave == null ? entrance.name() : cave.name(), hovered
            );
        }

        for (MapContent.ForestEntrance entrance : content.forestEntrances()) {
            ScreenPoint point = hexCenter(center, size, entrance.hex().q(), entrance.hex().r());
            boolean hovered = entrance.equals(hoveredForest);
            int markerRadius = Math.max(6, size * 2 / 3);
            drawForestMarker(graphics, point.x(), point.y(), markerRadius, hovered);
            MapContent.ForestInfo forest = content.forest(entrance.forestId());
            drawMapLocationLabel(
                graphics, point.x(), point.y(), markerRadius,
                forest == null ? entrance.name() : forest.name(), hovered
            );
        }

        drawCellOutline(
            graphics, mapCellBounds(center, size, selected.q(), selected.r()), SELECTED_BORDER
        );
        drawOtherPlayers(graphics, center, size);
        MapContent.Hex playerHex = currentPlayerHex();
        if (playerHex != null) {
            ScreenPoint player = hexCenter(center, size, playerHex.q(), playerHex.r());
            int marker = Math.max(3, size / 2);
            graphics.fill(player.x() - marker - 1, player.y() - 1, player.x() + marker + 2, player.y() + 2, 0xFF161A18);
            graphics.fill(player.x() - 1, player.y() - marker - 1, player.x() + 2, player.y() + marker + 2, 0xFF161A18);
            graphics.fill(player.x() - marker, player.y(), player.x() + marker + 1, player.y() + 1, PLAYER_MARKER);
            graphics.fill(player.x(), player.y() - marker, player.x() + 1, player.y() + marker + 1, PLAYER_MARKER);
            String currentLabel = "현재 위치";
            int currentX = player.x() + marker + 3;
            int currentY = player.y() - 4;
            graphics.fill(currentX - 2, currentY - 1,
                currentX + font.width(currentLabel) + 2, currentY + 9,
                MAP_LABEL_BACKGROUND);
            graphics.drawString(font, currentLabel, currentX, currentY, TEXT, false);
        }

        if (layout.mapContains(mouseX, mouseY)) {
            MapContent.Hex hover = screenToHex(layout, mouseX, mouseY);
            if (isPopulated(hover.q(), hover.r())) {
                drawCellOutline(
                    graphics, mapCellBounds(center, size, hover.q(), hover.r()), 0x99FFFFFF
                );
            }
        }
        endRoundedMapClip(graphics, roundedClip);
    }

    private void drawOtherPlayers(GuiGraphics graphics, ScreenPoint center, int size) {
        if (!playerOnMappedDimension()) return;
        Map<String, Integer> playersPerHex = new HashMap<>();
        for (MapNetwork.MapPlayer other : MapNetwork.clientSnapshot().players()) {
            MapContent.Hex hex = content.worldToHex(other.x(), other.z());
            if (!content.contains(hex.q(), hex.r())) continue;

            String hexKey = hex.q() + ":" + hex.r();
            int slot = playersPerHex.getOrDefault(hexKey, 0);
            playersPerHex.put(hexKey, slot + 1);
            ScreenPoint point = hexCenter(center, size, hex.q(), hex.r());
            int markerX = point.x() + ((slot % 3) - 1) * 5;
            int markerY = point.y() + (slot / 3) * 5;

            graphics.fill(markerX - 3, markerY - 3, markerX + 4, markerY + 4, 0xFF161A18);
            graphics.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, OTHER_PLAYER_MARKER);

            String name = font.plainSubstrByWidth(other.name(), 64);
            int labelX = markerX + 5;
            int labelY = markerY - 4;
            graphics.fill(
                labelX - 2, labelY - 1, labelX + font.width(name) + 2, labelY + 9,
                0xE51C5359
            );
            graphics.drawString(font, name, labelX, labelY, 0xFFFFFFFF, false);
        }
    }

    private boolean beginRoundedMapClip(GuiGraphics graphics, Layout layout) {
        int left = layout.mapLeft() + 2;
        int top = layout.top() + 2;
        int right = layout.mapRight() - 2;
        int bottom = layout.bottom() - 2;
        graphics.enableScissor(left, top, right, bottom);
        if (minecraft == null || !minecraft.getMainRenderTarget().isStencilEnabled()) return false;

        graphics.flush();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glColorMask(false, false, false, false);
        fillRoundedRect(
            graphics, left, top, right, bottom,
            Math.max(0, menuTheme.cornerRadius - 2), 0xFFFFFFFF
        );
        graphics.flush();
        GL11.glColorMask(true, true, true, true);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        return true;
    }

    private static void endRoundedMapClip(GuiGraphics graphics, boolean roundedClip) {
        if (roundedClip) {
            graphics.flush();
            GL11.glStencilMask(0xFF);
            GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        graphics.disableScissor();
    }

    private void drawInfoPanel(
        GuiGraphics graphics, Layout layout, MapContent.CaveEntrance hoveredCave,
        MapContent.ForestEntrance hoveredForest
    ) {
        hidePokemonModels();
        encounterMethodButton.visible = false;
        drawRibbonPanel(graphics, layout.infoLeft(), layout.top(), layout.infoWidth(), layout.height(),
            INFO_BACKGROUND);
        graphics.fill(layout.infoLeft() + 12, layout.top() + 1,
            layout.infoLeft() + 48, layout.top() + 3, ACCENT_COLOR);
        int x = layout.infoLeft() + 10;
        int y = layout.top() + 9;
        int lineWidth = layout.infoWidth() - 20;
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        MapContent.Town town = content.townAt(selected.q(), selected.r());
        MapContent.MapObject object = content.objectAt(selected.q(), selected.r());
        MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());
        MapContent.CaveInfo cave = hoveredCave == null ? null : content.cave(hoveredCave.caveId());
        MapContent.ForestInfo forest = hoveredForest == null
            ? null : content.forest(hoveredForest.forestId());

        MapContent.Hex infoHex = hoveredCave != null ? hoveredCave.hex()
            : hoveredForest != null ? hoveredForest.hex() : selected;
        graphics.drawString(font, "Q " + infoHex.q() + " · R " + infoHex.r(), x, y, MUTED_TEXT, false);
        y += 15;
        MapContent.Hex playerHex = currentPlayerHex();
        if (playerHex != null) {
            graphics.drawString(font, "현재 위치 Q " + playerHex.q() + " · R " + playerHex.r(), x, y, PLAYER_MARKER, false);
            y += 15;
        }
        if (cave != null) {
            graphics.drawString(font, cave.name(), x, y, ACCENT_COLOR, false);
            y += 14;
            graphics.drawString(font, hoveredCave.name(), x, y, TEXT, false);
            y += 16;
            graphics.drawString(font, "동굴 입구 · " + directionName(hoveredCave.facing()), x, y, MUTED_TEXT, false);
            y += 16;
            graphics.drawString(font, "내부 바이옴", x, y, MUTED_TEXT, false);
            y += 11;
            graphics.drawString(font,
                font.plainSubstrByWidth(String.join(" · ", cave.biomes()), lineWidth),
                x, y, TEXT, false);
            y += 18;
            graphics.drawString(font, "서식 포켓몬 " + cave.pokemon().size() + "종", x, y, TEXT, false);
            y += 14;
            renderPokemonGrid(graphics, layout, cave.pokemon(), x, y, lineWidth, 0);
        } else if (forest != null) {
            graphics.drawString(font, forest.name(), x, y, ACCENT_COLOR, false);
            y += 14;
            graphics.drawString(font, hoveredForest.name(), x, y, TEXT, false);
            y += 16;
            graphics.drawString(font, "숲 입구 · " + directionName(hoveredForest.facing()), x, y, MUTED_TEXT, false);
            y += 16;
            graphics.drawString(font, "내부 바이옴", x, y, MUTED_TEXT, false);
            y += 11;
            graphics.drawString(font,
                font.plainSubstrByWidth(String.join(" · ", forest.biomes()), lineWidth),
                x, y, TEXT, false);
            y += 18;
            graphics.drawString(font, "서식 포켓몬 " + forest.pokemon().size() + "종", x, y, TEXT, false);
            y += 14;
            renderPokemonGrid(graphics, layout, forest.pokemon(), x, y, lineWidth, 0);
        } else if (town != null) {
            boolean visited = snapshot.visited().contains(town.id());
            graphics.drawString(font, town.name(), x, y, TEXT, false);
            y += 14;
            graphics.drawString(font, visited ? "방문 완료" : "미방문", x, y, visited ? SUCCESS_TEXT : WARNING_TEXT, false);
            y += 19;
            y = drawLabelValue(graphics, x, y, lineWidth, "바이옴", content.biome(town.biome()).name());
            y = drawLabelValue(graphics, x, y, lineWidth, "체육관", town.gymEnabled() ? gymName(town.gymTheme()) : "없음");
            if (town.gymEnabled()) y = drawSmallWrapped(graphics, x, y, lineWidth, town.gymStructure());
            y = drawLabelValue(graphics, x, y, lineWidth, "특별 건물", town.specialBuildingEnabled() ? "배치됨" : "없음");
            if (town.specialBuildingEnabled()) y = drawSmallWrapped(graphics, x, y, lineWidth, town.specialBuildingStructure());
            if (!town.fieldMoveNpcs().isEmpty()) {
                y += 4;
                graphics.drawString(font, "NPC 정보", x, y, MUTED_TEXT, false);
                y += 13;
                for (MapContent.FieldMoveNpc npc : town.fieldMoveNpcs()) {
                    y = drawSmallWrapped(
                        graphics, x, y, lineWidth,
                        withObjectParticle(npc.move()) + " 주는 NPC · " + npc.name()
                    );
                }
            }
            if (!visited && !snapshot.administrator() && !snapshot.creative()) {
                y += 4;
                graphics.drawWordWrap(font, Component.literal("이 마을을 직접 방문하면 빠른 이동이 해금됩니다."), x, y, lineWidth, MUTED_TEXT);
            }
        } else if (object != null) {
            boolean visited = snapshot.visited().contains(object.id());
            graphics.drawString(font, object.name(), x, y, TEXT, false);
            y += 14;
            graphics.drawString(font, "월드 오브젝트", x, y, MUTED_TEXT, false);
            y += 15;
            if (object.teleportable()) {
                graphics.drawString(font, visited ? "방문 완료 · 순간이동 가능" : "미방문 · 순간이동 가능",
                    x, y, visited ? SUCCESS_TEXT : WARNING_TEXT, false);
                if (!visited && !snapshot.administrator() && !snapshot.creative()) {
                    y += 16;
                    graphics.drawWordWrap(font, Component.literal(
                        "이 장소를 직접 방문하면 빠른 이동이 해금됩니다."
                    ), x, y, lineWidth, MUTED_TEXT);
                }
            } else {
                graphics.drawString(font, "순간이동 불가", x, y, MUTED_TEXT, false);
            }
        } else if (tile != null) {
            String method = selectedEncounterMethod(tile);
            MapContent.BiomeInfo biome = content.encounterBiome(tile, method);
            y += menuTheme.drawWrappedText(graphics, font, Component.literal(
                biome.name() + (biome.habitatVariant() > 0 ? biome.habitatVariant() : "")),
                x, y, lineWidth, MenuTheme.TextRole.BODY, menuTheme.textColor, 2) + 4;
            menuTheme.drawText(graphics, font, Component.literal(tile.biome()),
                x, y, MenuTheme.TextRole.CAPTION);
            y += menuTheme.textHeight(font, MenuTheme.TextRole.CAPTION) + 5;
            if (content.encounterMethods(tile).size() > 1) {
                encounterMethodButton.setY(y);
                encounterMethodButton.setMessage(Component.literal(MapContent.encounterMethodName(method) + "  ▸"));
                encounterMethodButton.visible = true;
                y += encounterMethodButton.getHeight() + 4;
            }
            menuTheme.drawText(graphics, font, Component.literal("서식 포켓몬 " + biome.totalPokemon() + "종 · 휠"),
                x, y, MenuTheme.TextRole.BODY);
            y += menuTheme.textHeight(font, MenuTheme.TextRole.BODY) + 5;
            renderPokemonGrid(graphics, layout, biome.pokemon(), x, y, lineWidth, pokemonScroll);
        } else {
            graphics.drawString(font, "미지정 타일", x, y, TEXT, false);
            y += 16;
            graphics.drawWordWrap(font, Component.literal("기본 월드 지형이 생성되는 영역입니다."), x, y, lineWidth, MUTED_TEXT);
        }

        if (!snapshot.message().isBlank()) {
            graphics.drawWordWrap(
                font, Component.literal(snapshot.message()), x, layout.bottom() - 45,
                lineWidth, snapshot.teleportSucceeded() ? SUCCESS_TEXT : WARNING_TEXT
            );
        }
        if (snapshot.administrator()) {
            graphics.drawString(font, "관리자 디버그 이동 활성", x, layout.bottom() - 39, WARNING_TEXT, false);
        } else if (snapshot.creative()) {
            graphics.drawString(font, "크리에이티브 자유 이동 활성", x, layout.bottom() - 39, SUCCESS_TEXT, false);
        }
    }

    private static MapContent.Hex screenPointToHex(
        ScreenPoint center, int size, double x, double y
    ) {
        double localX = x - center.x();
        double localY = y - center.y();
        double q = (Math.sqrt(3.0D) / 3.0D * localX - localY / 3.0D) / size;
        double r = (2.0D / 3.0D * localY) / size;
        double s = -q - r;
        int rq = (int) Math.round(q);
        int rr = (int) Math.round(r);
        int rs = (int) Math.round(s);
        double qDiff = Math.abs(rq - q);
        double rDiff = Math.abs(rr - r);
        double sDiff = Math.abs(rs - s);
        if (qDiff > rDiff && qDiff > sDiff) rq = -rr - rs;
        else if (rDiff > sDiff) rr = -rq - rs;
        return new MapContent.Hex(rq, rr);
    }

    private static String withObjectParticle(String value) {
        if (value == null || value.isEmpty()) return "";
        char last = value.charAt(value.length() - 1);
        boolean hasFinalConsonant = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
        return value + (hasFinalConsonant ? "을" : "를");
    }

    private String selectedEncounterMethod(MapContent.BiomeTile tile) {
        if (encounterMethod == null || !selected.equals(encounterSelection) || encounterGeneration != content.generation()
            || !content.encounterMethods(tile).contains(encounterMethod)) {
            encounterSelection = selected;
            encounterGeneration = content.generation();
            encounterMethod = content.defaultEncounterMethod(tile);
            pokemonScroll = 0;
        }
        return encounterMethod;
    }

    private void renderPokemonGrid(
        GuiGraphics graphics, Layout layout, List<MapContent.Pokemon> pokemon,
        int x, int y, int width, int scroll
    ) {
        int columns = pokemonColumns(width);
        int index = Math.min(scroll, maxPokemonScroll(pokemon.size(), columns));
        int modelIndex = 0;
        for (; index < pokemon.size() && modelIndex < pokemonModels.size(); index++, modelIndex++) {
            int column = modelIndex % columns;
            int row = modelIndex / columns;
            int iconX = x + column * POKEMON_CELL_SIZE;
            int iconY = y + row * POKEMON_CELL_SIZE;
            if (iconY + POKEMON_ICON_SIZE > layout.bottom() - 42) break;
            MapContent.Pokemon value = pokemon.get(index);
            graphics.fill(iconX, iconY, iconX + POKEMON_ICON_SIZE, iconY + POKEMON_ICON_SIZE, 0x702F3B35);
            showPokemonModel(modelIndex, value, iconX, iconY);
            pokemonHovers.add(new PokemonHover(
                iconX, iconY, iconX + POKEMON_ICON_SIZE, iconY + POKEMON_ICON_SIZE, value.name()
            ));
        }
    }

    private MapContent.CaveEntrance caveAtMouse(Layout layout, int mouseX, int mouseY) {
        if (!layout.mapContains(mouseX, mouseY)) return null;
        int size = hexSize(layout);
        int radius = Math.max(7, size / 2 + 3);
        ScreenPoint center = mapCenter(layout);
        MapContent.CaveEntrance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (MapContent.CaveEntrance entrance : content.caveEntrances()) {
            ScreenPoint point = hexCenter(center, size, entrance.hex().q(), entrance.hex().r());
            int dx = mouseX - point.x();
            int dy = mouseY - point.y();
            int distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < nearestDistance) {
                nearest = entrance;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private MapContent.ForestEntrance forestAtMouse(Layout layout, int mouseX, int mouseY) {
        if (!layout.mapContains(mouseX, mouseY)) return null;
        int size = hexSize(layout);
        int radius = Math.max(7, size / 2 + 3);
        ScreenPoint center = mapCenter(layout);
        MapContent.ForestEntrance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (MapContent.ForestEntrance entrance : content.forestEntrances()) {
            ScreenPoint point = hexCenter(center, size, entrance.hex().q(), entrance.hex().r());
            int dx = mouseX - point.x();
            int dy = mouseY - point.y();
            int distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < nearestDistance) {
                nearest = entrance;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void drawCaveMarker(
        GuiGraphics graphics, int centerX, int centerY, int radius, boolean hovered
    ) {
        int border = hovered ? ACCENT_COLOR : CAVE_BORDER;
        int sideRadius = Math.max(3, radius - 3);
        drawRockPeak(graphics, centerX - radius + 2, centerY + 2, sideRadius, border);
        drawRockPeak(graphics, centerX + radius - 2, centerY + 2, sideRadius, border);
        drawRockPeak(graphics, centerX, centerY, radius, border);
        int openingRadius = Math.max(2, radius / 2);
        for (int row = 0; row <= openingRadius; row++) {
            int halfWidth = Math.max(1, openingRadius - Math.abs(openingRadius - row));
            graphics.fill(centerX - halfWidth, centerY + row,
                centerX + halfWidth + 1, centerY + row + 1, CAVE_OPENING);
        }
        graphics.fill(centerX - openingRadius, centerY + openingRadius,
            centerX + openingRadius + 1, centerY + radius + 1, CAVE_OPENING);
        if (hovered) {
            graphics.fill(centerX - radius - 2, centerY + radius + 1,
                centerX + radius + 3, centerY + radius + 2, ACCENT_COLOR);
        }
    }

    private static void drawRockPeak(
        GuiGraphics graphics, int centerX, int centerY, int radius, int color
    ) {
        for (int row = 0; row <= radius; row++) {
            int halfWidth = Math.max(1, row);
            graphics.fill(centerX - halfWidth, centerY - radius + row,
                centerX + halfWidth + 1, centerY - radius + row + 1, color);
        }
    }

    private void drawForestMarker(
        GuiGraphics graphics, int centerX, int centerY, int radius, boolean hovered
    ) {
        int border = hovered ? ACCENT_COLOR : FOREST_BORDER;
        int sideRadius = Math.max(3, radius - 3);
        drawTreeMarker(graphics, centerX - radius + 2, centerY + 2, sideRadius, border);
        drawTreeMarker(graphics, centerX + radius - 2, centerY + 2, sideRadius, border);
        drawTreeMarker(graphics, centerX, centerY, radius, border);
        if (hovered) {
            graphics.fill(centerX - radius - 2, centerY + radius + 1,
                centerX + radius + 3, centerY + radius + 2, ACCENT_COLOR);
        }
    }

    private static void drawTreeMarker(
        GuiGraphics graphics, int centerX, int centerY, int radius, int color
    ) {
        for (int row = 0; row <= radius; row++) {
            int halfWidth = Math.max(1, row);
            graphics.fill(centerX - halfWidth, centerY - radius + row,
                centerX + halfWidth + 1, centerY - radius + row + 1, color);
        }
        int trunkWidth = Math.max(1, radius / 4);
        graphics.fill(centerX - trunkWidth, centerY,
            centerX + trunkWidth + 1, centerY + radius / 2 + 1, FOREST_OPENING);
    }

    private void drawMapLocationLabel(
        GuiGraphics graphics, int centerX, int centerY, int radius, String value, boolean hovered
    ) {
        String label = font.plainSubstrByWidth(value, Math.max(54, radius * 16));
        int labelWidth = font.width(label);
        int labelY = centerY - radius - 12;
        graphics.fill(centerX - labelWidth / 2 - 3, labelY - 2,
            centerX + (labelWidth + 1) / 2 + 3, labelY + 10,
            hovered ? MAP_LABEL_HOVER : MAP_LABEL_BACKGROUND);
        graphics.drawString(
            font, label, centerX - labelWidth / 2, labelY,
            hovered ? ACCENT_COLOR : TEXT, false
        );
    }

    private int drawLabelValue(GuiGraphics graphics, int x, int y, int width, String label, String value) {
        graphics.drawString(font, label, x, y, MUTED_TEXT, false);
        y += 11;
        graphics.drawString(font, font.plainSubstrByWidth(value, width), x, y, TEXT, false);
        return y + 15;
    }

    private int drawSmallWrapped(GuiGraphics graphics, int x, int y, int width, String value) {
        if (value == null || value.isBlank()) return y;
        graphics.drawWordWrap(font, Component.literal(value), x, y, width, MUTED_TEXT);
        return y + font.split(Component.literal(value), width).size() * 10 + 4;
    }

    private void initPokemonModels(Layout layout) {
        pokemonModels.clear();
        pokemonModelIds.clear();
        Species fallback = PokemonSpecies.getByName("bulbasaur");
        if (fallback == null) return;
        int columns = pokemonColumns(layout.infoWidth() - 20);
        int rows = Math.max(1, (layout.height() - 118) / POKEMON_CELL_SIZE);
        int capacity = Math.min(MAX_POKEMON_MODELS, columns * rows);
        capacity = Math.max(columns, capacity - capacity % columns);
        RenderablePokemon fallbackPokemon = new RenderablePokemon(fallback, java.util.Set.of(), ItemStack.EMPTY);
        for (int index = 0; index < capacity; index++) {
            ModelWidget model = CobblemonModelWidgetCompat.create(
                0, 0, POKEMON_ICON_SIZE, POKEMON_ICON_SIZE, fallbackPokemon,
                0.45F, 25.0F, 0.0D, false, false
            );
            model.visible = false;
            model.active = false;
            pokemonModels.add(addRenderableWidget(model));
            pokemonModelIds.add("");
        }
    }

    private void showPokemonModel(int modelIndex, MapContent.Pokemon pokemon, int x, int y) {
        ModelWidget model = pokemonModels.get(modelIndex);
        Species species = PokemonSpecies.getByIdentifier(ResourceLocation.parse(pokemon.id()));
        if (species == null) {
            model.visible = false;
            return;
        }
        if (!pokemon.id().equals(pokemonModelIds.get(modelIndex))) {
            model.setPokemon(new RenderablePokemon(species, java.util.Set.of(), ItemStack.EMPTY));
            pokemonModelIds.set(modelIndex, pokemon.id());
        }
        model.setX(x);
        model.setY(y);
        model.visible = true;
    }

    private void hidePokemonModels() {
        pokemonHovers.clear();
        for (ModelWidget model : pokemonModels) model.visible = false;
    }

    private static int pokemonColumns(int width) {
        return Math.max(2, width / POKEMON_CELL_SIZE);
    }

    private int maxPokemonScroll(int pokemonCount, int columns) {
        int hidden = Math.max(0, pokemonCount - pokemonModels.size());
        return (hidden + columns - 1) / columns * columns;
    }

    private void requestDestination() {
        if (teleportButton == null || !teleportButton.active) return;
        teleportButton.active = false;
        if (selectionToken != null) {
            teleportButton.setMessage(Component.translatable(
                "screen.cobbleventure_player_menu.world_map.selecting"
            ));
            MapNetwork.requestSelection(
                selectionToken, content.generation(), selected.q(), selected.r()
            );
        } else {
            teleportButton.setMessage(Component.translatable(
                "screen.cobbleventure_player_menu.world_map.teleporting"
            ));
            MapNetwork.requestTeleport(content.generation(), selected.q(), selected.r());
        }
    }

    private void updateTeleportButton() {
        if (teleportButton == null) return;
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        MapContent.Town town = content.townAt(selected.q(), selected.r());
        MapContent.MapObject object = content.objectAt(selected.q(), selected.r());
        boolean privileged = snapshot.administrator() || snapshot.creative();
        boolean permitted = selectionToken != null
            ? town != null && (privileged || snapshot.visited().contains(town.id()))
            : ProgressionNetwork.clientSnapshot().settlementTeleport()
                && (privileged
                    || town != null && snapshot.visited().contains(town.id())
                    || object != null && object.teleportable()
                        && snapshot.visited().contains(object.id()));
        teleportButton.visible = permitted;
        teleportButton.active = permitted;
        teleportButton.setMessage(Component.translatable(
            selectionToken != null
                ? "screen.cobbleventure_player_menu.world_map.select"
                : snapshot.administrator()
                ? "screen.cobbleventure_player_menu.world_map.debug_teleport"
                : "screen.cobbleventure_player_menu.world_map.teleport"
        ));
    }

    private MapContent.Hex playerHex() {
        MapContent.Hex current = currentPlayerHex();
        if (current != null && isPopulated(current.q(), current.r())) return current;
        if (!content.towns().isEmpty()) return content.towns().getFirst().hex();
        return new MapContent.Hex(0, 0);
    }

    private MapContent.Hex currentPlayerHex() {
        if (minecraft == null || minecraft.player == null || !playerOnMappedDimension()) return null;
        MapContent.Hex current = content.worldToHex(minecraft.player.getX(), minecraft.player.getZ());
        return content.contains(current.q(), current.r()) ? current : null;
    }

    private void selectPlayerGeneration() {
        if (minecraft == null || minecraft.level == null) return;
        String dimension = minecraft.level.dimension().location().toString();
        for (MapContent candidate : MapContent.all()) {
            if (candidate.dimension().equals(dimension)) {
                content = candidate;
                return;
            }
        }
    }

    private void switchGeneration(int offset) {
        List<Integer> generations = MapContent.availableGenerations();
        int index = generations.indexOf(content.generation());
        int nextIndex = Math.max(0, Math.min(generations.size() - 1, index + offset));
        if (nextIndex == index) return;
        MapContent next = MapContent.forGeneration(generations.get(nextIndex));
        if (next == null) return;
        content = next;
        selected = playerHex();
        pokemonScroll = 0;
        resetView();
        updateNavigationButtons();
        updateTeleportButton();
    }

    private void changeZoom(int amount, double focusX, double focusY) {
        int next = Math.max(0, Math.min(10, zoomLevel + amount));
        if (next == zoomLevel) return;
        Layout layout = layout();
        ScreenPoint oldCenter = mapCenter(layout);
        int oldSize = hexSize(layout);
        double localX = (focusX - oldCenter.x()) / oldSize;
        double localY = (focusY - oldCenter.y()) / oldSize;
        zoomLevel = next;
        ScreenPoint baseCenter = baseMapCenter(layout);
        int newSize = hexSize(layout);
        panX = (int) Math.round(focusX - localX * newSize - baseCenter.x());
        panY = (int) Math.round(focusY - localY * newSize - baseCenter.y());
        updateNavigationButtons();
    }

    private void resetView() {
        zoomLevel = 0;
        panX = 0;
        panY = 0;
        updateNavigationButtons();
    }

    private void updateNavigationButtons() {
        List<Integer> generations = MapContent.availableGenerations();
        int index = generations.indexOf(content.generation());
        if (previousGenerationButton != null) previousGenerationButton.active = index > 0;
        if (nextGenerationButton != null) nextGenerationButton.active = index >= 0 && index < generations.size() - 1;
        if (zoomOutButton != null) zoomOutButton.active = zoomLevel > 0;
        if (zoomInButton != null) zoomInButton.active = zoomLevel < 10;
        if (resetViewButton != null) resetViewButton.active = zoomLevel != 0 || panX != 0 || panY != 0;
    }

    private boolean playerOnMappedDimension() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.dimension().location().toString().equals(content.dimension());
    }

    private Layout layout() {
        int top = HEADER_HEIGHT;
        int bottom = Math.max(top + 120, height - FOOTER_HEIGHT);
        int infoWidth = Math.max(170, Math.min(220, width / 3));
        int infoRight = width - MARGIN;
        int infoLeft = Math.max(MARGIN + 120, infoRight - infoWidth);
        int mapLeft = MARGIN;
        int mapRight = Math.max(mapLeft + 100, infoLeft - PANEL_GAP);
        return new Layout(mapLeft, mapRight, infoLeft, infoRight, top, bottom);
    }

    private int hexSize(Layout layout) {
        MapBounds bounds = mapBounds();
        return WorldMapSizing.responsiveHexSize(
            layout.mapWidth(), layout.height(), bounds.width(), bounds.height(), zoomLevel
        );
    }

    private ScreenPoint mapCenter(Layout layout) {
        ScreenPoint base = baseMapCenter(layout);
        return new ScreenPoint(base.x() + panX, base.y() + panY);
    }

    private ScreenPoint baseMapCenter(Layout layout) {
        MapBounds bounds = mapBounds();
        int size = hexSize(layout);
        int x = (int) Math.round((layout.mapLeft() + layout.mapRight()) / 2.0D - bounds.centerX() * size);
        int y = (int) Math.round(
            (layout.top() + MAP_CONTENT_TOP_INSET + layout.bottom()) / 2.0D - bounds.centerY() * size
        );
        return new ScreenPoint(x, y);
    }

    private boolean isPopulated(int q, int r) {
        return content.tileAt(q, r) != null || content.townAt(q, r) != null
            || content.objectAt(q, r) != null;
    }

    private MapBounds mapBounds() {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int r = -content.mapRadiusCells(); r <= content.mapRadiusCells(); r++) {
            int minQ = Math.max(-content.mapRadiusCells(), -r - content.mapRadiusCells());
            int maxQ = Math.min(content.mapRadiusCells(), -r + content.mapRadiusCells());
            for (int q = minQ; q <= maxQ; q++) {
                if (!isPopulated(q, r)) continue;
                double x = Math.sqrt(3.0D) * (q + r / 2.0D);
                double y = 1.5D * r;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (!Double.isFinite(minX)) return new MapBounds(0.0D, 0.0D, Math.sqrt(3.0D), 2.0D);
        return new MapBounds(
            (minX + maxX) / 2.0D,
            (minY + maxY) / 2.0D,
            maxX - minX + Math.sqrt(3.0D),
            maxY - minY + 2.0D
        );
    }

    private static ScreenPoint hexCenter(ScreenPoint center, int size, int q, int r) {
        int x = (int) Math.round(center.x() + Math.sqrt(3.0D) * size * (q + r / 2.0D));
        int y = (int) Math.round(center.y() + 1.5D * size * r);
        return new ScreenPoint(x, y);
    }

    private MapContent.Hex screenToHex(Layout layout, double x, double y) {
        ScreenPoint center = mapCenter(layout);
        int size = hexSize(layout);
        return screenPointToHex(center, size, x, y);
    }

    /**
     * Uses shared rounded edges instead of independently expanding each cell around its rounded
     * center. Adjacent patches therefore meet without one patch overdrawing its neighbour.
     */
    private static CellBounds mapCellBounds(ScreenPoint center, int size, int q, int r) {
        double centerX = center.x() + Math.sqrt(3.0D) * size * (q + r / 2.0D);
        double centerY = center.y() + 1.5D * size * r;
        double halfWidth = Math.sqrt(3.0D) * 0.5D * size;
        double halfHeight = 0.75D * size;
        return new CellBounds(
            (int) Math.round(centerX - halfWidth),
            (int) Math.round(centerY - halfHeight),
            (int) Math.round(centerX + halfWidth),
            (int) Math.round(centerY + halfHeight)
        );
    }

    private static void drawMapCell(GuiGraphics graphics, CellBounds bounds, int color) {
        graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), color);
    }

    /** Draws an outline with the exact same shared bounds as {@link #drawMapCell}. */
    private static void drawCellOutline(GuiGraphics graphics, CellBounds bounds, int color) {
        graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.top() + 1, color);
        graphics.fill(bounds.left(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), color);
        graphics.fill(bounds.left(), bounds.top(), bounds.left() + 1, bounds.bottom(), color);
        graphics.fill(bounds.right() - 1, bounds.top(), bounds.right(), bounds.bottom(), color);
    }

    private static void drawLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color, int width) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x0 - width / 2, y0 - width / 2, x0 + (width + 1) / 2, y0 + (width + 1) / 2, color);
            if (x0 == x1 && y0 == y1) break;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }

    private static void drawDashedLine(
        GuiGraphics graphics, int x0, int y0, int x1, int y1,
        int color, int width, int dashLength, int gapLength
    ) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        int step = 0;
        int period = dashLength + gapLength;
        while (true) {
            if (step % period < dashLength) {
                graphics.fill(
                    x0 - width / 2, y0 - width / 2,
                    x0 + (width + 1) / 2, y0 + (width + 1) / 2, color
                );
            }
            if (x0 == x1 && y0 == y1) break;
            int twice = error * 2;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
            step++;
        }
    }

    private static int biomeColor(String biome) {
        String value = biome.toLowerCase();
        if (value.contains("ocean") || value.contains("river")) return 0xFF53C6D7;
        if (value.contains("beach")) return 0xFFF4D67A;
        if (value.contains("badlands") || value.contains("desert")) return 0xFFE9B45C;
        if (value.contains("snow") || value.contains("ice") || value.contains("frozen")) return 0xFFD9F3EE;
        if (value.contains("peak") || value.contains("mountain") || value.contains("windswept")) return 0xFF9AB89B;
        if (value.contains("jungle")) return 0xFF50A957;
        if (value.contains("forest") || value.contains("taiga")) return 0xFF65B966;
        if (value.contains("swamp") || value.contains("mangrove")) return 0xFF78A95F;
        if (value.contains("meadow") || value.contains("flower")) return 0xFF9CD765;
        return 0xFF8DCE63;
    }

    private static int emptyTerrainColor(String terrain) {
        return switch (terrain) {
            case "ocean" -> 0xFF35AFC4;
            case "deep_ocean" -> 0xFF247FA8;
            case "desert" -> 0xFFE4B65B;
            case "stone_mountain" -> 0xFF91A39B;
            case "red_rock_mountain" -> 0xFFC27858;
            case "snow_mountain" -> 0xFFD6ECE8;
            case "dense_forest" -> 0xFF4E8950;
            case "high_forest" -> 0xFF65985B;
            default -> 0xFF65985B;
        };
    }

    private static String gymName(String theme) {
        return switch (theme) {
            case "fire" -> "불꽃 타입";
            case "water" -> "물 타입";
            case "electric" -> "전기 타입";
            case "grass" -> "풀 타입";
            case "ice" -> "얼음 타입";
            case "fighting" -> "격투 타입";
            case "poison" -> "독 타입";
            case "ground" -> "땅 타입";
            case "flying" -> "비행 타입";
            case "psychic" -> "에스퍼 타입";
            case "bug" -> "벌레 타입";
            case "rock" -> "바위 타입";
            case "ghost" -> "고스트 타입";
            case "dragon" -> "드래곤 타입";
            case "dark" -> "악 타입";
            case "steel" -> "강철 타입";
            case "fairy" -> "페어리 타입";
            default -> "노말 타입";
        };
    }

    private static String directionName(String direction) {
        return switch (direction) {
            case "north" -> "북쪽";
            case "south" -> "남쪽";
            case "east" -> "동쪽";
            case "west" -> "서쪽";
            default -> direction;
        };
    }

    private void drawRibbonPanel(
        GuiGraphics graphics,
        int x,
        int y,
        int panelWidth,
        int panelHeight,
        int innerColor
    ) {
        int radius = menuTheme.cornerRadius;
        fillRoundedRect(graphics, x + menuTheme.shadowOffset, y + menuTheme.shadowOffset,
            x + panelWidth + menuTheme.shadowOffset, y + panelHeight + menuTheme.shadowOffset,
            radius, SHADOW_COLOR);
        fillRoundedRect(graphics, x, y, x + panelWidth, y + panelHeight, radius, PANEL_DARK_COLOR);
        fillRoundedRect(graphics, x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1,
            Math.max(0, radius - 1), innerColor);
        graphics.fill(x + 12, y + 1, x + panelWidth - 12, y + 2, PANEL_LIGHT_COLOR);
        graphics.fill(x + panelWidth - 42, y + panelHeight - 3,
            x + panelWidth - 8, y + panelHeight - 1, ACCENT_COLOR);
    }

    private void drawCenteredNoShadow(
        GuiGraphics graphics, Component text, int centerX, int y, int color
    ) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private Component generationHeader() {
        return Component.literal(content.displayName());
    }

    private int generationHeaderWidth() {
        return Math.max(176, font.width(generationHeader()) + 34);
    }

    private int rowRadius(int height) {
        return Math.min(menuTheme.rowRadius, Math.max(0, height / 2));
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

    private float mapControlOpacity(int mouseX, int mouseY) {
        if (zoomInButton == null || zoomOutButton == null || resetViewButton == null) return 1.0F;
        int left = zoomInButton.getX();
        int top = zoomInButton.getY();
        int right = resetViewButton.getX() + resetViewButton.getWidth();
        int bottom = top + MAP_CONTROL_SIZE;
        int distanceX = mouseX < left ? left - mouseX : Math.max(0, mouseX - right);
        int distanceY = mouseY < top ? top - mouseY : Math.max(0, mouseY - bottom);
        double distance = Math.sqrt(distanceX * distanceX + distanceY * distanceY);
        if (distance <= 16.0D) return 1.0F;
        if (distance >= 48.0D) return 0.35F;
        return (float)(1.0D - (distance - 16.0D) / 32.0D * 0.65D);
    }

    private static int withOpacity(int color, float opacity) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, opacity)));
        return color & 0x00FFFFFF | alpha << 24;
    }

    private final class RibbonButton extends AbstractButton {
        private final Runnable action;

        private RibbonButton(Component message, int x, int y, int width, int height, Runnable action) {
            super(x, y, width, height, message);
            this.action = action;
        }

        @Override
        public void onPress() {
            if (active) action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = active && isHovered() ? ACCENT_COLOR : PANEL_LIGHT_COLOR;
            int fill = active && isHovered() ? MAP_LABEL_HOVER : PANEL_COLOR;
            boolean mapControl = this == zoomInButton || this == zoomOutButton || this == resetViewButton;
            float opacity = mapControl ? mapControlOpacity(mouseX, mouseY) : 1.0F;
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                rowRadius(getHeight()), withOpacity(border, opacity));
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(0, rowRadius(getHeight()) - 1), withOpacity(fill, opacity));
            int color = active ? (isHovered() ? ACCENT_COLOR : TEXT) : MUTED_TEXT;
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8);
            graphics.drawString(font, label,
                getX() + (getWidth() - font.width(label)) / 2,
                getY() + (getHeight() - 8) / 2, withOpacity(color, opacity), false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private record ScreenPoint(int x, int y) {}
    private record CellBounds(int left, int top, int right, int bottom) {}
    private record PokemonHover(int left, int top, int right, int bottom, String name) {
        boolean contains(int x, int y) { return x >= left && x < right && y >= top && y < bottom; }
    }
    private record MapBounds(double centerX, double centerY, double width, double height) {}
    private record Layout(int mapLeft, int mapRight, int infoLeft, int infoRight, int top, int bottom) {
        int mapWidth() { return mapRight - mapLeft; }
        int infoWidth() { return infoRight - infoLeft; }
        int height() { return bottom - top; }
        int mapCenterX() { return (mapLeft + mapRight) / 2; }
        int mapCenterY() { return (top + bottom) / 2; }
        boolean mapContains(double x, double y) { return x >= mapLeft && x < mapRight && y >= top && y < bottom; }
    }
}
