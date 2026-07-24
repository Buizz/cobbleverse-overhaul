package dev.buizz.cobbleverse.ai.engine;

public record ScoreAdjustment(double amount, String reason) {
    public ScoreAdjustment {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
