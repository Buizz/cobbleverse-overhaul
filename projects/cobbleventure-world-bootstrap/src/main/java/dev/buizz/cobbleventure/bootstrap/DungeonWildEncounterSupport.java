package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Isolates optional Cobblemon entity linkage from pure dungeon model tests. */
final class DungeonWildEncounterSupport {
    private DungeonWildEncounterSupport() {}

    static Entity spawn(
        ServerLevel level,
        DungeonDefinition.WildPokemon pokemon,
        BlockPos position,
        float yaw
    ) {
        try {
            String properties = pokemon.species() + " level=" + pokemon.level()
                + (pokemon.catchable() ? "" : " uncatchable");
            var entity = PokemonProperties.Companion.parse(properties).createEntity(level);
            entity.moveTo(
                position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D,
                yaw, 0.0F
            );
            entity.setPersistenceRequired();
            entity.setCountsTowardsSpawnCap(false);
            entity.setNoAi(true);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.addTag("cobbleventure_dungeon_encounter");
            if (!level.addFreshEntity(entity)) {
                throw new IllegalStateException("Cobblemon rejected the dungeon Pokemon");
            }
            return entity;
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "Dungeon Pokemon placement failed: " + pokemon.species()
                    + " at " + position,
                error
            );
        }
    }

    static UUID findEncounterPokemon(
        Iterable<BattleActor> actors, Set<UUID> encounterEntities
    ) {
        for (BattleActor actor : actors) {
            if (actor instanceof PokemonBattleActor pokemonActor
                && pokemonActor.getEntity() != null
                && encounterEntities.contains(pokemonActor.getEntity().getUUID())) {
                return pokemonActor.getEntity().getUUID();
            }
        }
        return null;
    }
}
