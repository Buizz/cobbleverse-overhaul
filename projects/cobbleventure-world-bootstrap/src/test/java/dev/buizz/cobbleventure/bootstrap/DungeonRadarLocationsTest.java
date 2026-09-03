package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DungeonRadarLocationsTest {
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation DUNGEONS =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "dungeons");

    @Test
    void exposesActualEntranceBlockAndAuthoredDungeonName() {
        var locations = DungeonRadarLocations.locations(OVERWORLD, List.of(
            entrance("power_plant_front", OVERWORLD, new BlockPos(120, 67, -240))
        ));

        assertEquals(1, locations.size());
        var location = locations.getFirst();
        assertEquals("dungeon/power_plant_front", location.id());
        assertEquals(RadarLocationCatalog.Kind.DUNGEON_ENTRANCE, location.kind());
        assertEquals(OVERWORLD, location.dimension());
        assertEquals(120.5D, location.x());
        assertEquals(67.0D, location.y());
        assertEquals(-239.5D, location.z());
        assertEquals("던전: 로켓단 발전소", location.label());
        assertEquals("rocket_power_plant", location.areaId());
    }

    @Test
    void excludesOtherDimensionsAndPreservesSeparateEntrancesInStableOrder() {
        var front = entrance("front", OVERWORLD, new BlockPos(100, 64, 200));
        var back = entrance("back", OVERWORLD, new BlockPos(150, 65, 250));
        var interior = entrance("inside", DUNGEONS, new BlockPos(32768, 80, 32768));
        var locations = DungeonRadarLocations.locations(OVERWORLD, List.of(front, interior, back));

        assertEquals(List.of("dungeon/back", "dungeon/front"), locations.stream()
            .map(RadarLocationCatalog.Location::id).toList());
        assertEquals(locations, DungeonRadarLocations.locations(OVERWORLD, List.of(back, front)));
        assertEquals(List.of("dungeon/inside"),
            DungeonRadarLocations.locations(DUNGEONS, List.of(front, interior, back)).stream()
                .map(RadarLocationCatalog.Location::id).toList());
    }

    @Test
    void returnsNothingWithoutRegisteredEntrances() {
        assertTrue(DungeonRadarLocations.locations(OVERWORLD, List.of()).isEmpty());
    }

    private static DungeonRadarLocations.Entrance entrance(
        String id, ResourceLocation dimension, BlockPos trigger
    ) {
        return new DungeonRadarLocations.Entrance(
            id, "rocket_power_plant", "로켓단 발전소", dimension, trigger
        );
    }
}
