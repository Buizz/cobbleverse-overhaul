package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.TerminalOutcome;
import dev.buizz.cobbleverse.ai.api.WinEstimate;
import dev.buizz.cobbleverse.ai.api.WinFactor;
import dev.buizz.cobbleverse.ai.api.WinProbabilityInput;
import dev.buizz.cobbleverse.ai.api.WinProbabilityModel;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

public final class LinearWinProbabilityModel implements WinProbabilityModel {
    private static final Comparator<WinFactor> FACTOR_RANKING = Comparator
            .comparingDouble((WinFactor factor) -> Math.abs(factor.contribution()))
            .reversed()
            .thenComparing(WinFactor::id);

    private final String modelVersion;
    private final double intercept;
    private final Map<String, Double> weights;
    private final int factorLimit;
    private final double totalAbsoluteWeight;

    public LinearWinProbabilityModel(
            String modelVersion,
            double intercept,
            Map<String, Double> weights,
            int factorLimit
    ) {
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        if (!Double.isFinite(intercept)) {
            throw new IllegalArgumentException("intercept must be finite");
        }
        if (factorLimit < 0) {
            throw new IllegalArgumentException("factorLimit must not be negative");
        }

        this.weights = weights == null ? Map.of() : Map.copyOf(weights);
        if (this.weights.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || !Double.isFinite(entry.getValue()))) {
            throw new IllegalArgumentException("weights must have non-blank ids and finite values");
        }

        this.modelVersion = modelVersion;
        this.intercept = intercept;
        this.factorLimit = factorLimit;
        this.totalAbsoluteWeight = this.weights.values().stream()
                .mapToDouble(Math::abs)
                .sum();
    }

    @Override
    public WinEstimate estimate(WinProbabilityInput input) {
        Objects.requireNonNull(input, "input");

        if (input.terminalOutcome() == TerminalOutcome.WIN) {
            return terminalEstimate(1.0, 1.0);
        }
        if (input.terminalOutcome() == TerminalOutcome.LOSS) {
            return terminalEstimate(0.0, -1.0);
        }

        var factors = weights.entrySet().stream()
                .filter(entry -> input.features().containsKey(entry.getKey()))
                .map(entry -> new WinFactor(
                        entry.getKey(),
                        entry.getValue() * input.features().get(entry.getKey())
                ))
                .filter(factor -> factor.contribution() != 0.0)
                .sorted(FACTOR_RANKING)
                .toList();

        double score = intercept + factors.stream()
                .mapToDouble(WinFactor::contribution)
                .sum();

        double matchedAbsoluteWeight = weights.entrySet().stream()
                .filter(entry -> input.features().containsKey(entry.getKey()))
                .mapToDouble(entry -> Math.abs(entry.getValue()))
                .sum();
        double featureCoverage = totalAbsoluteWeight == 0.0
                ? 1.0
                : matchedAbsoluteWeight / totalAbsoluteWeight;
        double confidence = input.informationCompleteness() * featureCoverage;

        return new WinEstimate(
                sigmoid(score),
                confidence,
                modelVersion,
                factors.stream().limit(factorLimit).toList()
        );
    }

    private WinEstimate terminalEstimate(double probability, double contribution) {
        return new WinEstimate(
                probability,
                1.0,
                modelVersion,
                factorLimit == 0
                        ? java.util.List.of()
                        : java.util.List.of(new WinFactor("terminal_outcome", contribution))
        );
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-value));
        }
        double exponent = Math.exp(value);
        return exponent / (1.0 + exponent);
    }
}
