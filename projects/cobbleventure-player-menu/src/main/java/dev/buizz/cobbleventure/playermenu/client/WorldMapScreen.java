package dev.buizz.cobbleventure.playermenu.client;

import dev.buizz.cobbleventure.playermenu.MapContent;
import dev.buizz.cobbleventure.playermenu.MapNetwork;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Interactive hex world map backed by the same content used by world generation. */
public final class WorldMapScreen extends Screen {
    private static final int PAGE_BACKGROUND = 0xC8141917;
    private static final int MAP_BACKGROUND = 0xFF121A16;
    private static final int EMPTY_TILE = 0xFF1A2620;
    private static final int EMPTY_BORDER = 0xFF34483D;
    private static final int INFO_BACKGROUND = 0xF0202824;
    private static final int INFO_BORDER = 0xFF617569;
    private static final int ROUTE_COLOR = 0xFFD8BA70;
    private static final int TOWN_BORDER = 0xFFF0A43B;
    private static final int SELECTED_BORDER = 0xFFF2F5EF;
    private static final int PLAYER_MARKER = 0xFFFFD166;
    private static final int TEXT = 0xFFF3F5F1;
    private static final int MUTED_TEXT = 0xFFAAB8B0;
    private static final int SUCCESS_TEXT = 0xFF91C7A2;
    private static final int WARNING_TEXT = 0xFFF0A43B;
    private static final int MARGIN = 14;
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 32;
    private static final int PANEL_GAP = 9;

    private final Screen parent;
    private final MapContent content = MapContent.instance();
    private MapContent.Hex selected = new MapContent.Hex(0, 0);
    private Button teleportButton;
    private long stateRevision = -1L;
    private int pokemonScroll;

