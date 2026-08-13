package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Seeds Cobblemon battles from authored local weather, excluding Minecraft weather. */
final class BattleWeatherSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean registered;

    private BattleWeatherSystem() {}

    static void register() {
        if (registered) return;
        registered = true;
        CobblemonEvents.BATTLE_STARTED_POST.subscribe(
            (Consumer<BattleStartedEvent.Post>) BattleWeatherSystem::onBattleStarted
        );
    }

    private static void onBattleStarted(BattleStartedEvent.Post event) {
        PokemonBattle battle = event.getBattle();
        List<ServerPlayer> players = battle.getPlayers();
        if (players.isEmpty()) return;

        String localWeather = CobbleventureAdventure.authoredWeatherAt(players.get(0));
        String battleWeather = showdownWeather(localWeather);
        if (battleWeather == null) return;

        battle.writeShowdownAction(
            ">eval if (battle.field.setWeather('" + battleWeather
                + "')) battle.field.weatherState.duration = 0"
        );
        LOGGER.info(
            "Authored local weather applied to battle: battle={}, local={}, showdown={}",
            battle.getBattleId(), localWeather, battleWeather
        );
    }

    private static String showdownWeather(String localWeather) {
        if (localWeather == null) return null;
        return switch (localWeather) {
            case "rain", "thunder" -> "raindance";
            case "snow" -> "snow";
            default -> null;
        };
    }
}
