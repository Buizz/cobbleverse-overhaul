package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
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

    private static void lowerLevelAndScaleHealth(Pokemon pokemon, int levelCap) {
        int previousHealth = pokemon.getCurrentHealth();
        int previousMaximum = pokemon.getMaxHealth();
        pokemon.setLevel(levelCap);
        pokemon.setCurrentHealth(scaledHealth(
            previousHealth, previousMaximum, pokemon.getMaxHealth()
        ));
    }

    private static void copyPersistentBattleState(Pokemon original, Pokemon battleCopy) {
        int resultingHealth = scaledHealth(
            battleCopy.getCurrentHealth(), battleCopy.getMaxHealth(), original.getMaxHealth()
        );
        original.getMoveSet().copyFrom(battleCopy.getMoveSet());
        original.setStatus(battleCopy.getStatus());
        original.setHeldItem$common(battleCopy.getHeldItem$common().copy());
        original.setCurrentHealth(resultingHealth);
    }

    static int scaledHealth(int health, int sourceMaximum, int targetMaximum) {
        if (health <= 0 || targetMaximum <= 0) return 0;
        if (sourceMaximum <= 0) return Math.min(health, targetMaximum);
        long numerator = (long) health * targetMaximum;
        int scaled = (int) Math.ceil((double) numerator / sourceMaximum);
        return Math.max(1, Math.min(targetMaximum, scaled));
    }
}
