package dev.buizz.cobbleventure.adventure;

import dev.buizz.cobbleventure.adventure.battleai.CobbleventureBattleAIRegistration;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;

@Mod(CobbleventureAdventure.MOD_ID)
public final class CobbleventureAdventure {
    public static final String MOD_ID = "cobbleventure_adventure";

    private static final AdventureWorldContext EMPTY_WORLD_CONTEXT =
        new AdventureWorldContext() {
            @Override
            public Integer averageWildSpawnLevel(
                ServerLevel level, double x, double z
            ) {
                return null;
            }

            @Override
            public WildSpawnRule wildSpawnRule(
                ServerLevel level, double x, double z
            ) {
                return null;
            }

            @Override
            public String authoredWeatherAt(ServerPlayer player) {
                return null;
            }
        };

    private static volatile AdventureWorldContext worldContext = EMPTY_WORLD_CONTEXT;

    public CobbleventureAdventure() {
        CobbleventureBattleAIRegistration.register();
        FieldMoveRidingAccess.register();
        WildSpawnLeveling.register();
        PokemonCenterDefeatReturn.register();
        BattleOnlyPokeBallUse.register();
        TrainerBattleState.register();
        TrainerBattleLevelScaling.register();
        TrainerMoneyRewards.register();
        BattleWeatherSystem.register();
        BattlePokemonPositioning.register();
        PlayerVersusPlayerProtection.register();
    }

    public static void registerWorldContext(AdventureWorldContext context) {
        worldContext = Objects.requireNonNull(context, "context");
    }

    static Integer averageWildSpawnLevel(ServerLevel level, double x, double z) {
        return worldContext.averageWildSpawnLevel(level, x, z);
    }

    public static Set<ResourceLocation> allowedWildSpecies(
        ServerLevel level, double x, double z
    ) {
        return worldContext.allowedWildSpecies(level, x, z);
    }

    static AdventureWorldContext.WildSpawnRule wildSpawnRule(
        ServerLevel level, double x, double z
    ) {
        return worldContext.wildSpawnRule(level, x, z);
    }

    static String authoredWeatherAt(ServerPlayer player) {
        return worldContext.authoredWeatherAt(player);
    }
}
