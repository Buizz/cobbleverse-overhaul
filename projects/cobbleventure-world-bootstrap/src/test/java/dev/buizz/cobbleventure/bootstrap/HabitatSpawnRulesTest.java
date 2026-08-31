package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buizz.cobbleventure.adventure.AdventureWorldContext;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.ConnectionPath;
import dev.buizz.cobbleventure.bootstrap.WorldPlanModels.HexCoord;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class HabitatSpawnRulesTest {
    private static final ResourceLocation PIDGEY = id("pidgey");
    private static final ResourceLocation RATTATA = id("rattata");
    private static final ResourceLocation EKANS = id("ekans");

    @Test
    void routeWithDisabledBiomeInheritanceUsesOnlyAuthoredAdditions() {
        AdventureWorldContext.WildSpawnRule rule = rule(
            false, Set.of(), List.of(addition(EKANS))
        );

        assertEquals(
            Set.of(EKANS),
            HabitatSpawnRules.applyRouteRule(Set.of(PIDGEY, RATTATA), rule)
        );
    }

    @Test
    void inheritedRouteRemovesExclusionsAndAddsAuthoredSpecies() {
        AdventureWorldContext.WildSpawnRule rule = rule(
            true, Set.of(RATTATA), List.of(addition(EKANS))
        );

        assertEquals(
            Set.of(PIDGEY, EKANS),
            HabitatSpawnRules.applyRouteRule(Set.of(PIDGEY, RATTATA), rule)
        );
    }

    @Test
    void exclusiveRoutePublishesAuthoredWeightsForInGameHabitatLists() {
        AdventureWorldContext.WildSpawnRule rule = rule(
            false, Set.of(), List.of(
                new AdventureWorldContext.WildSpawnAddition(EKANS, false, 3),
                new AdventureWorldContext.WildSpawnAddition(RATTATA, false, 2)
            )
        );

        assertEquals(
            Map.of(EKANS, 3, RATTATA, 2),
            HabitatSpawnRules.exclusiveRouteWeights(rule)
        );
    }

    @Test
    void oceanWaterHeightIsNotAcceptedAsLogBridgeDeck() {
        int oceanDeckY = CobbleventureBootstrap.WATER_SURFACE_Y + 1;

        assertFalse(HabitatSpawnRules.isLogBridgeDeckHeight(oceanDeckY, 61.0D));
        assertFalse(HabitatSpawnRules.isLogBridgeDeckHeight(oceanDeckY, 64.9D));
        assertTrue(HabitatSpawnRules.isLogBridgeDeckHeight(oceanDeckY, 65.4D));
    }

    @Test
    void authoredEncounterRouteCoversTheWholeMappedHex() {
        HexCoord routeCell = new HexCoord(-5, 7);
        ConnectionPath route = route("route_custom_03", "path", routeCell);

        assertSame(
            route,
            RouteEncounterSelector.forCell(
                routeCell, List.of(route), Set.of()
            )
        );
    }

    @Test
    void settlementTileKeepsPriorityOverRouteEncounter() {
        HexCoord townCell = new HexCoord(-4, 4);
        ConnectionPath route = route("route_custom_03", "path", townCell);

        assertNull(RouteEncounterSelector.forCell(
            townCell, List.of(route), Set.of(townCell)
        ));
    }

    @Test
    void waterRouteKeepsMapPriorityOnAnOverlappingTile() {
        HexCoord sharedCell = new HexCoord(7, 6);
        ConnectionPath land = route("land", "path", sharedCell);
        ConnectionPath water = route("water", "water", sharedCell);

        assertSame(
            water,
            RouteEncounterSelector.forCell(
                sharedCell, List.of(land, water), Set.of()
            )
        );
    }

    private static AdventureWorldContext.WildSpawnRule rule(
        boolean inheritBiome, Set<ResourceLocation> excluded,
        List<AdventureWorldContext.WildSpawnAddition> additions
    ) {
        return new AdventureWorldContext.WildSpawnRule(
            inheritBiome, excluded, additions, Map.of(), true, 1.0D
        );
    }

    private static AdventureWorldContext.WildSpawnAddition addition(
        ResourceLocation species
    ) {
        return new AdventureWorldContext.WildSpawnAddition(species, false, 1);
    }

    private static ConnectionPath route(
        String id, String surfaceStyle, HexCoord... cells
    ) {
        return new ConnectionPath(
            id, id, "from", "to", "minecraft:plains", "none",
            12.0D, 0.0D, null, surfaceStyle, "none", List.of(cells),
            List.of(), null, null, null, null, List.of(), null
        );
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobblemon", path);
    }
}
