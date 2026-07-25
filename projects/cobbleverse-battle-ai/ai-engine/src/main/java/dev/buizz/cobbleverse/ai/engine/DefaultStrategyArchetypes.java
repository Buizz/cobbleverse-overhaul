package dev.buizz.cobbleverse.ai.engine;

import dev.buizz.cobbleverse.ai.api.StrategyArchetype;
import dev.buizz.cobbleverse.ai.api.StrategyAxis;

import java.util.List;
import java.util.Map;

public final class DefaultStrategyArchetypes {
    private static final List<StrategyArchetype> ALL = List.of(
            archetype("balanced", 0.5, 0.45, 0.55, 0.5, 0.5, 0.5, 0.6, 0.6, 0.5, 0.55),
            archetype("offensive_pressure", 0.9, 0.7, 0.35, 0.35, 0.2, 0.45, 0.35, 0.7, 0.45, 0.4),
            archetype("defensive_control", 0.25, 0.2, 0.9, 0.75, 0.9, 0.65, 0.8, 0.65, 0.25, 0.75),
            archetype("ace_denial", 0.55, 0.3, 0.75, 0.65, 0.6, 0.65, 0.9, 1.0, 0.4, 0.75),
            archetype("ace_rush", 1.0, 0.9, 0.25, 0.25, 0.1, 0.3, 0.15, 0.55, 0.7, 0.25),
            archetype("setup_sweep", 0.75, 0.6, 0.65, 0.45, 0.35, 0.55, 0.7, 0.55, 1.0, 0.5),
            archetype("hazard_control", 0.45, 0.3, 0.85, 0.7, 0.65, 1.0, 0.75, 0.8, 0.35, 0.8),
            archetype("tempo_pivot", 0.7, 0.5, 0.55, 1.0, 0.35, 0.5, 0.65, 0.7, 0.35, 1.0)
    );

    private DefaultStrategyArchetypes() {
    }

    public static List<StrategyArchetype> all() {
        return ALL;
    }

    private static StrategyArchetype archetype(
            String id,
            double aggression,
            double riskTolerance,
            double horizon,
            double switchTendency,
            double recoveryPriority,
            double hazardPriority,
            double acePreservation,
            double enemyAcePressure,
            double setupPriority,
            double informationValue
    ) {
        return new StrategyArchetype(id, Map.of(
                StrategyAxis.AGGRESSION, aggression,
                StrategyAxis.RISK_TOLERANCE, riskTolerance,
                StrategyAxis.HORIZON, horizon,
                StrategyAxis.SWITCH_TENDENCY, switchTendency,
                StrategyAxis.RECOVERY_PRIORITY, recoveryPriority,
                StrategyAxis.HAZARD_PRIORITY, hazardPriority,
                StrategyAxis.ACE_PRESERVATION, acePreservation,
                StrategyAxis.ENEMY_ACE_PRESSURE, enemyAcePressure,
                StrategyAxis.SETUP_PRIORITY, setupPriority,
                StrategyAxis.INFORMATION_VALUE, informationValue
        ));
    }
}
