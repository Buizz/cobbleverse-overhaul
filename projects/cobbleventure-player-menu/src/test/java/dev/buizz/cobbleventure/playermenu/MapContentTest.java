package dev.buizz.cobbleventure.playermenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class MapContentTest {
    @Test
    void indigoPlateauIsATeleportableCenteredWorldObjectInsteadOfATown() {
        MapContent content = MapContent.forGeneration(1);

        assertNull(content.townAt(-6, -2));
        MapContent.MapObject plateau = content.objectAt(-6, -2);
        assertNotNull(plateau);
        assertEquals("indigo_plateau", plateau.id());
        assertEquals("석영고원", plateau.name());
        assertTrue(plateau.teleportable());
        assertTrue(plateau.showOnMinimap());
    }

    @Test
    void caveLoaderIgnoresUndergroundRoadEntrances() {
        MapContent content = MapContent.forGeneration(1);

        assertEquals(List.of(
            "cobbleventure:cave_entrance/mt_moon_west",
            "cobbleventure:cave_entrance/mt_moon_east",
            "cobbleventure:cave_entrance/rock_tunnel_cerulean",
            "cobbleventure:cave_entrance/rock_tunnel_lavender"
        ), content.caveEntrances().stream().map(MapContent.CaveEntrance::id).toList());
    }

    @Test
    void routeTilesUseTheSavedRoutePresetEncounterList() {
        MapContent content = MapContent.forGeneration(1);
        MapContent.BiomeInfo route = content.biome(content.tileAt(-3, 2));

        assertEquals("상록시티 - 상록숲 길", route.name());
        assertEquals(0, route.habitatVariant());
        assertEquals(List.of(
            "cobblemon:caterpie", "cobblemon:weedle",
            "cobblemon:pidgey", "cobblemon:rattata"
        ), route.pokemon().stream().map(MapContent.Pokemon::id).toList());
    }

    @Test
    void oceanBridgeTilesUseOnlyGenerationOneAquaticEncounters() {
        MapContent content = MapContent.forGeneration(1);
        List<MapContent.Hex> oceanBridgeCells = List.of(
            new MapContent.Hex(9, 3), new MapContent.Hex(8, 4),
            new MapContent.Hex(8, 5), new MapContent.Hex(7, 6),
            new MapContent.Hex(7, 7), new MapContent.Hex(6, 8),
            new MapContent.Hex(6, 9)
        );

        for (MapContent.Hex hex : oceanBridgeCells) {
            MapContent.BiomeTile tile = content.tileAt(hex.q(), hex.r());
            assertEquals("minecraft:ocean", tile.biome());
            Set<String> displayed = content.biome(tile).pokemon().stream()
                .map(MapContent.Pokemon::id).collect(Collectors.toSet());
            Set<String> runtime = content.spawnBiome(tile).pokemon().stream()
                .map(MapContent.Pokemon::id).collect(Collectors.toSet());
            assertTrue(displayed.contains("cobblemon:tentacool"));
            assertTrue(displayed.contains("cobblemon:magikarp"));
            assertFalse(displayed.contains("cobblemon:weepinbell"));
            assertFalse(runtime.contains("cobblemon:weepinbell"));
            assertTrue(runtime.contains("cobblemon:gyarados"));
        }
    }
}
