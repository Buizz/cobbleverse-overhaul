package dev.buizz.cobbleventure.adventure.daycare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.math.BigInteger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

final class DaycareBreedingPolicyTest {
    @Test
    void eggDiscoveryIsPossibleButNotGuaranteed() {
        assertTrue(DaycarePolicy.discoversEgg(0.0F));
        assertTrue(DaycarePolicy.discoversEgg(0.3499F));
        assertFalse(DaycarePolicy.discoversEgg(0.35F));
        assertFalse(DaycarePolicy.discoversEgg(0.99F));
    }

    @Test
    void trainingAccruesFromWallClockOnlyWhenEnabled() {
        DaycareJob.StoredPokemon trained = new DaycareJob.StoredPokemon(
            UUID.randomUUID(), new CompoundTag(), true, 1_000L
        );
        DaycareJob.StoredPokemon untrained = new DaycareJob.StoredPokemon(
            UUID.randomUUID(), new CompoundTag(), false, 0L
        );

        assertEquals(10, DaycarePolicy.accruedTrainingExperience(trained, 11_000L));
        assertEquals(0, DaycarePolicy.accruedTrainingExperience(untrained, 11_000L));
    }

    @Test
    void trainingExperienceIsCappedPerPokemon() {
        DaycareJob.StoredPokemon trained = new DaycareJob.StoredPokemon(
            UUID.randomUUID(), new CompoundTag(), true, 1_000L
        );

        assertEquals(
            DaycarePolicy.MAX_TRAINING_EXPERIENCE,
            DaycarePolicy.accruedTrainingExperience(trained, 100_000_000L)
        );
    }

    @Test
    void trainingSettlementAllowsNegativeBalance() {
        assertEquals(
            BigInteger.valueOf(-150L),
            DaycarePolicy.balanceAfterTraining(BigInteger.valueOf(100L), 250)
        );
    }
}
