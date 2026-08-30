package dev.buizz.cobbleventure.adventure.daycare;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.List;
import ludichat.cobbreeding.BreedingUtilities;
import ludichat.cobbreeding.EggUtilities;
import net.minecraft.world.item.ItemStack;

/** The only class allowed to directly depend on Cobbreeding implementation APIs. */
final class CobbreedingAdapter {
    private CobbreedingAdapter() {}

    static boolean canBreed(Pokemon first, Pokemon second) {
        return !BreedingUtilities.getPossibleEggs(List.of(first, second)).isEmpty();
    }

    static boolean canBreed(List<Pokemon> pokemon) {
        return pokemon.size() >= 2 && !BreedingUtilities.getPossibleEggs(pokemon).isEmpty();
    }

    static ItemStack createEgg(Pokemon first, Pokemon second) {
        return createEgg(List.of(first, second));
    }

    static ItemStack createEgg(List<Pokemon> pokemon) {
        PokemonProperties properties = BreedingUtilities.chooseEgg(pokemon);
        if (properties == null) {
            throw new IllegalStateException("Cobbreeding이 알 속성을 만들지 못했습니다.");
        }
        ItemStack egg = EggUtilities.getEggFromPokemonProperties(properties, null);
        if (egg == null || egg.isEmpty()) {
            throw new IllegalStateException("Cobbreeding이 알 아이템을 만들지 못했습니다.");
        }
        return egg;
    }
}
