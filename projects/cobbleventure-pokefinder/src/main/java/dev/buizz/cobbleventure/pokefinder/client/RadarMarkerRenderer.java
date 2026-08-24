package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
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
                if (point.visible()) drawTestIcon(graphics, point);
            }
            graphics.pose().popPose();
        });
    }

    private static void drawTestIcon(
        GuiGraphics graphics,
        Cobblenav233LayoutAdapter.RadarPoint point
    ) {
        int x = (int) Math.floor(point.x());
        int y = (int) Math.floor(point.y());
        int color = point.edgePinned() ? 0xFFFFC44D : 0xFF62E6FF;
        graphics.fill(x - 2, y, x + 3, y + 1, 0xFF101820);
        graphics.fill(x, y - 2, x + 1, y + 3, 0xFF101820);
        graphics.fill(x - 1, y, x + 2, y + 1, color);
        graphics.fill(x, y - 1, x + 1, y + 2, color);
    }
}
