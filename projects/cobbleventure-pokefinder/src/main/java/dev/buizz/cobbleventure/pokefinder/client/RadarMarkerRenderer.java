package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Draws server snapshots over the already-rendered CobbleNav radar. */
public final class RadarMarkerRenderer {
    private static final Comparator<RadarMarker> RENDER_ORDER = Comparator
        .comparingInt(RadarMarker::priority);
    private static final boolean DEBUG_MARKER = Boolean.getBoolean(
        "cobbleventure.pokefinder.testMarker"
    );

    private RadarMarkerRenderer() {}

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        Cobblenav233LayoutAdapter.radarLayout(minecraft).ifPresent(layout -> {
            List<RadarMarker> markers = new ArrayList<>(RadarMarkerSnapshot.markers());
            if (DEBUG_MARKER) markers.add(DebugMarker.create(player));
            markers.sort(RENDER_ORDER);

            graphics.pose().pushPose();
            graphics.pose().scale(layout.scale(), layout.scale(), 1.0F);
            ResourceLocation dimension = player.level().dimension().location();
            Vec3 playerPosition = player.position();
            for (RadarMarker marker : markers) {
                if (!marker.dimension().equals(dimension)) continue;
                Cobblenav233LayoutAdapter.RadarPoint point =
                    Cobblenav233LayoutAdapter.worldToRadar(
                        layout,
                        marker.position().x - playerPosition.x,
                        marker.position().z - playerPosition.z,
                        player.getYRot(),
                        marker.localRange(),
                        marker.edgeTracking()
                    );
                if (point.visible()) drawMarkerIcon(graphics, marker, point);
            }
            graphics.pose().popPose();
        });
    }

    private static void drawMarkerIcon(
        GuiGraphics graphics,
        RadarMarker marker,
        Cobblenav233LayoutAdapter.RadarPoint point
    ) {
        int x = (int) Math.floor(point.x());
        int y = (int) Math.floor(point.y());
        int color = markerColor(marker);
        if (point.edgePinned()) {
            graphics.fill(x - 3, y, x + 4, y + 1, 0xFFFFC44D);
            graphics.fill(x, y - 3, x + 1, y + 4, 0xFFFFC44D);
        }
        switch (marker.type()) {
            case TRAINER -> {
                graphics.fill(x - 2, y - 2, x - 1, y + 3, color);
                graphics.fill(x + 2, y - 2, x + 3, y + 3, color);
                graphics.fill(x - 1, y - 1, x + 2, y, color);
                graphics.fill(x - 1, y + 1, x + 2, y + 2, color);
            }
            case POKEMON_CENTER -> {
                graphics.fill(x - 2, y - 1, x + 3, y + 2, 0xFF101820);
                graphics.fill(x - 1, y - 2, x + 2, y + 3, 0xFF101820);
                graphics.fill(x - 1, y, x + 2, y + 1, color);
                graphics.fill(x, y - 1, x + 1, y + 2, color);
            }
            case POKEMART, SPECIAL_BUILDING -> {
                graphics.fill(x - 2, y - 2, x + 3, y + 3, 0xFF101820);
                graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
            }
            case GYM_LEADER -> {
                graphics.fill(x - 2, y + 1, x + 3, y + 3, 0xFF101820);
                graphics.fill(x - 2, y - 2, x - 1, y + 1, color);
                graphics.fill(x, y - 1, x + 1, y + 1, color);
                graphics.fill(x + 2, y - 2, x + 3, y + 1, color);
                graphics.fill(x - 1, y + 1, x + 2, y + 2, color);
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
            default -> {
                graphics.fill(x - 2, y, x + 3, y + 1, 0xFF101820);
                graphics.fill(x, y - 2, x + 1, y + 3, 0xFF101820);
                graphics.fill(x, y, x + 1, y + 1, color);
            }
        }
        if (marker.state() == RadarMarkerState.DEFEATED
            || marker.state() == RadarMarkerState.COMPLETED) {
            graphics.fill(x - 3, y + 1, x - 1, y + 2, 0xFFF2F7F5);
            graphics.fill(x - 2, y + 2, x, y + 3, 0xFFF2F7F5);
            graphics.fill(x - 1, y, x + 2, y + 1, 0xFFF2F7F5);
        }
    }

    private static int markerColor(RadarMarker marker) {
        if (marker.state() == RadarMarkerState.DEFEATED) return 0xFF68717D;
        if (marker.state() == RadarMarkerState.COMPLETED) return 0xFF668276;
        return switch (marker.type()) {
            case TRAINER -> 0xFFFF8A65;
            case GYM_LEADER -> 0xFFFFD35A;
            case IMPORTANT_NPC -> 0xFFB89CFF;
            case POKEMON_CENTER -> 0xFFFF6B78;
            case POKEMART -> 0xFF69A7FF;
            case CASINO -> 0xFFFFC44D;
            case CAVE_ENTRANCE -> 0xFFC3A27A;
            case FOREST_ENTRANCE -> 0xFF5FD36D;
            case GATE -> 0xFFD7DEE8;
            default -> 0xFF62E6FF;
        };
    }
}
