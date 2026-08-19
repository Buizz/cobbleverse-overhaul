package dev.buizz.cobbleventure.adventure.event;

/** Pure once/cooldown decision shared by persistent server trigger ledgers. */
final class EventTriggerGate {
    private EventTriggerGate() {}

    static boolean canFire(
        boolean completed,
        Long lastFiredGameTime,
        EventTriggerContract.Options options,
        long gameTime
    ) {
        return canFire(
            completed, lastFiredGameTime, options.once(), options.cooldownSeconds(), gameTime
        );
    }

    static boolean canFire(
        boolean completed,
        Long lastFiredGameTime,
        EventTriggerContract.TargetOptions options,
        long gameTime
    ) {
        return canFire(
            completed, lastFiredGameTime, options.once(), options.cooldownSeconds(), gameTime
        );
    }

    static boolean canFire(
        boolean completed, Long lastFiredGameTime,
        boolean once, double cooldownSeconds, long gameTime
    ) {
        if (once && completed) return false;
        if (cooldownSeconds <= 0.0D || lastFiredGameTime == null) return true;
        long elapsed = gameTime - lastFiredGameTime;
        return elapsed >= cooldownTicks(cooldownSeconds) || elapsed < 0L;
    }

    static long cooldownTicks(double seconds) {
        double ticks = Math.ceil(seconds * 20.0D);
        if (!Double.isFinite(ticks) || ticks > Long.MAX_VALUE) {
            throw new EventRuntimeException("trigger cooldown이 너무 큽니다: " + seconds);
        }
        return (long) ticks;
    }

}
