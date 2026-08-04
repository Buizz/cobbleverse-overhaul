package dev.buizz.cobbleventure.ai.engine;

import dev.buizz.cobbleventure.ai.api.ActionCandidate;
import dev.buizz.cobbleventure.ai.api.BattleObservation;

import java.util.Optional;

public interface ScoringRule {
    String id();

    Optional<ScoreAdjustment> evaluate(BattleObservation observation, ActionCandidate candidate);
}
