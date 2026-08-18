package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.fishing.BobberSpawnPokemonEvent;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.interactive.PokerodItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Applies authored route pools without replacing Cobblemon's bobber and battle flow. */
final class AuthoredFishingEncounters {
    private static boolean registered;

    private AuthoredFishingEncounters() {}

    static void register() {
        if (registered) return;
        registered = true;
        CobblemonEvents.BOBBER_SPAWN_POKEMON_PRE.subscribe(
            (Consumer<BobberSpawnPokemonEvent.Pre>) AuthoredFishingEncounters::onPreSpawn
        );
        CobblemonEvents.BOBBER_SPAWN_POKEMON_MODIFY.subscribe(
            (Consumer<BobberSpawnPokemonEvent.Modify>) AuthoredFishingEncounters::onModifySpawn
        );
    }

    private static void onPreSpawn(BobberSpawnPokemonEvent.Pre event) {
        SpawnablePosition spawn = event.getSpawnAction().getSpawnablePosition();
        AdventureWorldContext.WildSpawnRule rule = ruleAt(
            spawn.getWorld(), spawn.getPosition(), event.getRod()
        );
        if (rule == null) return;
        if (!rule.enabled()
            || spawn.getWorld().getRandom().nextDouble() > rule.triggerChance()
            || !rule.inheritBiome() && rule.additions().isEmpty()) {
            event.cancel();
        }
    }

    private static void onModifySpawn(BobberSpawnPokemonEvent.Modify event) {
        SpawnablePosition spawn = event.getSpawnAction().getSpawnablePosition();
        AdventureWorldContext.WildSpawnRule rule = ruleAt(
            spawn.getWorld(), spawn.getPosition(), event.getRod()
        );
        if (rule == null || !rule.enabled()) return;

        PokemonEntity entity = event.getPokemon();
        Pokemon pokemon = entity.getPokemon();
        AdventureWorldContext.WildSpawnAddition addition =
            WildSpawnLeveling.selectAddition(entity, pokemon, rule);
        if (addition == null && WildSpawnLeveling.shouldCancel(pokemon, rule)) {
            if (rule.additions().isEmpty()) return;
            addition = WildSpawnLeveling.randomAddition(entity, rule.additions());
        }

        Integer averageLevel = CobbleventureAdventure.averageWildSpawnLevel(
            spawn.getWorld(), spawn.getPosition().getX(), spawn.getPosition().getZ()
        );
        if (addition != null) {
            int level = WildSpawnLeveling.levelFor(
                entity, addition.species(), rule, averageLevel, pokemon.getLevel()
            );
            if (!WildSpawnLeveling.replacePokemon(pokemon, addition, level)) return;
            if (!addition.spawnAsEvolved()) WildSpawnLeveling.normalizeLevelEvolution(pokemon);
        } else {
            int level = WildSpawnLeveling.levelFor(
                entity, pokemon.getSpecies().getResourceIdentifier(), rule,
                averageLevel, pokemon.getLevel()
            );
            pokemon.setLevel(level);
            WildSpawnLeveling.normalizeLevelEvolution(pokemon);
        }
        entity.addTag(WildSpawnLeveling.AUTHORED_METHOD_ENCOUNTER_TAG);
    }

    private static AdventureWorldContext.WildSpawnRule ruleAt(
        ServerLevel level, BlockPos position, ItemStack rod
    ) {
        return CobbleventureAdventure.authoredEncounterRule(
            level, position.getX() + 0.5D, position.getZ() + 0.5D,
            methodFor(rod)
        );
    }

    static AdventureWorldContext.WildEncounterMethod methodFor(ItemStack rod) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(rod.getItem());
        String path = rod.getItem() instanceof PokerodItem pokerod
            ? pokerod.getPokeRodId().getPath() : id.getPath();
        path = path.toLowerCase(Locale.ROOT);
        if (path.contains("ultra") || path.contains("master") || path.contains("super")) {
            return AdventureWorldContext.WildEncounterMethod.SUPER_ROD;
        }
        if (path.contains("great") || path.contains("good")) {
            return AdventureWorldContext.WildEncounterMethod.GOOD_ROD;
        }
        return AdventureWorldContext.WildEncounterMethod.OLD_ROD;
    }
}
