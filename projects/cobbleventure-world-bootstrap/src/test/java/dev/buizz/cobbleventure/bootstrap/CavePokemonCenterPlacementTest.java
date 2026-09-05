package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class CavePokemonCenterPlacementTest {
    @Test
    void placesCenterBesideRoadAndFacesItsEntranceTowardRoad() {
        var site = CavePokemonCenterPlacement.resolve(
            new CobbleventureBootstrap.Point(100, 100),
            new CobbleventureBootstrap.Point(100, 40),
            List.of(
                new CobbleventureBootstrap.Point(100, 100),
                new CobbleventureBootstrap.Point(160, 100)
            ),
            true
        );

        assertEquals(new CobbleventureBootstrap.Point(128, 88), site.center());
        assertEquals(new CobbleventureBootstrap.Point(128, 100), site.roadPoint());
        assertEquals(Direction.SOUTH, site.roadFacing());
    }

    @Test
    void usesCorrectRouteEndWhenCaveIsConnectionTarget() {
        var site = CavePokemonCenterPlacement.resolve(
            new CobbleventureBootstrap.Point(200, 100),
            new CobbleventureBootstrap.Point(200, 160),
            List.of(
                new CobbleventureBootstrap.Point(100, 100),
                new CobbleventureBootstrap.Point(200, 100)
            ),
            false
        );

        assertEquals(new CobbleventureBootstrap.Point(172, 112), site.center());
        assertEquals(new CobbleventureBootstrap.Point(172, 100), site.roadPoint());
        assertEquals(Direction.NORTH, site.roadFacing());
    }

    @Test
    void fallsBackToConfiguredOffsetWhenNoRoadIsAvailable() {
        var site = CavePokemonCenterPlacement.resolve(
            new CobbleventureBootstrap.Point(100, 100),
            new CobbleventureBootstrap.Point(164, 100),
            List.of(),
            true
        );

        assertEquals(new CobbleventureBootstrap.Point(128, 100), site.center());
        assertEquals(Direction.WEST, site.roadFacing());
    }
}
