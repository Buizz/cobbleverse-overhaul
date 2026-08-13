package dev.buizz.cobbleventure.playermenu.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget;
import com.cobblemon.mod.common.pokemon.RenderablePokemon;
import com.cobblemon.mod.common.pokemon.Species;
import dev.buizz.cobbleventure.playermenu.MapContent;
import dev.buizz.cobbleventure.playermenu.MapNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/** Interactive hex world map backed by the same content used by world generation. */
public final class WorldMapScreen extends Screen {
    private static final int PAGE_BACKGROUND = 0x44000000;
    private static final int SHADOW_COLOR = 0x99000000;
    private static final int PANEL_COLOR = 0xF01D2630;
    private static final int PANEL_DARK_COLOR = 0xFF10171E;
    private static final int PANEL_LIGHT_COLOR = 0xFF34444F;
    private static final int MAP_BACKGROUND = 0xF010171E;
    private static final int TILE_BORDER = 0xFF34483D;
    private static final int INFO_BACKGROUND = 0xF01D2630;
    private static final int ROUTE_COLOR = 0xFFD8BA70;
    private static final int TOWN_BORDER = 0xFFF0A43B;
    private static final int CAVE_BORDER = 0xFF9AA8B0;
    private static final int CAVE_OPENING = 0xFF080B0E;
    private static final int SELECTED_BORDER = 0xFFF2F5EF;
    private static final int PLAYER_MARKER = 0xFFFFD166;
    private static final int TEXT = 0xFFF3F5F1;
    private static final int MUTED_TEXT = 0xFFAAB8B0;
    private static final int ACCENT_COLOR = 0xFF5EE4E4;
    private static final int SUCCESS_TEXT = ACCENT_COLOR;
    private static final int WARNING_TEXT = 0xFFF0A43B;
    private static final int MARGIN = 14;
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 32;
    private static final int PANEL_GAP = 9;
    private static final int POKEMON_ICON_SIZE = 16;
    private static final int POKEMON_ICON_GAP = 2;
    private static final int POKEMON_CELL_SIZE = POKEMON_ICON_SIZE + POKEMON_ICON_GAP;
    private static final int MAX_POKEMON_MODELS = 96;

    private final Screen parent;
    private MapContent content = MapContent.instance();
    private MapContent.Hex selected = new MapContent.Hex(0, 0);
    private AbstractButton teleportButton;
    private AbstractButton previousGenerationButton;
    private AbstractButton nextGenerationButton;
    private AbstractButton zoomOutButton;
    private AbstractButton zoomInButton;
    private AbstractButton resetViewButton;
    private long stateRevision = -1L;
    private int pokemonScroll;
    private int zoomLevel;
    private int panX;
    private int panY;
    private boolean generationInitialized;
    private final List<ModelWidget> pokemonModels = new ArrayList<>();
    private final List<String> pokemonModelIds = new ArrayList<>();
    private final List<PokemonHover> pokemonHovers = new ArrayList<>();

