package dev.buizz.cobbleventure.ai.engine;

import dev.buizz.cobbleventure.ai.api.AiRuntimeProfile;
import dev.buizz.cobbleventure.ai.api.AiSelectionPolicy;
import dev.buizz.cobbleventure.ai.api.BattleObservation;
import dev.buizz.cobbleventure.ai.api.DecisionEngine;
import dev.buizz.cobbleventure.ai.api.DecisionResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 실제 서버 프로필이 선택한 난이도 정책을 해당 판단 엔진으로 전달한다. */
public final class ConfiguredDecisionEngine {
    private final Map<AiSelectionPolicy, DecisionEngine> engines;

    public ConfiguredDecisionEngine(Map<AiSelectionPolicy, DecisionEngine> engines) {
        Objects.requireNonNull(engines, "engines");
        this.engines = new EnumMap<>(AiSelectionPolicy.class);
        this.engines.putAll(engines);
        if (this.engines.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("engines must not contain null");
        }
    }

    public DecisionResult decide(
            BattleObservation observation,
            AiRuntimeProfile profile,
            double deterministicCheatRoll
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(profile, "profile");
        var policy = profile.selectionPolicy(deterministicCheatRoll);
        var engine = engines.get(policy);
        if (engine == null) {
            throw new IllegalStateException("no decision engine registered for policy " + policy);
        }
        return engine.decide(observation);
    }
}
