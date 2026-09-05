package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.CobbleventurePokefinder;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import dev.buizz.cobbleventure.pokefinder.marker.RadarRanges;
import dev.buizz.cobbleventure.pokefinder.server.RadarIconSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Draws server snapshots over the already-rendered CobbleNav radar. */
public final class RadarMarkerRenderer {
    private static final boolean DEBUG_MARKER = Boolean.getBoolean(
        "cobbleventure.pokefinder.testMarker"
    );
    private static final boolean VISUAL_REGRESSION = Boolean.getBoolean(
        RadarVisualRegressionScenario.SYSTEM_PROPERTY
    );

    private RadarMarkerRenderer() {}

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        Cobblenav233LayoutAdapter.radarLayout(minecraft).ifPresent(layout -> {
            List<RadarMarker> markers = new ArrayList<>(RadarMarkerSnapshot.markers());
            for (AbstractClientPlayer other : player.clientLevel.players()) {
                if (other == player || other.isSpectator() || other.isInvisible()) continue;
                markers.add(playerMarker(other));
            }
            if (DEBUG_MARKER) markers.add(DebugMarker.create(player));
            if (VISUAL_REGRESSION) {
                markers.addAll(RadarVisualRegressionScenario.create(
                    player.level().dimension().location(), player.position()
                ));
            }

            graphics.pose().pushPose();
            graphics.pose().scale(layout.scale(), layout.scale(), 1.0F);
            ResourceLocation dimension = player.level().dimension().location();
            Vec3 playerPosition = player.position();
            List<RadarMarkerLayout.Candidate> candidates = new ArrayList<>();
            for (RadarMarker marker : markers) {
                if (!marker.dimension().equals(dimension)) continue;
                if (!RadarVisualRegressionScenario.contains(marker)
                    && !RadarDisplaySettings.visible(marker)) continue;
                Cobblenav233LayoutAdapter.RadarPoint point =
                    Cobblenav233LayoutAdapter.worldToRadar(
                        layout,
                        marker.position().x - playerPosition.x,
                        marker.position().z - playerPosition.z,
                        player.getYRot(),
                        marker.localRange(),
                        marker.type() == RadarMarkerType.OBJECTIVE
                            ? Double.POSITIVE_INFINITY
                            : Cobblenav233LayoutAdapter.MAX_FALLBACK_RANGE,
                        marker.edgeTracking()
                    );
                if (point.visible()) candidates.add(
                    new RadarMarkerLayout.Candidate(marker, point)
                );
            }
            for (RadarMarkerLayout.Placed placed : RadarMarkerLayout.resolve(candidates)) {
                drawMarkerIcon(graphics, layout, placed.marker(), placed.point());
                drawOverlapIndicator(graphics, placed);
                drawMarkerDetails(
                    graphics, minecraft, layout, placed.marker(),
                    placed.point(), playerPosition
                );
            }
            graphics.pose().popPose();
        });
    }

    private static RadarMarker playerMarker(AbstractClientPlayer player) {
        RadarIconSettings.Entry icon = RadarIconSettings.resolve("PLAYER", "player");
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
            CobbleventurePokefinder.MOD_ID,
            "player/" + player.getUUID().toString().toLowerCase(java.util.Locale.ROOT)
        );
        return new RadarMarker(
            id,
            RadarMarkerType.PLAYER,
            player.level().dimension().location(),
            player.position(),
            player.getGameProfile().getName(),
            ResourceLocation.fromNamespaceAndPath(
                CobbleventurePokefinder.MOD_ID, "radar/" + icon.icon()
            ),
            0,
            RadarMarkerState.AVAILABLE,
            "",
            RadarRanges.DEFAULT_LOCAL,
            false
        );
    }

    private static void drawMarkerDetails(
        GuiGraphics graphics, Minecraft minecraft,
        Cobblenav233LayoutAdapter.Layout layout, RadarMarker marker,
        Cobblenav233LayoutAdapter.RadarPoint point, Vec3 playerPosition
    ) {
        boolean names = marker.type() == RadarMarkerType.PLAYER
            || RadarDisplaySettings.isLandmark(marker)
            || RadarDisplaySettings.value(RadarDisplaySettings.Option.NAMES);
        boolean distances = RadarDisplaySettings.value(
            RadarDisplaySettings.Option.DISTANCES
        );
        if (!names && !distances) return;
        StringBuilder text = new StringBuilder();
        if (names) text.append(marker.label());
        if (distances) {
            if (!text.isEmpty()) text.append(" · ");
            double dx = marker.position().x - playerPosition.x;
            double dz = marker.position().z - playerPosition.z;
            text.append(Math.round(Math.hypot(dx, dz))).append('m');
        }
        int textWidth = minecraft.font.width(text.toString());
        int x = (int) Math.floor(point.x()) + 4;
        if (x + textWidth > layout.left() + Cobblenav233LayoutAdapter.WIDTH - 3) {
            x = (int) Math.floor(point.x()) - 4 - textWidth;
        }
        int y = Math.max(
            layout.top() + 2,
            Math.min(
                (int) Math.floor(point.y()) - 4,
                layout.top() + Cobblenav233LayoutAdapter.HEIGHT - 10
            )
        );
        graphics.drawString(
            minecraft.font, text.toString(),
            x, y,
            0xFFF2F7FF, true
        );
    }

    private static void drawOverlapIndicator(
        GuiGraphics graphics, RadarMarkerLayout.Placed placed
    ) {
        if (placed.overlapCount() <= 0) return;
        int x = (int) Math.floor(placed.point().x());
        int y = (int) Math.floor(placed.point().y());
        graphics.fill(x + 3, y - 3, x + 5, y - 2, 0xFFF2F7FF);
        graphics.fill(x + 4, y - 4, x + 5, y - 1, 0xFFF2F7FF);
    }

    private static void drawMarkerIcon(
        GuiGraphics graphics,
        Cobblenav233LayoutAdapter.Layout layout,
        RadarMarker marker,
        Cobblenav233LayoutAdapter.RadarPoint point
    ) {
        int x = (int) Math.floor(point.x());
        int y = (int) Math.floor(point.y());
        int color = markerColor(marker);
        if (point.edgePinned() && marker.type() != RadarMarkerType.OBJECTIVE) {
            graphics.fill(x - 3, y, x + 4, y + 1, 0xFFFFC44D);
            graphics.fill(x, y - 3, x + 1, y + 4, 0xFFFFC44D);
        }
        RadarIconSettings.Entry authored = authoredIcon(marker);
        if (authored != null && !authored.pixels().isEmpty()) {
            drawAuthoredIcon(graphics, x, y, marker, authored);
            drawCompletionMark(graphics, x, y, marker);
            return;
        }
        switch (iconType(marker)) {
            case PLAYER -> {
                graphics.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101820);
                graphics.fill(x - 2, y - 2, x + 3, y + 1, color);
                graphics.fill(x - 1, y + 1, x + 2, y + 3, color);
                graphics.fill(x - 1, y - 1, x, y, 0xFF101820);
                graphics.fill(x + 1, y - 1, x + 2, y, 0xFF101820);
            }
            case TRAINER -> {
                for (int row = 0; row < TrainerRadarIcon.PIXELS.size(); row++) {
                    String pixels = TrainerRadarIcon.PIXELS.get(row);
                    for (int column = 0; column < pixels.length(); column++) {
                        char pixel = pixels.charAt(column);
                        if (pixel == '.') continue;
                        graphics.fill(x + column - 4, y + row - 4,
                            x + column - 3, y + row - 3,
                            pixel == '#' ? 0xFF101820 : color);
                    }
                }
            }
            case POKEMON_CENTER -> {
                drawFacilityPlate(graphics, x, y, color);
                graphics.fill(x - 3, y - 1, x + 4, y + 2, 0xFFF7FBFF);
                graphics.fill(x - 1, y - 3, x + 2, y + 4, 0xFFF7FBFF);
            }
            case POKEMART -> {
                drawFacilityPlate(graphics, x, y, color);
                // A compact white M remains legible at the radar's native scale.
                graphics.fill(x - 3, y - 3, x - 2, y + 4, 0xFFF7FBFF);
                graphics.fill(x + 2, y - 3, x + 3, y + 4, 0xFFF7FBFF);
                graphics.fill(x - 2, y - 2, x - 1, y, 0xFFF7FBFF);
                graphics.fill(x + 1, y - 2, x + 2, y, 0xFFF7FBFF);
                graphics.fill(x - 1, y - 1, x + 2, y + 1, 0xFFF7FBFF);
            }
            case SPECIAL_BUILDING -> {
                for (int row = 0; row < DoorRadarIcon.PIXELS.size(); row++) {
                    String pixels = DoorRadarIcon.PIXELS.get(row);
                    for (int column = 0; column < pixels.length(); column++) {
                        char pixel = pixels.charAt(column);
                        if (pixel == '.') continue;
                        int pixelColor = switch (pixel) {
                            case '#' -> 0xFF101820;
                            case 'o' -> 0xFFF7FBFF;
                            default -> color;
                        };
                        graphics.fill(x + column - 4, y + row - 4,
                            x + column - 3, y + row - 3, pixelColor);
                    }
                }
            }
            case GYM_LEADER -> {
                if (RadarDisplaySettings.isLandmark(marker)) {
                    drawFacilityPlate(graphics, x, y, color);
                    // Dark pixel G distinguishes a gym entrance from its leader NPC.
                    graphics.fill(x - 2, y - 3, x + 3, y - 2, 0xFF101820);
                    graphics.fill(x - 3, y - 2, x - 2, y + 3, 0xFF101820);
                    graphics.fill(x - 2, y + 2, x + 3, y + 3, 0xFF101820);
                    graphics.fill(x, y, x + 3, y + 1, 0xFF101820);
                    graphics.fill(x + 2, y, x + 3, y + 3, 0xFF101820);
                } else {
                    graphics.fill(x - 2, y + 1, x + 3, y + 3, 0xFF101820);
                    graphics.fill(x - 2, y - 2, x - 1, y + 1, color);
                    graphics.fill(x, y - 1, x + 1, y + 1, color);
                    graphics.fill(x + 2, y - 2, x + 3, y + 1, color);
                    graphics.fill(x - 1, y + 1, x + 2, y + 2, color);
                }
            }
            case CASINO -> {
                graphics.fill(x, y - 3, x + 1, y + 4, 0xFF101820);
                graphics.fill(x - 3, y, x + 4, y + 1, 0xFF101820);
                graphics.fill(x, y - 2, x + 1, y + 3, color);
                graphics.fill(x - 2, y, x + 3, y + 1, color);
            }
            case CAVE_ENTRANCE -> {
                graphics.fill(x - 3, y + 2, x + 4, y + 3, 0xFF101820);
                graphics.fill(x - 2, y, x + 3, y + 2, color);
                graphics.fill(x - 1, y - 2, x + 2, y, color);
            }
            case FOREST_ENTRANCE -> {
                graphics.fill(x, y, x + 1, y + 3, 0xFF6B4423);
                graphics.fill(x - 2, y - 1, x + 3, y + 1, color);
                graphics.fill(x - 1, y - 3, x + 2, y - 1, color);
            }
            case DUNGEON_ENTRANCE -> {
                // Violet arch with descending steps, distinct from the brown cave peak.
                graphics.fill(x - 4, y - 4, x + 5, y + 5, 0xFF101820);
                graphics.fill(x - 2, y - 3, x + 3, y - 2, color);
                graphics.fill(x - 3, y - 2, x - 2, y + 4, color);
                graphics.fill(x + 3, y - 2, x + 4, y + 4, color);
                graphics.fill(x - 1, y, x + 1, y + 1, color);
                graphics.fill(x, y + 1, x + 2, y + 2, color);
                graphics.fill(x + 1, y + 2, x + 3, y + 3, color);
            }
            case GATE -> {
                graphics.fill(x - 2, y - 2, x - 1, y + 3, color);
                graphics.fill(x + 2, y - 2, x + 3, y + 3, color);
                graphics.fill(x - 1, y - 2, x + 2, y - 1, color);
            }
            case IMPORTANT_NPC -> {
                graphics.fill(x, y - 3, x + 1, y + 1, color);
                graphics.fill(x, y + 2, x + 1, y + 3, color);
                graphics.fill(x - 1, y - 2, x + 2, y, color);
            }
            case OBJECTIVE -> {
                double centerX = layout.left()
                    + Cobblenav233LayoutAdapter.WIDTH / 2.0D - 1.0D;
                double centerY = layout.top()
                    + Cobblenav233LayoutAdapter.HEIGHT / 2.0D - 1.0D;
                List<ObjectiveRadarIcon.Pixel> pixels = ObjectiveRadarIcon.oriented(
                    point.x() - centerX, point.y() - centerY
                );
                for (ObjectiveRadarIcon.Pixel pixel : pixels) {
                    graphics.fill(
                        x + pixel.x() - 1, y + pixel.y() - 1,
                        x + pixel.x() + 2, y + pixel.y() + 2,
                        0xFF101820
                    );
                }
                for (ObjectiveRadarIcon.Pixel pixel : pixels) {
                    graphics.fill(
                        x + pixel.x(), y + pixel.y(),
                        x + pixel.x() + 1, y + pixel.y() + 1,
                        pixel.head() ? color : 0xFFFFC44D
                    );
                }
            }
            default -> {
                graphics.fill(x - 2, y, x + 3, y + 1, 0xFF101820);
                graphics.fill(x, y - 2, x + 1, y + 3, 0xFF101820);
                graphics.fill(x, y, x + 1, y + 1, color);
            }
        }
        drawCompletionMark(graphics, x, y, marker);
    }

    private static RadarIconSettings.Entry authoredIcon(RadarMarker marker) {
        String path = marker.icon().getPath();
        return RadarIconSettings.style(path.substring(path.lastIndexOf('/') + 1));
    }

    private static void drawAuthoredIcon(
        GuiGraphics graphics, int x, int y, RadarMarker marker,
        RadarIconSettings.Entry style
    ) {
        int primary = marker.state() == RadarMarkerState.DEFEATED ? 0xFF68717D
            : marker.state() == RadarMarkerState.COMPLETED ? 0xFF668276 : style.primary();
        for (int row = 0; row < style.pixels().size(); row++) {
            String pixels = style.pixels().get(row);
            for (int column = 0; column < pixels.length(); column++) {
                int color = switch (pixels.charAt(column)) {
                    case '#' -> style.outline();
                    case 'x' -> primary;
                    case 'o' -> style.secondary();
                    default -> 0;
                };
                if (color != 0) graphics.fill(
                    x + column - 4, y + row - 4,
                    x + column - 3, y + row - 3, color
                );
            }
        }
    }

    private static void drawCompletionMark(
        GuiGraphics graphics, int x, int y, RadarMarker marker
    ) {
        if (marker.state() != RadarMarkerState.DEFEATED
            && marker.state() != RadarMarkerState.COMPLETED) return;
        if (marker.type() == RadarMarkerType.TRAINER) x += 4;
        graphics.fill(x - 3, y + 1, x - 1, y + 2, 0xFFF2F7F5);
        graphics.fill(x - 2, y + 2, x, y + 3, 0xFFF2F7F5);
        graphics.fill(x - 1, y, x + 2, y + 1, 0xFFF2F7F5);
    }

    private static void drawFacilityPlate(
        GuiGraphics graphics, int x, int y, int color
    ) {
        graphics.fill(x - 5, y - 4, x + 6, y + 5, 0xFF101820);
        graphics.fill(x - 4, y - 5, x + 5, y + 6, 0xFF101820);
        graphics.fill(x - 4, y - 3, x + 5, y + 4, color);
        graphics.fill(x - 3, y - 4, x + 4, y + 5, color);
    }

    private static int markerColor(RadarMarker marker) {
        if (marker.state() == RadarMarkerState.DEFEATED) return 0xFF68717D;
        if (marker.state() == RadarMarkerState.COMPLETED) return 0xFF668276;
        return switch (iconType(marker)) {
            case PLAYER -> 0xFF53E1D4;
            case TRAINER -> 0xFFFF8A65;
            case GYM_LEADER -> 0xFFFFD35A;
            case IMPORTANT_NPC -> 0xFFB89CFF;
            case OBJECTIVE -> 0xFFFFFFFF;
            case POKEMON_CENTER -> 0xFFFF6B78;
            case POKEMART -> 0xFF69A7FF;
            case CASINO -> 0xFFFFC44D;
            case CAVE_ENTRANCE -> 0xFFC3A27A;
            case DUNGEON_ENTRANCE -> 0xFFB89CFF;
            case FOREST_ENTRANCE -> 0xFF5FD36D;
            case GATE -> 0xFFD7DEE8;
            default -> 0xFF62E6FF;
        };
    }

    private static RadarMarkerType iconType(RadarMarker marker) {
        String path = marker.icon().getPath();
        String icon = path.substring(path.lastIndexOf('/') + 1).toUpperCase(java.util.Locale.ROOT);
        try {
            return RadarMarkerType.valueOf(icon);
        } catch (IllegalArgumentException ignored) {
            return marker.type();
        }
    }
}
