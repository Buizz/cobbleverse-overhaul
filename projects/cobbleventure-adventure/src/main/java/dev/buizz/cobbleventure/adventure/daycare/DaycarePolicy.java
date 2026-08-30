package dev.buizz.cobbleventure.adventure.daycare;

import java.math.BigInteger;

/** Pure daycare economy and wall-clock rules kept independent from mod runtime classes. */
final class DaycarePolicy {
    static final float EGG_DISCOVERY_CHANCE = 0.35F;
    static final long TRAINING_COST_PER_EXPERIENCE = 1L;
    static final long TRAINING_EXPERIENCE_PER_SECOND = 1L;
    static final int MAX_TRAINING_EXPERIENCE = 10_000;

    private DaycarePolicy() {}

    static boolean discoversEgg(float roll) {
        return roll >= 0.0F && roll < EGG_DISCOVERY_CHANCE;
    }

    static int accruedTrainingExperience(
        DaycareJob.StoredPokemon stored, long nowMillis
    ) {
        if (!stored.training() || stored.trainingStartedAtMillis() < 0
            || nowMillis <= stored.trainingStartedAtMillis()) {
            return 0;
        }
        long seconds = (nowMillis - stored.trainingStartedAtMillis()) / 1_000L;
        long experience = seconds * TRAINING_EXPERIENCE_PER_SECOND;
        return (int) Math.min(MAX_TRAINING_EXPERIENCE, Math.max(0L, experience));
    }

    static BigInteger trainingCost(int appliedExperience) {
        return BigInteger.valueOf(TRAINING_COST_PER_EXPERIENCE)
            .multiply(BigInteger.valueOf(Math.max(0, appliedExperience)));
    }

    static BigInteger balanceAfterTraining(BigInteger balance, int appliedExperience) {
        return balance.subtract(trainingCost(appliedExperience));
    }
}
