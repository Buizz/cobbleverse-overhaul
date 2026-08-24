package dev.buizz.cobbleventure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Read-only view of actual world-runtime placements for HUD integrations. */
public final class RadarLocationCatalog {
    private RadarLocationCatalog() {}

    public static List<Location> locations(ServerPlayer player) {
        ResourceLocation dimension = player.serverLevel().dimension().location();
        List<Location> result = new ArrayList<>();
        result.addAll(BuildingRuntimeSystem.radarLocations(player.getServer(), dimension));
        result.addAll(CobbleventureBootstrap.radarWorldLocations(player.serverLevel()));
        return List.copyOf(result);
    }

    public static Kind buildingKind(String structure) {
        String normalized = structure.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("pokemon_center")) return Kind.POKEMON_CENTER;
        if (normalized.contains("pokemart")) return Kind.POKEMART;
        if (normalized.contains("casino")) return Kind.CASINO;
        if (normalized.contains("gym")) return Kind.GYM;
        return Kind.SPECIAL_BUILDING;
    }

    public enum Kind {
        GYM,
        POKEMON_CENTER,
        POKEMART,
        CASINO,
        SPECIAL_BUILDING,
        CAVE_ENTRANCE,
        FOREST_ENTRANCE,
        GATE
    }

    public record Location(
        String id,
        Kind kind,
        ResourceLocation dimension,
        double x,
        double y,
        double z,
        String label,
        String areaId
    ) {}
}
