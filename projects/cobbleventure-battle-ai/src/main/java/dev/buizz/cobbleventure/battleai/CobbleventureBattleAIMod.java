package dev.buizz.cobbleventure.battleai;

import net.neoforged.fml.common.Mod;

@Mod(CobbleventureBattleAIMod.MOD_ID)
public final class CobbleventureBattleAIMod {
    public static final String MOD_ID = "cobbleventure_battle_ai";

    public CobbleventureBattleAIMod() {
        CobbleventureBattleAIRegistration.register();
    }
}
