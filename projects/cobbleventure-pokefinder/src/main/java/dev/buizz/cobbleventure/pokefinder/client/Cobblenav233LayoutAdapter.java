package dev.buizz.cobbleventure.pokefinder.client;

import com.metacontent.cobblenav.client.CobblenavClient;
import com.metacontent.cobblenav.config.ClientCobblenavConfig;
import com.metacontent.cobblenav.item.Pokefinder;
import dev.buizz.cobbleventure.pokefinder.marker.RadarRanges;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/** All direct CobbleNav 2.3.3 layout and item assumptions live in this adapter. */
public final class Cobblenav233LayoutAdapter {
    public static final int WIDTH = 145;
    public static final int HEIGHT = 97;
    public static final double RADAR_SCALE = 0.55D;
    public static final double DEFAULT_LOCAL_RANGE = RadarRanges.DEFAULT_LOCAL;
    public static final double MAX_FALLBACK_RANGE = RadarRanges.MAX_FALLBACK;

    private Cobblenav233LayoutAdapter() {}

    public static Optional<Layout> radarLayout(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || CobblenavClient.INSTANCE.getPokefinderSettings() == null) {
            return Optional.empty();
        }
        boolean mainHand = isPokefinder(player.getMainHandItem());
        if (!mainHand && !isPokefinder(player.getOffhandItem())) {
            return Optional.empty();
        }

        ClientCobblenavConfig config = CobblenavClient.INSTANCE.getConfig();
        float scale = config.getPokefinderOverlayScale();
        int scaledWidth = (int) (minecraft.getWindow().getGuiScaledWidth() / scale);
        int scaledHeight = (int) (minecraft.getWindow().getGuiScaledHeight() / scale);
        int left = mainHand
            ? scaledWidth - WIDTH - config.getPokefinderOverlayOffsetX()
            : config.getPokefinderOverlayOffsetX();
        int top = scaledHeight - HEIGHT - config.getPokefinderOverlayOffsetY();
        return Optional.of(new Layout(left, top, scale, mainHand));
    }

    public static RadarPoint worldToRadar(
        Layout layout,
        double deltaX,
        double deltaZ,
        float playerYaw,
        double localRange,
        boolean edgeTracking
    ) {
        double distance = Math.hypot(deltaX, deltaZ);
        if (distance > localRange && !edgeTracking) return RadarPoint.hidden();
        if (distance > MAX_FALLBACK_RANGE) return RadarPoint.hidden();

        double angle = Math.toRadians(180.0D - playerYaw);
        double radarX = deltaX * Math.cos(angle) - deltaZ * Math.sin(angle);
        double radarY = deltaX * Math.sin(angle) + deltaZ * Math.cos(angle);
        boolean edgePinned = distance > localRange;
        if (edgePinned && distance > 0.0D) {
            double edgeRadius = DEFAULT_LOCAL_RANGE * RADAR_SCALE;
            radarX = radarX / distance * edgeRadius;
            radarY = radarY / distance * edgeRadius;
        } else {
            radarX *= RADAR_SCALE;
            radarY *= RADAR_SCALE;
        }

        return new RadarPoint(
            layout.left() + WIDTH / 2.0D - 1.0D + radarX,
            layout.top() + HEIGHT / 2.0D - 1.0D + radarY,
            true,
            edgePinned
        );
    }

    private static boolean isPokefinder(ItemStack stack) {
        return stack.getItem() instanceof Pokefinder;
    }

    public record Layout(int left, int top, float scale, boolean mainHand) {}

    public record RadarPoint(double x, double y, boolean visible, boolean edgePinned) {
        private static RadarPoint hidden() {
            return new RadarPoint(0.0D, 0.0D, false, false);
        }
    }
}
