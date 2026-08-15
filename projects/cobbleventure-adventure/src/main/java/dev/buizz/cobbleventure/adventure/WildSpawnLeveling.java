package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Applies the world-map level brush to naturally spawned wild Pokemon. */
final class WildSpawnLeveling {
    private static final int LEVEL_SPREAD = 2;
    private static final int INHERITED_SPAWN_WEIGHT = 4;
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
        Pokemon pokemon = entity.getPokemon();
        if (!pokemon.isWild()) {
            return;
        }
        AdventureWorldContext.WildSpawnRule rule = CobbleventureAdventure.wildSpawnRule(
            level, entity.getX(), entity.getZ()
        );
        AdventureWorldContext.WildSpawnAddition addition = selectAddition(
            entity, pokemon, rule
        );
        if (rule != null && addition == null && shouldCancel(pokemon, rule)) {
            event.cancel();
            return;
        }
        Set<ResourceLocation> habitatSpecies = CobbleventureAdventure.allowedWildSpecies(
            level, entity.getX(), entity.getZ()
        );
        if (addition == null && habitatSpecies != null && !habitatSpecies.contains(
            pokemon.getSpecies().getResourceIdentifier()
        )) {
            if (habitatSpecies.isEmpty() || !replacePokemon(
                pokemon,
                randomSpecies(entity, habitatSpecies),
                pokemon.getLevel()
            )) {
                event.cancel();
                return;
            }
        }
        Integer averageLevel = CobbleventureAdventure.averageWildSpawnLevel(
            level, entity.getX(), entity.getZ()
        );
        if (addition != null) {
            int levelValue = levelFor(entity, addition.species(), rule, averageLevel, pokemon.getLevel());
            if (!replacePokemon(pokemon, addition, levelValue)) {
                event.cancel();
            }
        } else {
            pokemon.setLevel(levelFor(
                entity, pokemon.getSpecies().getResourceIdentifier(), rule,
                averageLevel, pokemon.getLevel()
            ));
        }
    }

    private static int levelFor(
        PokemonEntity entity, ResourceLocation species,
        AdventureWorldContext.WildSpawnRule rule, Integer averageLevel, int fallback
    ) {
        AdventureWorldContext.WildSpawnLevelRange override = rule == null
            ? null : rule.levelOverrides().get(species);
        if (override != null) return randomLevel(entity, override.minLevel(), override.maxLevel());
        return averageLevel == null ? fallback : randomLevel(entity, averageLevel);
    }

    private static AdventureWorldContext.WildSpawnAddition selectAddition(
        PokemonEntity entity, Pokemon pokemon,
        AdventureWorldContext.WildSpawnRule rule
    ) {
        if (rule == null || rule.additions().isEmpty()) {
            return null;
        }
        boolean excluded = rule.excludedSpecies().contains(
            pokemon.getSpecies().getResourceIdentifier()
        );
        if (!rule.inheritBiome() || excluded) {
            return randomAddition(entity, rule.additions());
        }
        int totalWeight = INHERITED_SPAWN_WEIGHT + rule.additions().size();
        int choice = entity.getRandom().nextInt(totalWeight);
        return choice < rule.additions().size() ? rule.additions().get(choice) : null;
    }

    private static AdventureWorldContext.WildSpawnAddition randomAddition(
        PokemonEntity entity, List<AdventureWorldContext.WildSpawnAddition> additions
    ) {
        return additions.get(entity.getRandom().nextInt(additions.size()));
    }

    private static boolean shouldCancel(
        Pokemon pokemon, AdventureWorldContext.WildSpawnRule rule
    ) {
        if (!rule.inheritBiome()) {
            return true;
        }
        return rule.excludedSpecies().contains(
            pokemon.getSpecies().getResourceIdentifier()
        );
    }

    private static boolean replacePokemon(
        Pokemon pokemon, AdventureWorldContext.WildSpawnAddition addition,
        int level
    ) {
        return replacePokemon(pokemon, addition.species(), level);
    }

    private static ResourceLocation randomSpecies(
        PokemonEntity entity, Set<ResourceLocation> species
    ) {
        List<ResourceLocation> choices = new ArrayList<>(species);
        return choices.get(entity.getRandom().nextInt(choices.size()));
    }

    private static boolean replacePokemon(
        Pokemon pokemon, ResourceLocation speciesId, int level
    ) {
        Species species = PokemonSpecies.getByIdentifier(speciesId);
        if (species == null || !species.getImplemented()) {
            return false;
        }
        pokemon.setSpecies(species);
        pokemon.setForm(species.getStandardForm());
        pokemon.setLevel(level);
        pokemon.getMoveSet().clear();
        pokemon.initializeMoveset(false);
        pokemon.checkGender();
        return true;
    }

    static int randomLevel(PokemonEntity entity, int minimum, int maximum) {
        int safeMinimum = Math.max(1, Math.min(100, minimum));
        int safeMaximum = Math.max(safeMinimum, Math.min(100, maximum));
        return safeMinimum + entity.getRandom().nextInt(safeMaximum - safeMinimum + 1);
    }

    static int randomLevel(PokemonEntity entity, int averageLevel) {
        int minimum = Math.max(1, averageLevel - LEVEL_SPREAD);
        int maximum = Math.min(100, averageLevel + LEVEL_SPREAD);
        return randomLevel(entity, minimum, maximum);
    }
}
