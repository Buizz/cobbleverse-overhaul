package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

/** Applies the world-map level brush to naturally spawned wild Pokemon. */
final class WildSpawnLeveling {
    private static final int LEVEL_SPREAD = 2;
    private static boolean registered;

    private WildSpawnLeveling() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(
            (Consumer<SpawnEvent<PokemonEntity>>) WildSpawnLeveling::onPokemonSpawn
        );
    }

    private static void onPokemonSpawn(SpawnEvent<PokemonEntity> event) {
        PokemonEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        Integer averageLevel = CobbleventureAdventure.averageWildSpawnLevel(
            level, entity.getX(), entity.getZ()
        );
        if (averageLevel == null) {
            return;
        }
        entity.getPokemon().setLevel(randomLevel(entity, averageLevel));
    }

    static int randomLevel(PokemonEntity entity, int averageLevel) {
        int minimum = Math.max(1, averageLevel - LEVEL_SPREAD);
        int maximum = Math.min(100, averageLevel + LEVEL_SPREAD);
        return minimum + entity.getRandom().nextInt(maximum - minimum + 1);
    }
}
