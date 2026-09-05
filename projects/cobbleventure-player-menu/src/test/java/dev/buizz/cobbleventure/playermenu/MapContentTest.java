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
    void vermilionSeaDisplaysSurfInsteadOfBiomeAndKeepsRodPoolsSeparate() {
        MapContent content = MapContent.forGeneration(1);
        for (MapContent.Hex cell : List.of(new MapContent.Hex(1, 8), new MapContent.Hex(1, 7),
            new MapContent.Hex(2, 6), new MapContent.Hex(2, 8))) {
            MapContent.BiomeTile tile = content.tileAt(cell.q(), cell.r());
            assertEquals("surf", content.defaultEncounterMethod(tile));
            assertEquals(List.of("cobblemon:tentacool"), ids(content.biome(tile)));
            assertEquals(List.of("cobblemon:magikarp"), ids(content.encounterBiome(tile, "old_rod")));
            assertEquals(Set.of("cobblemon:horsea", "cobblemon:krabby", "cobblemon:magikarp"),
                Set.copyOf(ids(content.encounterBiome(tile, "good_rod"))));
            assertEquals(Set.of("cobblemon:horsea", "cobblemon:shellder", "cobblemon:gyarados", "cobblemon:psyduck"),
                Set.copyOf(ids(content.encounterBiome(tile, "super_rod"))));
            assertEquals(List.of(), ids(content.encounterBiome(tile, "land")));
            assertTrue(ids(content.spawnBiome(tile)).contains("cobblemon:dragonite"));
        }
    }

    @Test
    void ceruleanExplicitRiverCellsDisplayPsyduckWithoutChangingRouteGeometry() {
        MapContent content = MapContent.forGeneration(1);
        MapContent.Route route = content.routes().stream().filter(value -> value.id().equals("route_custom_19")).findFirst().orElseThrow();
        for (MapContent.Hex cell : List.of(new MapContent.Hex(7, -4), new MapContent.Hex(8, -5), new MapContent.Hex(9, -4))) {
            MapContent.BiomeTile tile = content.tileAt(cell.q(), cell.r());
            assertEquals(List.of("cobblemon:psyduck"), ids(content.biome(tile)));
            assertEquals(List.of("land", "surf", "old_rod", "good_rod", "super_rod"), content.encounterMethods(tile));
            assertFalse(route.path().contains(cell));
            assertTrue(ids(content.spawnBiome(tile)).contains("cobblemon:dragonite"));
        }
    }

    private static List<String> ids(MapContent.BiomeInfo biome) {
        return biome.pokemon().stream().map(MapContent.Pokemon::id).toList();
    }

    @Test
    void indigoPlateauIsATeleportableCenteredWorldObjectInsteadOfATown() {
        MapContent content = MapContent.forGeneration(1);

        assertNull(content.townAt(-6, -2));
        MapContent.MapObject plateau = content.objectAt(-6, -2);
        assertNotNull(plateau);
        assertEquals("indigo_plateau", plateau.id());
        assertEquals("석영고원", plateau.name());
        assertEquals("Indigo Plateau", plateau.name("en_us"));
        assertTrue(plateau.teleportable());
        assertTrue(plateau.showOnMinimap());
    }

    @Test
    void worldObjectsResolveConfiguredNamesForTheActiveLanguage() {
        MapContent content = MapContent.forGeneration(1);

        MapContent.MapObject powerPlant = content.objectAt(12, -2);
        assertNotNull(powerPlant);
        assertEquals("발전소", powerPlant.name("ko_kr"));
        assertEquals("Power Plant", powerPlant.name("en_us"));
        assertTrue(powerPlant.suppressNaturalSpawns());
        MapContent.BiomeTile powerPlantTile = content.tileAt(12, -2);
        assertEquals(List.of(), ids(content.spawnBiome(powerPlantTile)));
        assertEquals(List.of(), ids(content.encounterBiome(powerPlantTile, "land")));

        MapContent.MapObject daycare = content.objectAt(6, 0);
        assertNotNull(daycare);
        assertEquals("키우미집", daycare.name("ko_kr"));
        assertEquals("Pokémon Day Care", daycare.name("en_us"));
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
