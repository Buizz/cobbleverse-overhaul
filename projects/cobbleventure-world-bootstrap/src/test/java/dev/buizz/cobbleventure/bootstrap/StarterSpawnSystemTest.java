package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class StarterSpawnSystemTest {
    @Test
    void doesNotRevalidateACompletedStarterArrivalOnReconnect() {
        assertFalse(StarterSpawnSystem.needsStarterValidationOnLogin(true, true));
    }

    @Test
    void validatesOnlyAnIncompleteLegacyStarterArrival() {
        assertTrue(StarterSpawnSystem.needsStarterValidationOnLogin(true, false));
        assertFalse(StarterSpawnSystem.needsStarterValidationOnLogin(false, false));
    }

    @Test
    void acceptsLeavingTheBuildingInteriorAsAnExistingStarterArrival() {
        assertTrue(StarterSpawnSystem.isBuildingInteriorExit(
            ResourceLocation.parse("cobbleventure:building_interiors")
        ));
        assertFalse(StarterSpawnSystem.isBuildingInteriorExit(
            ResourceLocation.parse("minecraft:overworld")
        ));
    }
}
