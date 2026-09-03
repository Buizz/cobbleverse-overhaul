package dev.buizz.cobbleventure.adventure.event;

import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.InsufficientPokemonError;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor;
import com.gitlab.srcmc.rctapi.api.battle.BattleContext;
import java.util.Arrays;

/** Allows an empty second active slot, not a fabricated or borrowed party member. */
public final class UnderfilledTrainerDoubles {
    private UnderfilledTrainerDoubles() {}

    public static boolean allows(int actorsPerSide, int slotsPerActor, int pokemonCount,
                                 boolean cvesPending, boolean isolatedTrainerOpponent) {
        return actorsPerSide == 1 && slotsPerActor == 2 && pokemonCount == 1
            && cvesPending && isolatedTrainerOpponent;
    }

    public static void relaxPlayerCount(ErroredBattleStart errors, BattleContext context) {
        var type = context.getBattleFormat().getCobblemonBattleFormat().getBattleType();
        var sides = new com.cobblemon.mod.common.battles.BattleSide[] {
            context.getBattleSide1(), context.getBattleSide2()
        };
        for (int side = 0; side < sides.length; side++) {
            boolean isolatedOpponent = Arrays.stream(sides[1 - side].getActors()).anyMatch(actor ->
                actor instanceof EntityBackedBattleActor<?> backed && backed.getEntity() != null
                    && backed.getEntity().getTags().contains("cobbleventure_battle_proxy"));
            for (var actor : sides[side].getActors()) {
                if (!(actor instanceof PlayerBattleActor player)) continue;
                if (!allows(type.getActorsPerSide(), type.getSlotsPerActor(),
                    actor.getPokemonList().size(),
                    EventBattleBridge.pendingContext(player.getUuid()).isPresent(), isolatedOpponent)) continue;
                // Keep every other validation error, including already-in-battle / actor-count errors.
                errors.getParticipantErrors().get(actor).removeIf(InsufficientPokemonError.class::isInstance);
            }
        }
    }
}