    public WorldMapScreen(Screen parent) {
        super(Component.translatable("screen.cobbleventure_player_menu.world_map.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (!generationInitialized) {
            selectPlayerGeneration();
            generationInitialized = true;
        }
        selected = playerHex();
        Layout layout = layout();
        previousGenerationButton = addRenderableWidget(new RibbonButton(
            Component.literal("<"), layout.mapLeft() + 4, 6, 20, 20,
            () -> switchGeneration(-1)));
        nextGenerationButton = addRenderableWidget(new RibbonButton(
            Component.literal(">"), layout.mapLeft() + 80, 6, 20, 20,
            () -> switchGeneration(1)));
        zoomOutButton = addRenderableWidget(new RibbonButton(
            Component.literal("−"), layout.mapRight() - 72, layout.top() + 6, 20, 20,
            () -> changeZoom(-1, layout.mapCenterX(), layout.mapCenterY())));
        resetViewButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.world_map.reset_view"),
            layout.mapRight() - 50, layout.top() + 6, 42, 20, this::resetView));
        zoomInButton = addRenderableWidget(new RibbonButton(
            Component.literal("+"), layout.mapRight() - 94, layout.top() + 6, 20, 20,
            () -> changeZoom(1, layout.mapCenterX(), layout.mapCenterY())));
        teleportButton = addRenderableWidget(new RibbonButton(
            Component.translatable("screen.cobbleventure_player_menu.world_map.teleport"),
            layout.infoLeft() + 9, layout.bottom() - 28, layout.infoWidth() - 18, 20,
            this::requestTeleport));
        Component closeLabel = Component.translatable(
            parent == null
                ? "screen.cobbleventure_player_menu.world_map.close"
                : "screen.cobbleventure_player_menu.world_map.back"
        );
        addRenderableWidget(new RibbonButton(closeLabel,
            width - MARGIN - 72, height - FOOTER_HEIGHT + 5, 72, 20, this::onClose));
        initPokemonModels(layout);
        MapNetwork.requestSnapshot();
        updateNavigationButtons();
        updateTeleportButton();
    }

    @Override
    public void tick() {
        super.tick();
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        if (snapshot.revision() != stateRevision) {
            stateRevision = snapshot.revision();
            if (snapshot.teleportSucceeded() && minecraft != null) {
                minecraft.setScreen(null);
                return;
            }
            updateTeleportButton();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, PAGE_BACKGROUND);
        int titleWidth = Math.max(116, font.width(title) + 34);
        drawRibbonPanel(graphics, (width - titleWidth) / 2, 4, titleWidth, 23, PANEL_COLOR);
        graphics.fill((width - titleWidth) / 2 + 12, 5,
            (width - titleWidth) / 2 + 42, 7, ACCENT_COLOR);
        graphics.drawCenteredString(font, title, width / 2, 11, TEXT);
        Layout layout = layout();
        MapContent.CaveEntrance hoveredCave = caveAtMouse(layout, mouseX, mouseY);
        graphics.drawCenteredString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.generation", content.generation()),
            layout.mapLeft() + 52,
            11,
            TEXT
        );
        drawMap(graphics, layout, mouseX, mouseY, hoveredCave);
        drawInfoPanel(graphics, layout, hoveredCave);
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.hint"),
            MARGIN,
            height - FOOTER_HEIGHT + 10,
            MUTED_TEXT,
            false
        );
        super.render(graphics, mouseX, mouseY, partialTick);
        for (PokemonHover hover : pokemonHovers) {
            if (hover.contains(mouseX, mouseY)) {
                graphics.renderTooltip(font, Component.literal(hover.name()), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
        Layout layout = layout();
        if (layout.mapContains(mouseX, mouseY) && scrollY != 0.0D) {
            changeZoom(scrollY > 0.0D ? 1 : -1, mouseX, mouseY);
            return true;
        }
        if (mouseX >= layout.infoLeft() && mouseX < layout.infoRight()
            && mouseY >= layout.top() && mouseY < layout.bottom()
            && content.townAt(selected.q(), selected.r()) == null) {
            MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());
            if (tile != null) {
                int count = content.biome(tile).pokemon().size();
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
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawMap(
        GuiGraphics graphics, Layout layout, int mouseX, int mouseY,
        MapContent.CaveEntrance hoveredCave
    ) {
        drawRibbonPanel(graphics, layout.mapLeft(), layout.top(), layout.mapWidth(), layout.height(),
            MAP_BACKGROUND);
        graphics.fill(layout.mapLeft() + 14, layout.top() + 1,
            layout.mapLeft() + 58, layout.top() + 3, ACCENT_COLOR);
        graphics.enableScissor(layout.mapLeft() + 2, layout.top() + 2,
            layout.mapRight() - 2, layout.bottom() - 2);
        int size = hexSize(layout);
        ScreenPoint center = mapCenter(layout);

        for (int r = -content.mapRadiusCells(); r <= content.mapRadiusCells(); r++) {
            int minQ = Math.max(-content.mapRadiusCells(), -r - content.mapRadiusCells());
            int maxQ = Math.min(content.mapRadiusCells(), -r + content.mapRadiusCells());
            for (int q = minQ; q <= maxQ; q++) {
                ScreenPoint point = hexCenter(center, size, q, r);
                MapContent.Town town = content.townAt(q, r);
                MapContent.BiomeTile tile = content.tileAt(q, r);
                if (town == null && tile == null) continue;
                String biome = town != null ? town.biome() : tile.biome();
                int border = town != null ? TOWN_BORDER : TILE_BORDER;
                drawHex(graphics, point.x(), point.y(), size, border, biomeColor(biome));
            }
        }

        for (MapContent.Route route : content.routes()) {
            List<MapContent.Hex> path = route.path();
            for (int index = 1; index < path.size(); index++) {
                ScreenPoint from = hexCenter(center, size, path.get(index - 1).q(), path.get(index - 1).r());
                ScreenPoint to = hexCenter(center, size, path.get(index).q(), path.get(index).r());
                drawLine(graphics, from.x(), from.y(), to.x(), to.y(), ROUTE_COLOR, 2);
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
            graphics.fill(labelX - 2, labelY - 1, labelX + labelWidth + 2, labelY + 9, 0xB0121714);
            graphics.drawString(font, label, labelX, labelY, TEXT, false);
        }

        for (MapContent.CaveEntrance entrance : content.caveEntrances()) {
            ScreenPoint point = hexCenter(center, size, entrance.hex().q(), entrance.hex().r());
            boolean hovered = entrance.equals(hoveredCave);
            drawCaveMarker(graphics, point.x(), point.y(), Math.max(4, size / 2), hovered);
            if (hovered) {
                MapContent.CaveInfo cave = content.cave(entrance.caveId());
                String label = cave == null ? entrance.name() : cave.name();
                label = font.plainSubstrByWidth(label, Math.max(54, size * 8));
                int labelWidth = font.width(label);
                int labelY = point.y() - Math.max(4, size / 2) - 12;
                graphics.fill(point.x() - labelWidth / 2 - 3, labelY - 2,
                    point.x() + (labelWidth + 1) / 2 + 3, labelY + 10, 0xD010171E);
                graphics.drawString(font, label, point.x() - labelWidth / 2, labelY, ACCENT_COLOR, false);
            }
        }

        ScreenPoint selectedPoint = hexCenter(center, size, selected.q(), selected.r());
        drawHexOutline(graphics, selectedPoint.x(), selectedPoint.y(), size + 1, SELECTED_BORDER);
        MapContent.Hex playerHex = currentPlayerHex();
        if (playerHex != null) {
            ScreenPoint player = hexCenter(center, size, playerHex.q(), playerHex.r());
            int marker = Math.max(3, size / 2);
            graphics.fill(player.x() - marker - 1, player.y() - 1, player.x() + marker + 2, player.y() + 2, 0xFF161A18);
            graphics.fill(player.x() - 1, player.y() - marker - 1, player.x() + 2, player.y() + marker + 2, 0xFF161A18);
            graphics.fill(player.x() - marker, player.y(), player.x() + marker + 1, player.y() + 1, PLAYER_MARKER);
            graphics.fill(player.x(), player.y() - marker, player.x() + 1, player.y() + marker + 1, PLAYER_MARKER);
            graphics.drawString(font, "현재 위치", player.x() + marker + 3, player.y() - 4, PLAYER_MARKER, true);
        }

        if (layout.mapContains(mouseX, mouseY)) {
            MapContent.Hex hover = screenToHex(layout, mouseX, mouseY);
            if (isPopulated(hover.q(), hover.r())) {
                ScreenPoint point = hexCenter(center, size, hover.q(), hover.r());
                drawHexOutline(graphics, point.x(), point.y(), size, 0x99FFFFFF);
            }
        }
        graphics.disableScissor();
    }

    private void drawInfoPanel(
        GuiGraphics graphics, Layout layout, MapContent.CaveEntrance hoveredCave
    ) {
        hidePokemonModels();
        drawRibbonPanel(graphics, layout.infoLeft(), layout.top(), layout.infoWidth(), layout.height(),
            INFO_BACKGROUND);
        graphics.fill(layout.infoLeft() + 12, layout.top() + 1,
            layout.infoLeft() + 48, layout.top() + 3, ACCENT_COLOR);
        int x = layout.infoLeft() + 10;
        int y = layout.top() + 9;
        int lineWidth = layout.infoWidth() - 20;
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        MapContent.Town town = content.townAt(selected.q(), selected.r());
        MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());
        MapContent.CaveInfo cave = hoveredCave == null ? null : content.cave(hoveredCave.caveId());

        MapContent.Hex infoHex = hoveredCave == null ? selected : hoveredCave.hex();
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
        } else if (tile != null) {
            MapContent.BiomeInfo biome = content.biome(tile);
            graphics.drawString(font, biome.name() + biome.habitatVariant(), x, y, TEXT, false);
            y += 15;
            graphics.drawString(font, tile.biome(), x, y, MUTED_TEXT, false);
            y += 19;
            graphics.drawString(font, "서식 포켓몬 " + biome.totalPokemon() + "종 · 휠", x, y, TEXT, false);
            y += 14;
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

    private static String withObjectParticle(String value) {
        if (value == null || value.isEmpty()) return "";
        char last = value.charAt(value.length() - 1);
        boolean hasFinalConsonant = last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
        return value + (hasFinalConsonant ? "을" : "를");
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

    private static void drawCaveMarker(
        GuiGraphics graphics, int centerX, int centerY, int radius, boolean hovered
    ) {
        int border = hovered ? ACCENT_COLOR : CAVE_BORDER;
        for (int row = 0; row <= radius; row++) {
            int halfWidth = Math.max(1, row);
            graphics.fill(centerX - halfWidth, centerY - radius + row,
                centerX + halfWidth + 1, centerY - radius + row + 1, border);
        }
        graphics.fill(centerX - radius, centerY, centerX + radius + 1, centerY + radius, border);
        graphics.fill(centerX - Math.max(1, radius - 2), centerY,
            centerX + Math.max(1, radius - 2) + 1, centerY + radius, CAVE_OPENING);
        if (hovered) {
            graphics.fill(centerX - radius - 2, centerY + radius + 1,
                centerX + radius + 3, centerY + radius + 2, ACCENT_COLOR);
        }
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
            ModelWidget model = new ModelWidget(
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

    private void requestTeleport() {
        if (teleportButton == null || !teleportButton.active) return;
        teleportButton.active = false;
        teleportButton.setMessage(Component.translatable("screen.cobbleventure_player_menu.world_map.teleporting"));
        MapNetwork.requestTeleport(content.generation(), selected.q(), selected.r());
    }

    private void updateTeleportButton() {
        if (teleportButton == null) return;
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        MapContent.Town town = content.townAt(selected.q(), selected.r());
        boolean permitted = snapshot.administrator() || snapshot.creative()
            || town != null && snapshot.visited().contains(town.id());
        teleportButton.visible = permitted;
        teleportButton.active = permitted;
        teleportButton.setMessage(Component.translatable(
            snapshot.administrator()
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
        double horizontal = (layout.mapWidth() - 12.0D) / bounds.width();
        double vertical = (layout.height() - 12.0D) / bounds.height();
        int fitted = Math.max(3, Math.min(12, (int) Math.floor(Math.min(horizontal, vertical))));
        return Math.min(32, fitted + zoomLevel * 2);
    }

    private ScreenPoint mapCenter(Layout layout) {
        ScreenPoint base = baseMapCenter(layout);
        return new ScreenPoint(base.x() + panX, base.y() + panY);
    }

    private ScreenPoint baseMapCenter(Layout layout) {
        MapBounds bounds = mapBounds();
        int size = hexSize(layout);
        int x = (int) Math.round((layout.mapLeft() + layout.mapRight()) / 2.0D - bounds.centerX() * size);
        int y = (int) Math.round((layout.top() + layout.bottom()) / 2.0D - bounds.centerY() * size);
        return new ScreenPoint(x, y);
    }

    private boolean isPopulated(int q, int r) {
        return content.tileAt(q, r) != null || content.townAt(q, r) != null;
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

    private static void drawHex(GuiGraphics graphics, int centerX, int centerY, int size, int border, int fill) {
        fillHex(graphics, centerX, centerY, size, border);
        fillHex(graphics, centerX, centerY, Math.max(1, size - 1), fill);
    }

    private static void drawHexOutline(GuiGraphics graphics, int centerX, int centerY, int size, int color) {
        int halfWidth = (int) Math.round(Math.sqrt(3.0D) * 0.5D * size);
        int halfHeight = size / 2;
        int[] xs = { centerX, centerX + halfWidth, centerX + halfWidth, centerX, centerX - halfWidth, centerX - halfWidth };
        int[] ys = { centerY - size, centerY - halfHeight, centerY + halfHeight, centerY + size, centerY + halfHeight, centerY - halfHeight };
        for (int index = 0; index < 6; index++) {
            int next = (index + 1) % 6;
            drawLine(graphics, xs[index], ys[index], xs[next], ys[next], color, 1);
        }
    }

    private static void fillHex(GuiGraphics graphics, int centerX, int centerY, int size, int color) {
        for (int offsetY = -size; offsetY <= size; offsetY++) {
            double ratio = Math.abs(offsetY) / (double) size;
            double widthFactor = ratio <= 0.5D ? 1.0D : 2.0D * (1.0D - ratio);
            int halfWidth = Math.max(0, (int) Math.round(Math.sqrt(3.0D) * 0.5D * size * widthFactor));
            graphics.fill(centerX - halfWidth, centerY + offsetY, centerX + halfWidth + 1, centerY + offsetY + 1, color);
        }
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

    private static int biomeColor(String biome) {
        String value = biome.toLowerCase();
        if (value.contains("ocean") || value.contains("river")) return 0xFF356E91;
        if (value.contains("beach")) return 0xFFB8A66A;
        if (value.contains("badlands") || value.contains("desert")) return 0xFFA86B43;
        if (value.contains("snow") || value.contains("ice") || value.contains("frozen")) return 0xFF9EC2CB;
        if (value.contains("peak") || value.contains("mountain") || value.contains("windswept")) return 0xFF727D79;
        if (value.contains("jungle")) return 0xFF34784A;
        if (value.contains("forest") || value.contains("taiga")) return 0xFF3F6845;
        if (value.contains("swamp") || value.contains("mangrove")) return 0xFF526E48;
        if (value.contains("meadow") || value.contains("flower")) return 0xFF699B62;
        return 0xFF63875D;
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

    private static void drawRibbonPanel(
        GuiGraphics graphics,
        int x,
        int y,
        int panelWidth,
        int panelHeight,
        int innerColor
    ) {
        fillRoundedRect(graphics, x + 4, y + 5, x + panelWidth + 4, y + panelHeight + 5,
            9, SHADOW_COLOR);
        fillRoundedRect(graphics, x, y, x + panelWidth, y + panelHeight, 9, PANEL_DARK_COLOR);
        fillRoundedRect(graphics, x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1,
            8, innerColor);
        graphics.fill(x + 12, y + 1, x + panelWidth - 12, y + 2, PANEL_LIGHT_COLOR);
        graphics.fill(x + panelWidth - 42, y + panelHeight - 3,
            x + panelWidth - 8, y + panelHeight - 1, ACCENT_COLOR);
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
            int fill = active && isHovered() ? 0xE0374650 : PANEL_DARK_COLOR;
            fillRoundedRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                getHeight() / 2, border);
            fillRoundedRect(graphics, getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1,
                Math.max(1, getHeight() / 2 - 1), fill);
            int color = active ? (isHovered() ? ACCENT_COLOR : TEXT) : MUTED_TEXT;
            graphics.drawCenteredString(font,
                font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private record ScreenPoint(int x, int y) {}
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
