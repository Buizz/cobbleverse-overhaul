package dev.buizz.cobbleventure.ai.api;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * 정규화 콘텐츠와 RCT ai.data가 실제 서버 AI 런타임에 전달하는 공통 설정이다.
 * 치터 확률 판정은 서버가 만든 결정론적 0 이상 1 미만 난수를 입력받아 수행한다.
 */
public record AiRuntimeProfile(
        String controller,
        AiDifficulty difficulty,
        String strategy,
        OptionalDouble cheatProbability
) {
    private static final Pattern STRATEGY_ID = Pattern.compile("[a-z0-9_.-]+");

    public AiRuntimeProfile {
        controller = Objects.requireNonNull(controller, "controller");
        difficulty = Objects.requireNonNull(difficulty, "difficulty");
        strategy = Objects.requireNonNull(strategy, "strategy");
        cheatProbability = Objects.requireNonNull(cheatProbability, "cheatProbability");
        if (!"cobbleventure".equals(controller)) {
            throw new IllegalArgumentException("controller must be cobbleventure");
        }
        if (!STRATEGY_ID.matcher(strategy).matches()) {
            throw new IllegalArgumentException("invalid strategy: " + strategy);
        }
        if (difficulty == AiDifficulty.CHEATER && cheatProbability.isEmpty()) {
            throw new IllegalArgumentException("cheater difficulty requires cheatProbability");
        }
        if (difficulty != AiDifficulty.CHEATER && cheatProbability.isPresent()) {
            throw new IllegalArgumentException("cheatProbability is only valid for cheater difficulty");
        }
        if (cheatProbability.isPresent()) {
            double probability = cheatProbability.getAsDouble();
            if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("cheatProbability must be between 0 and 1");
            }
        }
    }

    public static AiRuntimeProfile standard(String strategy) {
        return new AiRuntimeProfile(
                "cobbleventure",
                AiDifficulty.STANDARD,
                strategy,
                OptionalDouble.empty()
        );
    }

    public AiSelectionPolicy selectionPolicy(double deterministicRoll) {
        if (!Double.isFinite(deterministicRoll) || deterministicRoll < 0.0 || deterministicRoll >= 1.0) {
            throw new IllegalArgumentException("deterministicRoll must be in [0, 1)");
        }
        if (difficulty != AiDifficulty.CHEATER) {
            return difficulty.defaultPolicy();
        }
        return deterministicRoll < cheatProbability.orElseThrow()
                ? AiSelectionPolicy.COMMITTED_ACTION_COUNTER
                : AiSelectionPolicy.TWO_TURN_SEARCH;
    }

    public boolean mayReadCommittedOpponentAction(double deterministicRoll) {
        return selectionPolicy(deterministicRoll) == AiSelectionPolicy.COMMITTED_ACTION_COUNTER;
    }
}
