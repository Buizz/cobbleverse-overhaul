package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Distance-driven, avoidable wild encounters shared by authored caves and forests. */
final class PursuitEncounterSystem {
    static final String ENTITY_TAG = "cobbleventure_pursuit_encounter";
    private static final int WARNING_TICKS = 15;
    private static final int PURSUIT_TICKS = 20 * 12;
    private static final int COOLDOWN_TICKS = 40;
    private static final double ESCAPE_DISTANCE_SQUARED = 32.0D * 32.0D;
    private static final Map<UUID, State> STATES = new HashMap<>();

    private PursuitEncounterSystem() {}

    static Config parse(
        String id, JsonObject document, JsonObject biomeProfiles, JsonObject pokemonHabitats
    ) {
        JsonObject settings = document.getAsJsonObject("random_encounters");
        if (settings == null || !settings.get("enabled").getAsBoolean()) return null;
        String biome = settings.get("pokemon_biome").getAsString();
        Set<String> habitats = new HashSet<>();
        boolean includeSecondary = false;
        for (JsonElement element : biomeProfiles.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            boolean matches = false;
            for (JsonElement candidate : profile.getAsJsonArray("minecraft_biomes")) {
                if (candidate.getAsString().equals(biome)) matches = true;
            }
            if (!matches) continue;
            habitats.add(profile.get("habitat").getAsString());
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
                if (excluded.contains(species)) continue;
                JsonObject pokemonHabitatsValue = pokemon.getAsJsonObject("habitats");
                String primary = pokemonHabitatsValue.get("primary").getAsString();
                String secondary = pokemonHabitatsValue.has("secondary")
                    ? pokemonHabitatsValue.get("secondary").getAsString() : "";
                if (!habitats.contains(primary) && !(includeSecondary && habitats.contains(secondary))) {
                    continue;
                }
                String rarity = pokemon.has("preferences")
                    && pokemon.getAsJsonObject("preferences").has("rarity")
                    ? pokemon.getAsJsonObject("preferences").get("rarity").getAsString() : "common";
                choices.put(species, new SpeciesChoice(
                    species, defaultMinimumLevel, defaultMaximumLevel, rarityWeight(rarity)
                ));
            }
        }
        for (JsonElement element : settings.getAsJsonArray("additions")) {
            JsonObject addition = element.getAsJsonObject();
            String species = addition.get("species").getAsString();
            choices.put(species, new SpeciesChoice(
                species, defaultMinimumLevel, defaultMaximumLevel, 20
            ));
        }
        if (settings.has("level_overrides")) {
            for (JsonElement element : settings.getAsJsonArray("level_overrides")) {
                JsonObject override = element.getAsJsonObject();
                String species = override.get("species").getAsString();
                SpeciesChoice current = choices.get(species);
                if (current != null) choices.put(species, new SpeciesChoice(
                    species, override.get("min_level").getAsInt(),
                    override.get("max_level").getAsInt(), current.weight()
                ));
            }
        }
        return new Config(
            id, settings.get("minimum_distance").getAsInt(),
            settings.get("maximum_distance").getAsInt(), List.copyOf(choices.values())
        );
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
        if (config == null || config.species().isEmpty() || !eligible(player)) {
            reset(player.serverLevel(), state, config == null ? null : config.id());
            state.lastPosition = player.position();
            return;
        }
        if (!config.id().equals(state.areaId)) reset(player.serverLevel(), state, config.id());
        if (state.lastPosition == null) state.lastPosition = player.position();
        if (state.pursuer != null) {
            tickPursuer(player, state, gameTime);
            state.lastPosition = player.position();
            return;
        }
        if (state.cooldown > 0) state.cooldown--;
        Vec3 current = player.position();
        double dx = current.x - state.lastPosition.x;
        double dz = current.z - state.lastPosition.z;
        double movement = Math.sqrt(dx * dx + dz * dz);
        if (state.cooldown == 0 && movement <= 4.0D && !player.isPassenger()
            && !player.isSwimming() && !player.isFallFlying()) {
            state.distance += movement;
        }
        state.lastPosition = current;
        if (state.targetDistance <= 0.0D) state.targetDistance = randomDistance(player.getRandom(), config);
        if (state.distance >= state.targetDistance) spawn(player, config, state, gameTime);
    }

    private static boolean eligible(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        return !player.isSpectator() && mode != GameType.CREATIVE
            && player.isAlive() && BattleRegistry.getBattleByParticipatingPlayer(player) == null;
    }

    private static double randomDistance(RandomSource random, Config config) {
        return config.minimumDistance() + random.nextInt(
            Math.max(1, config.maximumDistance() - config.minimumDistance() + 1)
        );
    }

    private static void spawn(ServerPlayer player, Config config, State state, long gameTime) {
        SpeciesChoice choice = choose(player.getRandom(), config.species());
        BlockPos position = findSpawnPosition(player);
        if (choice == null || position == null) {
            state.distance = Math.max(0.0D, state.distance - 12.0D);
            return;
        }
        int level = choice.minLevel() + player.getRandom().nextInt(
            Math.max(1, choice.maxLevel() - choice.minLevel() + 1)
        );
        PokemonEntity entity = PokemonProperties.Companion
            .parse(choice.species() + " level=" + level).createEntity(player.serverLevel());
        entity.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, player.getYRot(), 0.0F);
        entity.setCustomName(Component.literal("!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        entity.setCustomNameVisible(true);
        entity.setCountsTowardsSpawnCap(false);
        entity.addTag(ENTITY_TAG);
        if (!player.serverLevel().addFreshEntity(entity)) return;
        state.pursuer = entity.getUUID();
        state.spawnTick = gameTime;
        state.distance = 0.0D;
        state.targetDistance = 0.0D;
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

    private static BlockPos findSpawnPosition(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RandomSource random = player.getRandom();
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = 9.0D + random.nextDouble() * 5.0D;
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

    private static void tickPursuer(ServerPlayer player, State state, long gameTime) {
        if (!(player.serverLevel().getEntity(state.pursuer) instanceof PokemonEntity entity)
            || !entity.isAlive()) {
            state.pursuer = null;
            state.cooldown = COOLDOWN_TICKS;
            return;
        }
        long age = gameTime - state.spawnTick;
        if (age > PURSUIT_TICKS || entity.distanceToSqr(player) > ESCAPE_DISTANCE_SQUARED) {
            entity.discard();
            state.pursuer = null;
            state.cooldown = COOLDOWN_TICKS;
            return;
        }
        if (age < WARNING_TICKS) return;
        entity.getLookControl().setLookAt(player, 30.0F, 30.0F);
        entity.getNavigation().moveTo(player, 1.35D);
        if (entity.distanceToSqr(player) <= 2.5D * 2.5D && entity.canBattle(player)
            && entity.forceBattle(player)) {
            entity.setCustomNameVisible(false);
            state.pursuer = null;
            state.cooldown = COOLDOWN_TICKS;
        }
    }

    static void forget(ServerPlayer player) {
        State state = STATES.remove(player.getUUID());
        if (state != null) reset(player.serverLevel(), state, null);
    }

    private static void reset(ServerLevel level, State state, String areaId) {
        if (state.pursuer != null && level.getEntity(state.pursuer) instanceof PokemonEntity entity
            && !entity.isBattling()) entity.discard();
        state.areaId = areaId;
        state.distance = 0.0D;
        state.targetDistance = 0.0D;
        state.pursuer = null;
        state.cooldown = COOLDOWN_TICKS;
    }

    record Config(String id, int minimumDistance, int maximumDistance, List<SpeciesChoice> species) {}
    record SpeciesChoice(String species, int minLevel, int maxLevel, int weight) {}
    private static final class State {
        private String areaId;
        private Vec3 lastPosition;
        private double distance;
        private double targetDistance;
        private UUID pursuer;
        private long spawnTick;
        private int cooldown;
    }
}
