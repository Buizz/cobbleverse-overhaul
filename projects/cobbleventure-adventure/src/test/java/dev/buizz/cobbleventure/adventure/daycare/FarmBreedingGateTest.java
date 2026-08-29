package dev.buizz.cobbleventure.adventure.daycare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class FarmBreedingGateTest {
    private static final ResourceLocation FARM =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "farm_plots");
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.withDefaultNamespace("overworld");

    @Test
    void requiresFourBadgesBeforeLocationIsConsidered() {
        assertEquals(
            FarmBreedingGate.Denial.BADGES,
            FarmBreedingGate.denial(3, FARM)
        );
    }

    @Test
    void rejectsUnlockedPasturesOutsideThePersonalFarm() {
        assertEquals(
            FarmBreedingGate.Denial.FARM_ONLY,
            FarmBreedingGate.denial(4, OVERWORLD)
        );
    }

    @Test
    void allowsUnlockedPasturesInThePersonalFarm() {
        assertEquals(
            FarmBreedingGate.Denial.NONE,
            FarmBreedingGate.denial(4, FARM)
        );
    }
}
