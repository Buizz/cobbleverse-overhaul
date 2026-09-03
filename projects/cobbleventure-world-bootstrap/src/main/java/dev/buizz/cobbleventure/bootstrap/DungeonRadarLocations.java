package dev.buizz.cobbleventure.bootstrap;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Projects actual entry triggers into stable, same-dimension radar locations. */
final class DungeonRadarLocations {
    private DungeonRadarLocations() {}

    static List<RadarLocationCatalog.Location> locations(
        ResourceLocation dimension, Collection<Entrance> entrances
    ) {
        return entrances.stream()
            .filter(entrance -> entrance.dimension().equals(dimension))
            .sorted(Comparator.comparing(Entrance::entranceId))
            .map(entrance -> new RadarLocationCatalog.Location(
                "dungeon/" + entrance.entranceId(),
                RadarLocationCatalog.Kind.DUNGEON_ENTRANCE,
                entrance.dimension(),
                entrance.trigger().getX() + 0.5D,
                entrance.trigger().getY(),
                entrance.trigger().getZ() + 0.5D,
                "던전: " + entrance.displayName(),
                entrance.dungeonId()
            ))
            .toList();
    }

    record Entrance(
        String entranceId, String dungeonId, String displayName,
        ResourceLocation dimension, BlockPos trigger
    ) {}
}
