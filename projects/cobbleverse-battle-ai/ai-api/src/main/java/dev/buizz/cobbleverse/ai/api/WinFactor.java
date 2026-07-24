package dev.buizz.cobbleverse.ai.api;

public record WinFactor(String id, double contribution) {
    public WinFactor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (!Double.isFinite(contribution)) {
            throw new IllegalArgumentException("contribution must be finite");
        }
    }
}
