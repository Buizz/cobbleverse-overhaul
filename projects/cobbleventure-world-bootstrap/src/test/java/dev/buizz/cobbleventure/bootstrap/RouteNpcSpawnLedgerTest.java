package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class RouteNpcSpawnLedgerTest {
    @Test
    void recordsEachAuthoredRouteSlotIndependently() {
        var data = CobbleventureBootstrap.BootstrapSavedData.create();
        String first = RouteNpcSpawnLedger.key("route_custom_02", "trainer_a");
        String second = RouteNpcSpawnLedger.key("route_custom_02", "trainer_b");

        data.markRouteNpcSpawned(first);

        assertTrue(data.hasSpawnedRouteNpc(first));
        assertFalse(data.hasSpawnedRouteNpc(second));
    }

    @Test
    void initializesAnExistingWorldWithoutRespawningItsRouteNpcs() {
        var data = CobbleventureBootstrap.BootstrapSavedData.create();
        String slot = RouteNpcSpawnLedger.key(
            "route_custom_01", "population/0"
        );

        data.initializeRouteNpcLedger(Set.of(slot));

        assertTrue(data.isRouteNpcLedgerInitialized());
        assertTrue(data.hasSpawnedRouteNpc(slot));
    }
}
