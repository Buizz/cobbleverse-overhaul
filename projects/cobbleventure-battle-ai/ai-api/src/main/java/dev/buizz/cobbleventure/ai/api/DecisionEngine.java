package dev.buizz.cobbleventure.ai.api;

@FunctionalInterface
public interface DecisionEngine {
    DecisionResult decide(BattleObservation observation);
}
