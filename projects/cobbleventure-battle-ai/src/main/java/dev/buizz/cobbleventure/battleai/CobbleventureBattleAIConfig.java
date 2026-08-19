package dev.buizz.cobbleventure.battleai;

import com.gitlab.srcmc.rctapi.api.ai.config.RCTBattleAIConfig;
import java.util.Locale;

/** JSON model used by RCT's {@code ai.type = "cobbleventure"} parser. */
public record CobbleventureBattleAIConfig(
        String difficulty,
        String strategy,
        Double cheatProbability,
        Mechanics mechanics
) {
    public record Mechanics(
            Boolean megaEvolution,
            Boolean zMove,
            Boolean dynamax,
            Boolean terastallization
    ) {
        public Mechanics() {
            this(false, false, false, false);
        }

        public boolean allowsMegaEvolution() {
            return Boolean.TRUE.equals(megaEvolution);
        }

        public boolean allowsZMove() {
            return Boolean.TRUE.equals(zMove);
        }

        public boolean allowsDynamax() {
            return Boolean.TRUE.equals(dynamax);
        }

        public boolean allowsTerastallization() {
            return Boolean.TRUE.equals(terastallization);
        }
    }

    public CobbleventureBattleAIConfig() {
        this("standard", "balanced", null, new Mechanics());
    }

    public CobbleventureBattleAIConfig {
        difficulty = normalized(difficulty, "standard");
        strategy = normalized(strategy, "balanced");
        mechanics = mechanics == null ? new Mechanics() : mechanics;
        if (cheatProbability != null) {
            cheatProbability = Math.max(0.0, Math.min(1.0, cheatProbability));
        }
    }

    public CobbleventureBattleAI createBattleAI() {
        return new CobbleventureBattleAI(this, rctConfig());
    }

    RCTBattleAIConfig rctConfig() {
        StrategyBias bias = StrategyBias.forId(strategy);
        return new RCTBattleAIConfig(
                bias.move(),
                bias.statusMove(),
                bias.switchAction(),
                bias.item(),
                selectMargin(difficulty)
        );
    }

    private static double selectMargin(String difficulty) {
        return switch (difficulty) {
            case "novice" -> 0.40;
            case "standard" -> 0.20;
            case "advanced" -> 0.10;
            case "expert" -> 0.05;
            case "expert_winrate", "expert_search", "cheater" -> 0.0;
            default -> 0.20;
        };
    }

    private static String normalized(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record StrategyBias(
            double move,
            double statusMove,
            double switchAction,
            double item
    ) {
        private static StrategyBias forId(String strategy) {
            return switch (strategy) {
                case "aggressive", "reckless_ace" -> new StrategyBias(1.25, 0.70, 0.35, 0.70);
                case "defensive" -> new StrategyBias(0.85, 1.10, 0.90, 1.00);
                case "ace_check" -> new StrategyBias(1.05, 0.95, 0.80, 0.85);
                case "setup" -> new StrategyBias(0.95, 1.20, 0.55, 0.80);
                case "hazard" -> new StrategyBias(0.90, 1.25, 0.65, 0.75);
                case "tempo", "unpredictable" -> new StrategyBias(1.00, 0.90, 1.05, 0.80);
                default -> new StrategyBias(1.00, 0.85, 0.50, 0.85);
            };
        }
    }
}
