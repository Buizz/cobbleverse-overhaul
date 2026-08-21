package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class StarterSpawnSystemTest {
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
