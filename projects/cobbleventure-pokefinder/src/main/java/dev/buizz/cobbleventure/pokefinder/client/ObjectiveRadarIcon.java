package dev.buizz.cobbleventure.pokefinder.client;

import java.util.ArrayList;
import java.util.List;

/** Directional objective arrow rasterized at the Pokefinder's native pixel scale. */
final class ObjectiveRadarIcon {
    private static final List<Pixel> RIGHT_FACING = List.of(
        new Pixel(-5, -2, false), new Pixel(-5, 2, false),
        new Pixel(-3, -1, true), new Pixel(-3, 1, true),
        new Pixel(-1, 0, true), new Pixel(0, 0, true),
        new Pixel(1, -2, true), new Pixel(1, -1, true), new Pixel(1, 0, true),
        new Pixel(1, 1, true), new Pixel(1, 2, true),
        new Pixel(2, -1, true), new Pixel(2, 0, true), new Pixel(2, 1, true),
        new Pixel(3, 0, true)
    );

    private ObjectiveRadarIcon() {}

    static List<Pixel> oriented(double deltaX, double deltaY) {
        double length = Math.hypot(deltaX, deltaY);
        double cosine = length == 0.0D ? 0.0D : deltaX / length;
        double sine = length == 0.0D ? -1.0D : deltaY / length;
        List<Pixel> result = new ArrayList<>(RIGHT_FACING.size());
        for (Pixel pixel : RIGHT_FACING) {
            result.add(new Pixel(
                (int) Math.round(pixel.x() * cosine - pixel.y() * sine),
                (int) Math.round(pixel.x() * sine + pixel.y() * cosine),
                pixel.head()
            ));
        }
        return List.copyOf(result);
    }

    record Pixel(int x, int y, boolean head) {}
}
