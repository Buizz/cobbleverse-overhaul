package dev.buizz.cobbleverse.ai.api;

@FunctionalInterface
public interface DecisionEngine {
    DecisionResult decide(BattleObservation observation);
}
