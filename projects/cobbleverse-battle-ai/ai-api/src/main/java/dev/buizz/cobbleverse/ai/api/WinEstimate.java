package dev.buizz.cobbleverse.ai.api;

import java.util.List;

public record WinEstimate(
        double probability,
        double confidence,
        String modelVersion,
        List<WinFactor> topFactors
) {
    public WinEstimate {
        if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be between 0 and 1");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        topFactors = topFactors == null ? List.of() : List.copyOf(topFactors);
        if (topFactors.stream().anyMatch(factor -> factor == null)) {
            throw new IllegalArgumentException("topFactors must not contain null");
        }
    }
}
