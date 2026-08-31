package dev.buizz.cobbleventure.adventure.daycare;

import java.math.BigInteger;

/** Pure daycare economy and wall-clock rules kept independent from mod runtime classes. */
final class DaycarePolicy {
    static final float EGG_DISCOVERY_CHANCE = 0.35F;
    static final int TRAINING_EXPERIENCE_PER_INTERVAL = 100;
    static final long TRAINING_COST_PER_INTERVAL = 100L;
    static final long TRAINING_INTERVAL_SECONDS = 5L * 60L;
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
        long intervals = seconds / TRAINING_INTERVAL_SECONDS;
        long experience = intervals * TRAINING_EXPERIENCE_PER_INTERVAL;
        return (int) Math.min(MAX_TRAINING_EXPERIENCE, Math.max(0L, experience));
    }

    static long secondsUntilNextTrainingGain(
        DaycareJob.StoredPokemon stored, long nowMillis
    ) {
        if (!stored.training() || stored.trainingStartedAtMillis() < 0
            || nowMillis < stored.trainingStartedAtMillis()) {
            return TRAINING_INTERVAL_SECONDS;
        }
        long elapsedSeconds = (nowMillis - stored.trainingStartedAtMillis()) / 1_000L;
        long remainder = elapsedSeconds % TRAINING_INTERVAL_SECONDS;
        return remainder == 0L && elapsedSeconds > 0L
            ? TRAINING_INTERVAL_SECONDS
            : TRAINING_INTERVAL_SECONDS - remainder;
    }

    static BigInteger trainingCost(int appliedExperience) {
        int clamped = Math.max(0, appliedExperience);
        long intervals = (clamped + TRAINING_EXPERIENCE_PER_INTERVAL - 1L)
            / TRAINING_EXPERIENCE_PER_INTERVAL;
        return BigInteger.valueOf(TRAINING_COST_PER_INTERVAL)
            .multiply(BigInteger.valueOf(intervals));
    }

    static BigInteger balanceAfterTraining(BigInteger balance, int appliedExperience) {
        return balance.subtract(trainingCost(appliedExperience));
    }
}
