package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.CobbleventurePokefinder;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

final class DebugMarker {
    private DebugMarker() {}

    static RadarMarker create(LocalPlayer player) {
        return new RadarMarker(
            ResourceLocation.fromNamespaceAndPath(CobbleventurePokefinder.MOD_ID, "test_marker"),
            RadarMarkerType.OBJECTIVE,
            player.level().dimension().location(),
            player.position().add(96.0D, 0.0D, 0.0D),
            "cobbleventure.pokefinder.test_marker",
            ResourceLocation.fromNamespaceAndPath(CobbleventurePokefinder.MOD_ID, "test_marker"),
            1_000,
            RadarMarkerState.PRIMARY,
            "debug",
            Cobblenav233LayoutAdapter.DEFAULT_LOCAL_RANGE,
            true
        );
    }
}
