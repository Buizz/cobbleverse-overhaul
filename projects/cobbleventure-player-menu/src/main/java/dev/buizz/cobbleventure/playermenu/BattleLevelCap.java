package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.EvGainedEvent;
import com.cobblemon.mod.common.api.pokemon.stats.BattleEvSource;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Applies the player's progression cap to battle-only Pokemon copies. */
public final class BattleLevelCap {
    private static final int UNRESTRICTED_LEVEL_CAP = 100;

    private BattleLevelCap() {}

    public static void register() {
        CobblemonEvents.EV_GAINED_EVENT_POST.subscribe(
            Priority.LOWEST,
            BattleLevelCap::persistBattleCloneEvGain
        );
    }

    public static List<BattlePokemon> adjustPlayerTeam(
        UUID playerId, List<? extends BattlePokemon> team
    ) {
        ServerPlayer player = PlayerExtensionsKt.getPlayer(playerId);
        if (player == null) {
            return new ArrayList<>(team);
        }

        int levelCap = ProgressionNetwork.levelCap(player);
        if (levelCap >= UNRESTRICTED_LEVEL_CAP) {
            return new ArrayList<>(team);
        }

        List<BattlePokemon> adjusted = new ArrayList<>(team.size());
        for (BattlePokemon battlePokemon : team) {
            adjusted.add(adjustPokemon(playerId, battlePokemon, levelCap));
        }
        return adjusted;
    }

    private static BattlePokemon adjustPokemon(
        UUID playerId, BattlePokemon source, int levelCap
    ) {
        Pokemon original = source.getOriginalPokemon();
        ServerPlayer owner = original.getOwnerPlayer();
        if (owner == null || !owner.getUUID().equals(playerId)
            || source.getEffectedPokemon().getLevel() <= levelCap) {
            return source;
        }

        Pokemon currentBattleCopy = source.getEffectedPokemon();
        if (currentBattleCopy != original) {
            lowerLevelAndScaleHealth(currentBattleCopy, levelCap);
            source.getPostBattlePokemonOperations().add(ignored -> {
                copyPersistentBattleState(original, currentBattleCopy);
                return kotlin.Unit.INSTANCE;
            });
            return source;
        }

        BattlePokemon scaled = BattlePokemon.Companion.safeCopyOf(original);
        Pokemon battleCopy = scaled.getEffectedPokemon();
        lowerLevelAndScaleHealth(battleCopy, levelCap);

        scaled.getPostBattlePokemonOperations().addAll(source.getPostBattlePokemonOperations());
        scaled.getPostBattlePokemonOperations().add(ignored -> {
            copyPersistentBattleState(original, battleCopy);
            return kotlin.Unit.INSTANCE;
        });
        scaled.getPostBattleEntityOperations().addAll(source.getPostBattleEntityOperations());
        return scaled;
    }

    /**
     * Cobblemon awards battle EVs to the affected battle Pokemon. A level-capped
     * Pokemon fights as a clone, so mirror the actual awarded amount into the
     * stored Pokemon immediately instead of relying only on battle-end cleanup.
     */
    private static void persistBattleCloneEvGain(EvGainedEvent.Post event) {
        if (event.getAmount() <= 0 || !(event.getSource() instanceof BattleEvSource source)) {
            return;
        }

        Pokemon affected = event.getPokemon();
        for (var actor : source.getBattle().getActors()) {
            if (!(actor instanceof PlayerBattleActor)) {
                continue;
            }
            for (BattlePokemon battlePokemon : actor.getPokemonList()) {
                if (battlePokemon.getEffectedPokemon() != affected) {
                    continue;
                }
                Pokemon original = battlePokemon.getOriginalPokemon();
                if (original != affected) {
                    original.getEvs().add(event.getStat(), event.getAmount());
                }
                return;
            }
        }
    }

    private static void lowerLevelAndScaleHealth(Pokemon pokemon, int levelCap) {
        int previousHealth = pokemon.getCurrentHealth();
        int previousMaximum = pokemon.getMaxHealth();
        pokemon.setLevel(levelCap);
        pokemon.setCurrentHealth(scaledHealth(
            previousHealth, previousMaximum, pokemon.getMaxHealth()
        ));
    }

    private static void copyPersistentBattleState(Pokemon original, Pokemon battleCopy) {
        original.getMoveSet().copyFrom(battleCopy.getMoveSet());
        original.setStatus(battleCopy.getStatus());
        original.setHeldItem$common(battleCopy.getHeldItem$common().copy());
        copyEvsInto(original.getEvs(), battleCopy.getEvs());
        int resultingHealth = scaledHealth(
            battleCopy.getCurrentHealth(), battleCopy.getMaxHealth(), original.getMaxHealth()
        );
        original.setCurrentHealth(resultingHealth);
    }

    static void copyEvsInto(EVs target, EVs source) {
        for (var entry : source) target.set(entry.getKey(), entry.getValue());
    }

    static int scaledHealth(int health, int sourceMaximum, int targetMaximum) {
        if (health <= 0 || targetMaximum <= 0) return 0;
        if (sourceMaximum <= 0) return Math.min(health, targetMaximum);
        long numerator = (long) health * targetMaximum;
        int scaled = (int) Math.ceil((double) numerator / sourceMaximum);
        return Math.max(1, Math.min(targetMaximum, scaled));
    }
}