    public WorldMapScreen(Screen parent) {
        super(Component.translatable("screen.cobbleventure_player_menu.world_map.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        selected = playerHex();
        Layout layout = layout();
        teleportButton = addRenderableWidget(Button.builder(
            Component.translatable("screen.cobbleventure_player_menu.world_map.teleport"),
            ignored -> requestTeleport()
        ).bounds(layout.infoLeft() + 9, layout.bottom() - 28, layout.infoWidth() - 18, 20).build());
        Component closeLabel = Component.translatable(
            parent == null
                ? "screen.cobbleventure_player_menu.world_map.close"
                : "screen.cobbleventure_player_menu.world_map.back"
        );
        addRenderableWidget(Button.builder(closeLabel, ignored -> onClose())
            .bounds(width - MARGIN - 72, height - FOOTER_HEIGHT + 5, 72, 20)
            .build());
        MapNetwork.requestSnapshot();
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
        graphics.drawCenteredString(font, title, width / 2, 11, TEXT);
        Layout layout = layout();
        drawMap(graphics, layout, mouseX, mouseY);
        drawInfoPanel(graphics, layout);
        graphics.drawString(
            font,
            Component.translatable("screen.cobbleventure_player_menu.world_map.hint"),
            MARGIN,
            height - FOOTER_HEIGHT + 10,
            MUTED_TEXT,
            false
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        Layout layout = layout();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && layout.mapContains(mouseX, mouseY)) {
            MapContent.Hex candidate = screenToHex(layout, mouseX, mouseY);
            if (content.contains(candidate.q(), candidate.r())) {
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
        if (next != null && content.contains(next.q(), next.r())) {
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
        if (mouseX >= layout.infoLeft() && mouseX < layout.infoRight()
            && mouseY >= layout.top() && mouseY < layout.bottom()
            && content.townAt(selected.q(), selected.r()) == null) {
            MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());
            if (tile != null) {
                int count = content.biome(tile.biome()).pokemon().size();
                pokemonScroll = Math.max(0, Math.min(Math.max(0, count - 1), pokemonScroll - (int) Math.signum(scrollY)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    private void drawMap(GuiGraphics graphics, Layout layout, int mouseX, int mouseY) {
        graphics.fill(layout.mapLeft(), layout.top(), layout.mapRight(), layout.bottom(), MAP_BACKGROUND);
        drawBorder(graphics, layout.mapLeft(), layout.top(), layout.mapRight(), layout.bottom(), INFO_BORDER);
        int size = hexSize(layout);
        ScreenPoint center = mapCenter(layout);

        for (int r = -content.mapRadiusCells(); r <= content.mapRadiusCells(); r++) {
            int minQ = Math.max(-content.mapRadiusCells(), -r - content.mapRadiusCells());
            int maxQ = Math.min(content.mapRadiusCells(), -r + content.mapRadiusCells());
            for (int q = minQ; q <= maxQ; q++) {
                ScreenPoint point = hexCenter(center, size, q, r);
                MapContent.Town town = content.townAt(q, r);
                MapContent.BiomeTile tile = content.tileAt(q, r);
                String biome = town != null ? town.biome() : tile == null ? "" : tile.biome();
                int fill = biome.isEmpty() ? EMPTY_TILE : biomeColor(biome);
                int border = town != null ? TOWN_BORDER : EMPTY_BORDER;
                drawHex(graphics, point.x(), point.y(), size, border, fill);
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
        }

        ScreenPoint selectedPoint = hexCenter(center, size, selected.q(), selected.r());
        drawHexOutline(graphics, selectedPoint.x(), selectedPoint.y(), size + 1, SELECTED_BORDER);
        MapContent.Hex playerHex = playerHex();
        if (playerOnMappedDimension() && content.contains(playerHex.q(), playerHex.r())) {
            ScreenPoint player = hexCenter(center, size, playerHex.q(), playerHex.r());
            graphics.fill(player.x() - 2, player.y() - 2, player.x() + 3, player.y() + 3, PLAYER_MARKER);
        }

        if (layout.mapContains(mouseX, mouseY)) {
            MapContent.Hex hover = screenToHex(layout, mouseX, mouseY);
            if (content.contains(hover.q(), hover.r())) {
                ScreenPoint point = hexCenter(center, size, hover.q(), hover.r());
                drawHexOutline(graphics, point.x(), point.y(), size, 0x99FFFFFF);
            }
        }
    }

    private void drawInfoPanel(GuiGraphics graphics, Layout layout) {
        graphics.fill(layout.infoLeft(), layout.top(), layout.infoRight(), layout.bottom(), INFO_BACKGROUND);
        drawBorder(graphics, layout.infoLeft(), layout.top(), layout.infoRight(), layout.bottom(), INFO_BORDER);
        int x = layout.infoLeft() + 10;
        int y = layout.top() + 9;
        int lineWidth = layout.infoWidth() - 20;
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        MapContent.Town town = content.townAt(selected.q(), selected.r());
        MapContent.BiomeTile tile = content.tileAt(selected.q(), selected.r());

        graphics.drawString(font, "Q " + selected.q() + " · R " + selected.r(), x, y, MUTED_TEXT, false);
        y += 15;
        if (town != null) {
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
            if (!visited && !snapshot.administrator()) {
                y += 4;
                graphics.drawWordWrap(font, Component.literal("이 마을을 직접 방문하면 빠른 이동이 해금됩니다."), x, y, lineWidth, MUTED_TEXT);
            }
        } else if (tile != null) {
            MapContent.BiomeInfo biome = content.biome(tile.biome());
            graphics.drawString(font, biome.name(), x, y, TEXT, false);
            y += 15;
            graphics.drawString(font, tile.biome(), x, y, MUTED_TEXT, false);
            y += 19;
            graphics.drawString(font, "서식 포켓몬 " + biome.totalPokemon() + "종 · 휠", x, y, TEXT, false);
            y += 14;
            int index = Math.min(pokemonScroll, Math.max(0, biome.pokemon().size() - 1));
            for (; index < biome.pokemon().size(); index++) {
                if (y > layout.bottom() - 42) break;
                MapContent.Pokemon pokemon = biome.pokemon().get(index);
                graphics.drawString(font, String.format("#%04d %s", pokemon.dexNumber(), pokemon.name()), x, y, MUTED_TEXT, false);
                y += 11;
            }
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

    private void requestTeleport() {
        if (teleportButton == null || !teleportButton.active) return;
        teleportButton.active = false;
        teleportButton.setMessage(Component.translatable("screen.cobbleventure_player_menu.world_map.teleporting"));
        MapNetwork.requestTeleport(selected.q(), selected.r());
    }

    private void updateTeleportButton() {
        if (teleportButton == null) return;
        MapNetwork.ClientSnapshot snapshot = MapNetwork.clientSnapshot();
        MapContent.Town town = content.townAt(selected.q(), selected.r());
        boolean permitted = snapshot.administrator()
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
        if (minecraft != null && minecraft.player != null && playerOnMappedDimension()) {
            MapContent.Hex current = content.worldToHex(minecraft.player.getX(), minecraft.player.getZ());
            if (content.contains(current.q(), current.r())) return current;
        }
        if (!content.towns().isEmpty()) return content.towns().getFirst().hex();
        return new MapContent.Hex(0, 0);
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
        double horizontal = layout.mapWidth() / (2.0D * Math.sqrt(3.0D) * content.mapRadiusCells() + 3.0D);
        double vertical = layout.height() / (3.0D * content.mapRadiusCells() + 3.0D);
        return Math.max(3, Math.min(12, (int) Math.floor(Math.min(horizontal, vertical))));
    }

    private ScreenPoint mapCenter(Layout layout) {
        return new ScreenPoint((layout.mapLeft() + layout.mapRight()) / 2, (layout.top() + layout.bottom()) / 2);
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

    private static void drawBorder(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private record ScreenPoint(int x, int y) {}
    private record Layout(int mapLeft, int mapRight, int infoLeft, int infoRight, int top, int bottom) {
        int mapWidth() { return mapRight - mapLeft; }
        int infoWidth() { return infoRight - infoLeft; }
        int height() { return bottom - top; }
        boolean mapContains(double x, double y) { return x >= mapLeft && x < mapRight && y >= top && y < bottom; }
    }
}
