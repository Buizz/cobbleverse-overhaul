package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.pokemon.requirement.Requirement;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.evolution.variants.LevelUpEvolution;
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import org.slf4j.Logger;

/** Applies the world-map level brush to naturally spawned wild Pokemon. */
final class WildSpawnLeveling {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LEVEL_SPREAD = 2;
    private static final int INHERITED_SPAWN_WEIGHT = 4;
    private static final int INITIAL_DIAGNOSTIC_EVENTS = 20;
    private static final String AUTHORED_PURSUIT_TAG = "cobbleventure_pursuit_encounter";
    static final String AUTHORED_METHOD_ENCOUNTER_TAG =
        "cobbleventure_authored_method_encounter";
    private static final String FORCE_EVOLVED_SPAWN_TAG = "cobbleventure_force_evolved_spawn";
    private static boolean registered;
    private static int spawnEvents;
    private static int canceledEvents;

    private WildSpawnLeveling() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(
            (Consumer<SpawnEvent<PokemonEntity>>) WildSpawnLeveling::onPokemonSpawn
        );
        LOGGER.info("[Spawn diagnosis] Natural Pokemon spawn listener registered");
    }

    private static void onPokemonSpawn(SpawnEvent<PokemonEntity> event) {
        PokemonEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (entity.getTags().contains(AUTHORED_METHOD_ENCOUNTER_TAG)) {
            return;
        }
        Pokemon pokemon = entity.getPokemon();
        if (!pokemon.isWild()) {
            return;
        }
        int eventNumber = ++spawnEvents;
        Set<ResourceLocation> habitatSpecies = entity.getTags().contains(AUTHORED_PURSUIT_TAG)
            ? null : CobbleventureAdventure.allowedWildSpecies(
                level, entity.getX(), entity.getY(), entity.getZ()
            );
        if (habitatSpecies != null && habitatSpecies.isEmpty()) {
            logCancellation(
                eventNumber, entity, pokemon, "empty-habitat-pool", 0
            );
            event.cancel();
            return;
        }
        AdventureWorldContext.WildEncounterMethod method = naturalEncounterMethod(
            level.getFluidState(entity.blockPosition()).is(FluidTags.WATER)
        );
        AdventureWorldContext.WildSpawnRule rule = CobbleventureAdventure.authoredEncounterRule(
            level, entity.getX(), entity.getZ(), method
        );
        AdventureWorldContext.WildSpawnAddition addition = selectAddition(
            entity, pokemon, rule
        );
        if (rule != null && addition == null && shouldCancel(pokemon, rule)) {
            logCancellation(
                eventNumber, entity, pokemon,
                rule.inheritBiome() ? "excluded-by-route" : "route-biome-inheritance-disabled",
                -1
            );
            event.cancel();
            return;
        }
        if (shouldLog(eventNumber)) {
            LOGGER.info(
                "[Spawn diagnosis] Natural spawn event #{}: species={}, dimension={}, position=({}, {}, {}), routeRule={}, habitatPool={}",
                eventNumber, pokemon.getSpecies().getResourceIdentifier(), level.dimension().location(),
                entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), rule != null,
                habitatSpecies == null ? "unrestricted" : habitatSpecies.size()
            );
        }
        if (addition == null && habitatSpecies != null && !habitatSpecies.contains(
            pokemon.getSpecies().getResourceIdentifier()
        )) {
            if (habitatSpecies.isEmpty() || !replacePokemon(
                pokemon,
                randomSpecies(entity, habitatSpecies),
                pokemon.getLevel()
            )) {
                logCancellation(
                    eventNumber, entity, pokemon,
                    habitatSpecies.isEmpty() ? "empty-habitat-pool" : "habitat-replacement-failed",
                    habitatSpecies.size()
                );
                event.cancel();
                return;
            }
            if (shouldLog(eventNumber)) {
                LOGGER.info(
                    "[Spawn diagnosis] Natural spawn replaced from habitat pool: event=#{}, species={}, poolSize={}",
                    eventNumber, pokemon.getSpecies().getResourceIdentifier(), habitatSpecies.size()
                );
            }
        }
        Integer averageLevel = CobbleventureAdventure.averageWildSpawnLevel(
            level, entity.getX(), entity.getZ()
        );
        if (addition != null) {
            int levelValue = levelFor(entity, addition.species(), rule, averageLevel, pokemon.getLevel());
            if (!replacePokemon(pokemon, addition, levelValue)) {
                logCancellation(eventNumber, entity, pokemon, "route-addition-replacement-failed", -1);
                event.cancel();
                return;
            }
        } else {
            pokemon.setLevel(levelFor(
                entity, pokemon.getSpecies().getResourceIdentifier(), rule,
                averageLevel, pokemon.getLevel()
            ));
        }
        boolean forceEvolvedSpawn = entity.getTags().contains(FORCE_EVOLVED_SPAWN_TAG)
            || addition != null && addition.spawnAsEvolved();
        if (!forceEvolvedSpawn) normalizeLevelEvolution(pokemon);
        if (shouldLog(eventNumber)) {
            LOGGER.info(
                "[Spawn diagnosis] Final wild level: species={}, level={}, method={}, speciesOverride={}, worldAverage={}, dimension={}, position=({}, {}, {})",
                pokemon.getSpecies().getResourceIdentifier(), pokemon.getLevel(), method,
                rule != null && rule.levelOverrides().containsKey(pokemon.getSpecies().getResourceIdentifier()),
                averageLevel, level.dimension().location(), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()
            );
        }
    }

    private static boolean shouldLog(int eventNumber) {
        return eventNumber <= INITIAL_DIAGNOSTIC_EVENTS || eventNumber % 100 == 0;
    }

    static AdventureWorldContext.WildEncounterMethod naturalEncounterMethod(
        boolean inWater
    ) {
        return inWater ? AdventureWorldContext.WildEncounterMethod.SURF
            : AdventureWorldContext.WildEncounterMethod.LAND;
    }

    private static void logCancellation(
        int eventNumber, PokemonEntity entity, Pokemon pokemon, String reason, int habitatPoolSize
    ) {
        int cancellationNumber = ++canceledEvents;
        if (cancellationNumber <= INITIAL_DIAGNOSTIC_EVENTS || cancellationNumber % 100 == 0) {
            LOGGER.warn(
                "[Spawn diagnosis] Natural spawn canceled #{} (event #{}): reason={}, species={}, dimension={}, position=({}, {}, {}), habitatPool={}",
                cancellationNumber, eventNumber, reason, pokemon.getSpecies().getResourceIdentifier(),
                entity.level().dimension().location(), entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                habitatPoolSize < 0 ? "n/a" : habitatPoolSize
            );
        }
    }

    static int levelFor(
        PokemonEntity entity, ResourceLocation species,
        AdventureWorldContext.WildSpawnRule rule, Integer averageLevel, int fallback
    ) {
        return levelFor(entity.getRandom()::nextInt, species, rule, averageLevel, fallback);
    }

    static int levelFor(
        java.util.function.IntUnaryOperator nextInt, ResourceLocation species,
        AdventureWorldContext.WildSpawnRule rule, Integer averageLevel, int fallback
    ) {
        AdventureWorldContext.WildSpawnLevelRange override = rule == null
            ? null : rule.levelOverrides().get(species);
        if (override != null) return override.sample(nextInt);
        return averageLevel == null ? fallback : new AdventureWorldContext.WildSpawnLevelRange(
            averageLevel - LEVEL_SPREAD, averageLevel + LEVEL_SPREAD
        ).sample(nextInt);
    }

    static AdventureWorldContext.WildSpawnAddition selectAddition(
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
        int additionWeight = rule.additions().stream()
            .mapToInt(AdventureWorldContext.WildSpawnAddition::weight).sum();
        int totalWeight = INHERITED_SPAWN_WEIGHT + additionWeight;
        int choice = entity.getRandom().nextInt(totalWeight);
        return choice < additionWeight
            ? weightedAddition(rule.additions(), choice) : null;
    }

    static AdventureWorldContext.WildSpawnAddition randomAddition(
        PokemonEntity entity, List<AdventureWorldContext.WildSpawnAddition> additions
    ) {
        int totalWeight = additions.stream()
            .mapToInt(AdventureWorldContext.WildSpawnAddition::weight).sum();
        return weightedAddition(additions, entity.getRandom().nextInt(totalWeight));
    }

    private static AdventureWorldContext.WildSpawnAddition weightedAddition(
        List<AdventureWorldContext.WildSpawnAddition> additions, int choice
    ) {
        int cursor = choice;
        for (AdventureWorldContext.WildSpawnAddition addition : additions) {
            cursor -= addition.weight();
            if (cursor < 0) return addition;
        }
        return additions.get(additions.size() - 1);
    }

    static boolean shouldCancel(
        Pokemon pokemon, AdventureWorldContext.WildSpawnRule rule
    ) {
        if (!rule.inheritBiome()) {
            return true;
        }
        return rule.excludedSpecies().contains(
            pokemon.getSpecies().getResourceIdentifier()
        );
    }

    static boolean replacePokemon(
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

    static boolean replacePokemon(
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

    /**
     * Walks backwards only across ordinary level evolutions whose required level has not
     * been reached. Item/stone and other non-level evolution methods are intentionally kept.
     */
    static void normalizeLevelEvolution(Pokemon pokemon) {
        Species original = pokemon.getSpecies();
        Species current = original;
        int level = pokemon.getLevel();
        while (true) {
            PreEvolution preEvolution = current.getPreEvolution();
            if (preEvolution == null) break;
            Species previous = preEvolution.getSpecies();
            Integer requiredLevel = levelRequirementFor(previous, current);
            if (requiredLevel == null || level >= requiredLevel) break;
            current = previous;
        }
        if (current != original) replacePokemon(pokemon, current.getResourceIdentifier(), level);
    }

    private static Integer levelRequirementFor(Species previous, Species current) {
        ResourceLocation currentId = current.getResourceIdentifier();
        for (Evolution evolution : previous.getEvolutions()) {
            if (!(evolution instanceof LevelUpEvolution)) continue;
            Species result = speciesFor(evolution.getResult().getSpecies());
            if (result == null || !result.getResourceIdentifier().equals(currentId)) continue;
            for (Requirement requirement : evolution.getRequirements()) {
                if (requirement instanceof LevelRequirement levelRequirement) {
                    return levelRequirement.getMinLevel();
                }
            }
        }
        return null;
    }

    private static Species speciesFor(String value) {
        if (value == null || value.isBlank()) return null;
        Species species = PokemonSpecies.getByName(value);
        if (species != null) return species;
        ResourceLocation id = ResourceLocation.tryParse(value);
        return id == null ? null : PokemonSpecies.getByIdentifier(id);
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
