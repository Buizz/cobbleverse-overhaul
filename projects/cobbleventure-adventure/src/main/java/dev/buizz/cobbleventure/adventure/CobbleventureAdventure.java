package dev.buizz.cobbleventure.adventure;

import dev.buizz.cobbleventure.adventure.event.EventScriptRepository;
import dev.buizz.cobbleventure.adventure.event.EventDialogueNetwork;
import dev.buizz.cobbleventure.adventure.event.EventDialogueThemeRepository;
import dev.buizz.cobbleventure.adventure.event.EventNpcBindingRepository;
import dev.buizz.cobbleventure.adventure.event.EventNpcInteractionHandler;
import dev.buizz.cobbleventure.adventure.event.EventNpcProximityHandler;
import dev.buizz.cobbleventure.adventure.event.EventStarterRouletteBridge;
import dev.buizz.cobbleventure.adventure.event.EventMapSelectionBridge;
import dev.buizz.cobbleventure.adventure.event.EventItemGrantBridge;
import dev.buizz.cobbleventure.adventure.event.EventBattleBridge;
import dev.buizz.cobbleventure.adventure.event.EventBattlePresetRepository;
import dev.buizz.cobbleventure.adventure.event.EventMovementBridge;
import dev.buizz.cobbleventure.adventure.event.EventSessionAdminCommands;
import dev.buizz.cobbleventure.adventure.event.EventPresentationBridge;
import dev.buizz.cobbleventure.adventure.event.EventServerSignalBridge;
import dev.buizz.cobbleventure.adventure.event.EventAwaitInputLockService;
import dev.buizz.cobbleventure.adventure.event.EventHealingBridge;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;

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

    public CobbleventureAdventure(IEventBus modBus) {
        EventScriptRepository.register();
        EventDialogueThemeRepository.register();
        EventNpcBindingRepository.register();
        EventBattlePresetRepository.register();
        EventNpcInteractionHandler.register();
        EventNpcProximityHandler.register();
        EventServerSignalBridge.register();
        EventStarterRouletteBridge.register();
        EventMapSelectionBridge.register();
        EventItemGrantBridge.register();
        EventBattleBridge.register();
        EventMovementBridge.register();
        EventSessionAdminCommands.register();
        EventPresentationBridge.register();
        EventHealingBridge.register();
        EventAwaitInputLockService.register();
        EventDialogueNetwork.register(modBus);
        FieldMoveRidingAccess.register();
        WildSpawnLeveling.register();
        AuthoredFishingEncounters.register();
        HeadbuttEncounters.register();
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
        ServerLevel level, double x, double y, double z
    ) {
        return worldContext.allowedWildSpecies(level, x, y, z);
    }

    /** Public hook used by fishing, surfing, and field-interaction systems. */
    public static AdventureWorldContext.WildSpawnRule authoredEncounterRule(
        ServerLevel level, double x, double z,
        AdventureWorldContext.WildEncounterMethod method
    ) {
        return worldContext.wildSpawnRule(level, x, z, method);
    }

    static String authoredWeatherAt(ServerPlayer player) {
        return worldContext.authoredWeatherAt(player);
    }
}
