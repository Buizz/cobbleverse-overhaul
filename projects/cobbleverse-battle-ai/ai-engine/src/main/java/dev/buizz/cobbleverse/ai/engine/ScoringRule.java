package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.ActionCandidate;
import dev.buizz.cobbleverse.ai.api.BattleObservation;

import java.util.Optional;

public interface ScoringRule {
    String id();

    Optional<ScoreAdjustment> evaluate(BattleObservation observation, ActionCandidate candidate);
}
