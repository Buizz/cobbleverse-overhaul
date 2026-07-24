package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.ActionCandidate;
import dev.buizz.cobbleverse.ai.api.ActionType;
import dev.buizz.cobbleverse.ai.api.BattleObservation;
import dev.buizz.cobbleverse.ai.api.DecisionEngine;
import dev.buizz.cobbleverse.ai.api.DecisionResult;
import dev.buizz.cobbleverse.ai.api.RankedAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RuleBasedDecisionEngine implements DecisionEngine {
    private static final Comparator<RankedAction> RANKING = Comparator
            .comparingDouble(RankedAction::utility)
            .reversed()
            .thenComparing(result -> result.action().id());

    private final List<ScoringRule> rules;

    public RuleBasedDecisionEngine(List<ScoringRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        if (this.rules.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("rules must not contain null");
        }
    }

    @Override
    public DecisionResult decide(BattleObservation observation) {
        Objects.requireNonNull(observation, "observation");

        var ranking = observation.candidates().stream()
                .filter(ActionCandidate::legal)
                .filter(candidate -> !observation.forcedSwitch()
                        || candidate.action().type() == ActionType.SWITCH)
                .map(candidate -> score(observation, candidate))
                .sorted(RANKING)
                .toList();

        return ranking.isEmpty()
                ? DecisionResult.empty()
                : new DecisionResult(Optional.of(ranking.getFirst()), ranking);
    }

    private RankedAction score(BattleObservation observation, ActionCandidate candidate) {
        double utility = candidate.baseUtility();
        var reasons = new ArrayList<String>();
        reasons.add("base=" + candidate.baseUtility());

        for (var rule : rules) {
            var adjustment = rule.evaluate(observation, candidate);
            if (adjustment.isPresent()) {
                utility += adjustment.get().amount();
                reasons.add(rule.id() + ":" + signed(adjustment.get().amount())
                        + " (" + adjustment.get().reason() + ")");
            }
        }

        return new RankedAction(candidate.action(), utility, reasons);
    }

    private static String signed(double value) {
        return value >= 0 ? "+" + value : Double.toString(value);
    }
}
