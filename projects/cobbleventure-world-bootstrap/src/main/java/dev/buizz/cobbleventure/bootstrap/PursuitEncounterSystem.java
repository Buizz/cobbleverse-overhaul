package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.playermenu.MapContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/** Sparse ambient wild encounters shared by authored caves and forests. */
final class PursuitEncounterSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String ENTITY_TAG = "cobbleventure_pursuit_encounter";
    static final String FORCE_EVOLVED_SPAWN_TAG = "cobbleventure_force_evolved_spawn";
    private static final int MAX_AMBIENT_POKEMON = 2;
    private static final int SPAWN_INTERVAL_TICKS = 20 * 4;
    private static final int ALERT_ANIMATION_TICKS = 18;
    private static final int WARNING_TICKS = 15;
    private static final int PURSUIT_TICKS = 20 * 12;
    private static final int PURSUIT_ACCELERATION_TICKS = 20 * 2;
    private static final int AGGRO_COOLDOWN_TICKS = 40;
    private static final double INITIAL_PURSUIT_SPEED = 0.60D;
    private static final double MAXIMUM_PURSUIT_SPEED = 0.90D;
    private static final double DETECTION_DISTANCE_SQUARED = 12.0D * 12.0D;
    private static final double DESPAWN_DISTANCE_SQUARED = 48.0D * 48.0D;
    private static final Map<UUID, State> STATES = new HashMap<>();

    private PursuitEncounterSystem() {}

    static Config parse(
        String id, JsonObject document, JsonObject biomeProfiles, JsonObject pokemonHabitats
    ) {
        JsonObject settings = document.getAsJsonObject("random_encounters");
        if (settings == null || !settings.get("enabled").getAsBoolean()) return null;
        String biome = settings.get("pokemon_biome").getAsString();
        Set<Integer> generations = configuredGenerations(settings, document);
        Set<String> habitats = new HashSet<>();
        Set<String> rarities = new HashSet<>();
        boolean includeSecondary = false;
        for (JsonElement element : biomeProfiles.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            boolean matches = false;
            for (JsonElement candidate : profile.getAsJsonArray("minecraft_biomes")) {
                if (candidate.getAsString().equals(biome)) matches = true;
            }
            if (!matches) continue;
            habitats.add(profile.get("habitat").getAsString());
            if (profile.has("settings") && profile.getAsJsonObject("settings").has("rarities")) {
                for (JsonElement rarity : profile.getAsJsonObject("settings").getAsJsonArray("rarities")) {
                    rarities.add(rarity.getAsString());
                }
            }
            includeSecondary |= !profile.has("settings")
                || !profile.getAsJsonObject("settings").has("include_secondary")
                || profile.getAsJsonObject("settings").get("include_secondary").getAsBoolean();
        }
        Set<String> excluded = new HashSet<>();
        for (JsonElement element : settings.getAsJsonArray("excluded_species")) {
            excluded.add(element.getAsString());
        }
        Map<String, SpeciesChoice> choices = new HashMap<>();
        int defaultMinimumLevel = settings.get("minimum_level").getAsInt();
        int defaultMaximumLevel = settings.get("maximum_level").getAsInt();
        if (settings.get("inherit_biome").getAsBoolean()) {
            for (JsonElement element : pokemonHabitats.getAsJsonArray("pokemon")) {
                JsonObject pokemon = element.getAsJsonObject();
                String species = pokemon.get("id").getAsString();
                if (excluded.contains(species)
                    || !pokemon.has("implemented") || !pokemon.get("implemented").getAsBoolean()
                    || !generations.contains(pokemon.get("generation").getAsInt())
                    || pokemon.has("is_legendary") && pokemon.get("is_legendary").getAsBoolean()
                    || pokemon.has("is_mythical") && pokemon.get("is_mythical").getAsBoolean()) continue;
                JsonObject pokemonHabitatsValue = pokemon.getAsJsonObject("habitats");
                String primary = pokemonHabitatsValue.get("primary").getAsString();
                JsonElement secondaryValue = pokemonHabitatsValue.get("secondary");
                String secondary = secondaryValue != null && !secondaryValue.isJsonNull()
                    ? secondaryValue.getAsString() : "";
                if (!habitats.contains(primary) && !(includeSecondary && habitats.contains(secondary))) {
                    continue;
                }
                String rarity = pokemon.has("preferences")
                    && pokemon.getAsJsonObject("preferences").has("rarity")
                    ? pokemon.getAsJsonObject("preferences").get("rarity").getAsString() : "common";
                if (!rarities.isEmpty() && !rarities.contains(rarity)) continue;
                choices.put(species, new SpeciesChoice(
                    species, defaultMinimumLevel, defaultMaximumLevel, rarityWeight(rarity), false
                ));
            }
        }
        for (JsonElement element : settings.getAsJsonArray("additions")) {
            JsonObject addition = element.getAsJsonObject();
            String species = addition.get("species").getAsString();
            choices.put(species, new SpeciesChoice(
                species, defaultMinimumLevel, defaultMaximumLevel, 20,
                addition.has("spawn_as_evolved")
                    && addition.get("spawn_as_evolved").getAsBoolean()
            ));
        }
        if (settings.has("level_overrides")) {
            for (JsonElement element : settings.getAsJsonArray("level_overrides")) {
                JsonObject override = element.getAsJsonObject();
                String species = override.get("species").getAsString();
                SpeciesChoice current = choices.get(species);
                if (current != null) choices.put(species, new SpeciesChoice(
                    species, override.get("min_level").getAsInt(),
                    override.get("max_level").getAsInt(), current.weight(),
                    current.spawnAsEvolved()
                ));
            }
        }
        Config config = new Config(
            id, settings.get("minimum_distance").getAsInt(),
            settings.get("maximum_distance").getAsInt(), List.copyOf(choices.values())
        );
        LOGGER.info(
            "[Spawn diagnosis] Ambient encounter loaded: area={}, biome={}, generations={}, habitats={}, species={}, spawnRadius={}-{}",
            id, biome, generations, habitats, config.species().size(),
            config.minimumDistance(), config.maximumDistance()
        );
        return config;
    }

    private static Set<Integer> configuredGenerations(JsonObject settings, JsonObject document) {
        Set<Integer> result = new HashSet<>();
        if (settings.has("generations")) {
            for (JsonElement element : settings.getAsJsonArray("generations")) {
                int generation = element.getAsInt();
                if (generation >= 1 && generation <= 9) result.add(generation);
            }
        }
        if (!result.isEmpty()) return Set.copyOf(result);
        int documentGeneration = 1;
        if (document.has("dimension")) {
            String region = document.getAsJsonObject("dimension").get("region_id").getAsString();
            int marker = region.indexOf("generation_");
            if (marker >= 0 && marker + 11 < region.length()) {
                char value = region.charAt(marker + 11);
                if (value >= '1' && value <= '9') documentGeneration = value - '0';
            }
        }
        MapContent world = MapContent.forGeneration(documentGeneration);
        if (world != null) result.addAll(world.pokemonGenerations());
        if (result.isEmpty()) result.add(documentGeneration);
        return Set.copyOf(result);
    }

    private static int rarityWeight(String rarity) {
        return switch (rarity) {
            case "medium" -> 30;
            case "uncommon" -> 16;
            case "rare" -> 7;
            case "ultra_rare" -> 3;
            case "legendary", "mythical" -> 1;
            default -> 60;
        };
    }

    static void tick(ServerPlayer player, Config config, long gameTime) {
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        String inactiveReason = inactiveReason(player, config);
        if (inactiveReason != null) {
            logStatusChange(player, state, inactiveReason);
            reset(player.serverLevel(), state, config == null ? null : config.id());
            return;
        }
        if (!config.id().equals(state.areaId)) {
            reset(player.serverLevel(), state, config.id());
            logStatusChange(player, state, "active:" + config.id());
            LOGGER.info(
                "[Spawn diagnosis] Ambient encounter activated: player={}, area={}, species={}, gameMode={}, position=({}, {}, {})",
                player.getGameProfile().getName(), config.id(), config.species().size(),
                player.gameMode.getGameModeForPlayer(), player.getBlockX(), player.getBlockY(), player.getBlockZ()
            );
        }
        removeUnavailableEncounters(player.serverLevel(), player, state);
        tickAlert(player.serverLevel(), state, gameTime);
        if (state.pursuer != null) {
            tickPursuer(player, state, gameTime);
            return;
        }
        acquireVisiblePursuer(player, state, gameTime);
        if (state.pursuer == null && state.encounters.size() < MAX_AMBIENT_POKEMON
            && gameTime >= state.nextSpawnTick) {
            spawn(player, config, state);
            state.nextSpawnTick = gameTime + SPAWN_INTERVAL_TICKS;
        }
    }

    private static String inactiveReason(ServerPlayer player, Config config) {
        if (config == null) return "outside-pursuit-area";
        if (config.species().isEmpty()) return "empty-species-pool:" + config.id();
        if (player.isSpectator()) return "ineligible:spectator";
        if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) {
            return "ineligible:creative";
        }
        if (!player.isAlive()) return "ineligible:not-alive";
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return "ineligible:already-battling";
        }
        return null;
    }

    private static void logStatusChange(ServerPlayer player, State state, String status) {
        if (status.equals(state.diagnosticStatus)) return;
        state.diagnosticStatus = status;
        LOGGER.info(
            "[Spawn diagnosis] Pursuit status: player={}, status={}, dimension={}, position=({}, {}, {})",
            player.getGameProfile().getName(), status,
            player.serverLevel().dimension().location(),
            player.getBlockX(), player.getBlockY(), player.getBlockZ()
        );
    }

    private static void spawn(ServerPlayer player, Config config, State state) {
        SpeciesChoice choice = choose(player.getRandom(), config.species());
        BlockPos position = findSpawnPosition(player, config);
        if (choice == null || position == null) {
            LOGGER.warn(
                "[Spawn diagnosis] Pursuit spawn position unavailable: player={}, area={}, choice={}, position=({}, {}, {})",
                player.getGameProfile().getName(), config.id(),
                choice == null ? "none" : choice.species(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ()
            );
            return;
        }
        int level = choice.minLevel() + player.getRandom().nextInt(
            Math.max(1, choice.maxLevel() - choice.minLevel() + 1)
        );
        PokemonEntity entity = PokemonProperties.Companion
            .parse(choice.species() + " level=" + level).createEntity(player.serverLevel());
        entity.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, player.getYRot(), 0.0F);
        entity.setCountsTowardsSpawnCap(false);
        entity.addTag(ENTITY_TAG);
        if (choice.spawnAsEvolved()) entity.addTag(FORCE_EVOLVED_SPAWN_TAG);
        if (!player.serverLevel().addFreshEntity(entity)) {
            LOGGER.warn(
                "[Spawn diagnosis] Pursuit entity insertion rejected: player={}, area={}, species={}, level={}, position={}",
                player.getGameProfile().getName(), config.id(), choice.species(), level, position
            );
            return;
        }
        state.encounters.add(entity.getUUID());
        LOGGER.info(
            "[Spawn diagnosis] Ambient Pokemon spawned: player={}, area={}, species={}, level={}, position={}, active={}",
            player.getGameProfile().getName(), config.id(), choice.species(), level,
            position, state.encounters.size()
        );
    }

    private static SpeciesChoice choose(RandomSource random, List<SpeciesChoice> choices) {
        int total = choices.stream().mapToInt(SpeciesChoice::weight).sum();
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (SpeciesChoice choice : choices) {
            roll -= choice.weight();
            if (roll < 0) return choice;
        }
        return choices.getLast();
    }

    private static BlockPos findSpawnPosition(ServerPlayer player, Config config) {
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = config.minimumDistance() + random.nextDouble()
                * Math.max(1, config.maximumDistance() - config.minimumDistance());
            int x = (int)Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int)Math.floor(player.getZ() + Math.sin(angle) * radius);
            if (level.dimension().equals(CobbleventureBootstrap.GENERATION_ONE)) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos result = new BlockPos(x, y, z);
                if (level.getBlockState(result).isAir() && level.getBlockState(result.above()).isAir()) return result;
                continue;
            }
            int top = Math.min(level.getMaxBuildHeight() - 3, player.getBlockY() + 7);
            int bottom = Math.max(level.getMinBuildHeight() + 1, player.getBlockY() - 12);
            for (int y = top; y >= bottom; y--) {
                BlockPos result = new BlockPos(x, y, z);
                if (!level.getBlockState(result.below()).isAir()
                    && level.getBlockState(result).isAir()
                    && level.getBlockState(result.above()).isAir()) return result;
            }
        }
        return null;
    }

    private static void removeUnavailableEncounters(
        ServerLevel level, ServerPlayer player, State state
    ) {
        var iterator = state.encounters.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            if (!(level.getEntity(id) instanceof PokemonEntity entity) || !entity.isAlive()) {
                iterator.remove();
                if (id.equals(state.pursuer)) state.pursuer = null;
                continue;
            }
            if (!entity.isBattling() && entity.distanceToSqr(player) > DESPAWN_DISTANCE_SQUARED) {
                entity.discard();
                iterator.remove();
                if (id.equals(state.pursuer)) state.pursuer = null;
            }
        }
    }

    private static void acquireVisiblePursuer(ServerPlayer player, State state, long gameTime) {
        if (gameTime < state.nextAggroTick) return;
        PokemonEntity closest = null;
        double closestDistance = DETECTION_DISTANCE_SQUARED;
        for (UUID id : state.encounters) {
            if (!(player.serverLevel().getEntity(id) instanceof PokemonEntity entity)) continue;
            double distance = entity.distanceToSqr(player);
            if (distance <= closestDistance && entity.getSensing().hasLineOfSight(player)) {
                closest = entity;
                closestDistance = distance;
            }
        }
        if (closest == null) return;
        state.pursuer = closest.getUUID();
        state.pursuitStartTick = gameTime;
        state.battleStartTick = -1L;
        spawnAlert(player.serverLevel(), closest, state, gameTime);
    }

    private static void spawnAlert(
        ServerLevel level, PokemonEntity pokemon, State state, long gameTime
    ) {
        discardAlert(level, state);
        Display.TextDisplay alert = EntityType.TEXT_DISPLAY.create(level);
        if (alert == null) return;
        configureAlertDisplay(alert, 0.45F, 0);
        alert.setPos(
            pokemon.getX(), pokemon.getY() + pokemon.getBbHeight() + 0.25D, pokemon.getZ()
        );
        if (!level.addFreshEntity(alert)) return;
        state.alert = alert.getUUID();
        state.alertStartTick = gameTime;
        level.playSound(
            null, pokemon.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(),
            SoundSource.HOSTILE, 0.9F, 1.35F
        );
    }

    private static void tickAlert(ServerLevel level, State state, long gameTime) {
        if (state.alert == null) return;
        if (!(level.getEntity(state.alert) instanceof Display.TextDisplay alert)
            || !(level.getEntity(state.pursuer) instanceof PokemonEntity pokemon)) {
            discardAlert(level, state);
            return;
        }
        long age = gameTime - state.alertStartTick;
        if (age >= ALERT_ANIMATION_TICKS) {
            discardAlert(level, state);
            return;
        }
        if (age == 1L) configureAlertDisplay(alert, 1.0F, 6);
        double progress = Math.min(1.0D, age / 10.0D);
        double eased = 1.0D - Math.pow(1.0D - progress, 3.0D);
        alert.setPos(
            pokemon.getX(),
            pokemon.getY() + pokemon.getBbHeight() + 0.25D + eased * 0.7D,
            pokemon.getZ()
        );
    }

    private static void configureAlertDisplay(
        Display.TextDisplay alert, float scale, int interpolationTicks
    ) {
        CompoundTag data = alert.saveWithoutId(new CompoundTag());
        data.putString("text", "{\"text\":\"!\",\"color\":\"#ff2020\",\"bold\":true}");
        data.putString("billboard", "center");
        data.putInt("background", 0);
        data.putBoolean("shadow", true);
        data.putBoolean("see_through", true);
        data.putFloat("view_range", 1.0F);
        data.putInt("interpolation_duration", interpolationTicks);
        data.putInt("start_interpolation", 0);
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(0.0F, 0.0F, 0.0F));
        transformation.put("scale", floatList(scale, scale, scale));
        transformation.put("left_rotation", floatList(0.0F, 0.0F, 0.0F, 1.0F));
        transformation.put("right_rotation", floatList(0.0F, 0.0F, 0.0F, 1.0F));
        data.put("transformation", transformation);
        alert.load(data);
    }

    private static ListTag floatList(float... values) {
        ListTag result = new ListTag();
        for (float value : values) result.add(FloatTag.valueOf(value));
        return result;
    }

    private static void discardAlert(ServerLevel level, State state) {
        if (state.alert != null && level.getEntity(state.alert) instanceof Display.TextDisplay alert) {
            alert.discard();
        }
        state.alert = null;
    }

    private static void tickPursuer(ServerPlayer player, State state, long gameTime) {
        if (!(player.serverLevel().getEntity(state.pursuer) instanceof PokemonEntity entity)
            || !entity.isAlive()) {
            state.pursuer = null;
            state.nextAggroTick = gameTime + AGGRO_COOLDOWN_TICKS;
            return;
        }
        long age = gameTime - state.pursuitStartTick;
        if (age > PURSUIT_TICKS || entity.distanceToSqr(player) > DESPAWN_DISTANCE_SQUARED) {
            LOGGER.info(
                "[Spawn diagnosis] Pursuit escaped: player={}, species={}, ageTicks={}, distance={}",
                player.getGameProfile().getName(), entity.getPokemon().getSpecies().getResourceIdentifier(),
                age, String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(entity.distanceToSqr(player)))
            );
            entity.getNavigation().stop();
            discardAlert(player.serverLevel(), state);
            state.pursuer = null;
            state.nextAggroTick = gameTime + AGGRO_COOLDOWN_TICKS;
            return;
        }
        if (age < WARNING_TICKS) return;
        entity.getLookControl().setLookAt(player, 30.0F, 30.0F);
        entity.getNavigation().moveTo(player, pursuitSpeed(age));
        if (entity.distanceToSqr(player) <= 2.5D * 2.5D && entity.canBattle(player)) {
            entity.getNavigation().stop();
            if (state.battleStartTick < 0L) {
                state.battleStartTick = gameTime + 1L;
                return;
            }
            if (gameTime < state.battleStartTick) return;
            boolean started;
            try {
                started = entity.forceBattle(player);
            } catch (RuntimeException error) {
                LOGGER.error(
                    "[Spawn diagnosis] Ambient battle start failed safely: player={}, species={}",
                    player.getGameProfile().getName(),
                    entity.getPokemon().getSpecies().getResourceIdentifier(), error
                );
                entity.discard();
                state.encounters.remove(entity.getUUID());
                state.pursuer = null;
                discardAlert(player.serverLevel(), state);
                state.nextAggroTick = gameTime + AGGRO_COOLDOWN_TICKS;
                return;
            }
            if (!started) {
                state.battleStartTick = gameTime + 20L;
                return;
            }
            LOGGER.info(
                "[Spawn diagnosis] Pursuit battle started: player={}, species={}",
                player.getGameProfile().getName(), entity.getPokemon().getSpecies().getResourceIdentifier()
            );
            state.encounters.remove(entity.getUUID());
            state.pursuer = null;
            discardAlert(player.serverLevel(), state);
            state.nextAggroTick = gameTime + AGGRO_COOLDOWN_TICKS;
        }
    }

    private static double pursuitSpeed(long pursuitAge) {
        double progress = Math.min(
            1.0D,
            Math.max(0L, pursuitAge - WARNING_TICKS) / (double) PURSUIT_ACCELERATION_TICKS
        );
        return INITIAL_PURSUIT_SPEED
            + (MAXIMUM_PURSUIT_SPEED - INITIAL_PURSUIT_SPEED) * progress;
    }

    static void forget(ServerPlayer player) {
        State state = STATES.remove(player.getUUID());
        if (state != null) reset(player.serverLevel(), state, null);
    }

    private static void reset(ServerLevel level, State state, String areaId) {
        discardAlert(level, state);
        for (UUID id : state.encounters) {
            if (level.getEntity(id) instanceof PokemonEntity entity && !entity.isBattling()) {
                entity.discard();
            }
        }
        state.areaId = areaId;
        state.encounters.clear();
        state.pursuer = null;
        state.nextSpawnTick = 0L;
        state.nextAggroTick = 0L;
        state.battleStartTick = -1L;
    }

    record Config(String id, int minimumDistance, int maximumDistance, List<SpeciesChoice> species) {}
    record SpeciesChoice(
        String species, int minLevel, int maxLevel, int weight, boolean spawnAsEvolved
    ) {}
    private static final class State {
        private String areaId;
        private final Set<UUID> encounters = new HashSet<>();
        private UUID pursuer;
        private UUID alert;
        private long pursuitStartTick;
        private long alertStartTick;
        private long battleStartTick = -1L;
        private long nextSpawnTick;
        private long nextAggroTick;
        private String diagnosticStatus = "";
    }
}
