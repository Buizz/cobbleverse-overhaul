package dev.buizz.cobbleventure.adventure.battleai;

import com.gitlab.srcmc.rctapi.api.util.JTO;

public final class CobbleventureBattleAIRegistration {
    private CobbleventureBattleAIRegistration() {}

    public static void register() {
        JTO.registerParser(
                "cobbleventure",
                CobbleventureBattleAIConfig::createBattleAI,
                CobbleventureBattleAIConfig::new,
                CobbleventureBattleAIConfig.class
        );
    }
}
