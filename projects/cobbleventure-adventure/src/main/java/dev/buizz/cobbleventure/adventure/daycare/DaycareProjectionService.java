package dev.buizz.cobbleventure.adventure.daycare;

import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Maintains non-interactive visual copies of every deposited Pokemon near the daycare yard. */
public final class DaycareProjectionService {
    public static final String ENTITY_TAG = "cobbleventure_daycare_projection";
    private static final String JOB_ID = "cobbleventureDaycareJob";
    private static final String POKEMON_INDEX = "cobbleventureDaycarePokemon";
    private static final int UPDATE_INTERVAL_TICKS = 40;
    private static final double VIEW_DISTANCE_SQUARED = 64.0D * 64.0D;
    private static final double PADDOCK_RADIUS_SQUARED = 5.0D * 5.0D;

    private DaycareProjectionService() {}

    static void reset(MinecraftServer server, UUID jobId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof PokemonEntity pokemon
                    && pokemon.getTags().contains(ENTITY_TAG)
                    && pokemon.getPersistentData().hasUUID(JOB_ID)
                    && pokemon.getPersistentData().getUUID(JOB_ID).equals(jobId)) {
                    pokemon.discard();
                }
            }
        }
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(DaycareProjectionService::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        Map<UUID, DaycareJob> jobs = new HashMap<>();
        for (DaycareJob job : DaycareSavedData.get(server).snapshotJobs()) {
            jobs.put(job.jobId(), job);
        }

        Set<ProjectionKey> present = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof PokemonEntity pokemon)
                    || !pokemon.getTags().contains(ENTITY_TAG)) {
                    continue;
                }
                UUID jobId = pokemon.getPersistentData().hasUUID(JOB_ID)
                    ? pokemon.getPersistentData().getUUID(JOB_ID) : null;
                int pokemonIndex = pokemon.getPersistentData().getInt(POKEMON_INDEX);
                DaycareJob job = jobId == null ? null : jobs.get(jobId);
                if (job == null || pokemonIndex < 0 || pokemonIndex >= job.pokemonCount()
                    || !isVisible(server, job, level)) {
                    pokemon.discard();
                    continue;
                }
                ProjectionKey key = new ProjectionKey(jobId, pokemonIndex);
                if (!present.add(key)) {
                    pokemon.discard();
                    continue;
                }
                keepInsidePaddock(pokemon, job.paddockCenter(), pokemonIndex);
            }
        }

        for (DaycareJob job : jobs.values()) {
            ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(
                Registries.DIMENSION, job.facilityDimension()
            );
            ServerLevel level = server.getLevel(dimension);
            if (level == null || !isVisible(server, job, level)) {
                continue;
            }
            for (int pokemonIndex = 0; pokemonIndex < job.pokemonCount(); pokemonIndex++) {
                ProjectionKey key = new ProjectionKey(job.jobId(), pokemonIndex);
                if (!present.contains(key)) {
                    spawn(level, job, pokemonIndex);
                }
            }
        }
    }

    private static boolean isVisible(
        MinecraftServer server, DaycareJob job, ServerLevel level
    ) {
        if (!level.dimension().location().equals(job.facilityDimension())) {
            return false;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(job.ownerId());
        return owner != null && owner.serverLevel() == level
            && owner.distanceToSqr(Vec3.atCenterOf(job.paddockCenter()))
                <= VIEW_DISTANCE_SQUARED;
    }

    private static void spawn(ServerLevel level, DaycareJob job, int pokemonIndex) {
        Pokemon pokemon = new Pokemon().loadFromNBT(
            level.registryAccess(), job.pokemon(pokemonIndex).data()
        );
        pokemon.setUuid(UUID.randomUUID());
        PokemonEntity entity = new PokemonEntity(level, pokemon, CobblemonEntities.POKEMON);
        entity.addTag(ENTITY_TAG);
        entity.getPersistentData().putUUID(JOB_ID, job.jobId());
        entity.getPersistentData().putInt(POKEMON_INDEX, pokemonIndex);
        entity.setInvulnerable(true);
        entity.setPersistenceRequired();
        entity.setCountsTowardsSpawnCap(false);
        BlockPos spawn = paddockPosition(job.paddockCenter(), pokemonIndex);
        entity.moveTo(
            spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
            level.random.nextFloat() * 360.0F, 0.0F
        );
        level.addFreshEntity(entity);
    }

    private static void keepInsidePaddock(
        PokemonEntity entity, BlockPos center, int pokemonIndex
    ) {
        if (entity.distanceToSqr(Vec3.atCenterOf(center)) <= PADDOCK_RADIUS_SQUARED) {
            return;
        }
        BlockPos target = paddockPosition(center, pokemonIndex);
        entity.teleportTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
        entity.getNavigation().stop();
    }

    private static BlockPos paddockPosition(BlockPos center, int pokemonIndex) {
        int[][] offsets = {{-3, 2}, {3, -2}, {-2, -3}, {2, 3}, {-4, -1}, {4, 1}};
        int[] offset = offsets[Math.floorMod(pokemonIndex, offsets.length)];
        int x = center.getX() + offset[0];
        int z = center.getZ() + offset[1];
        // The structure anchor already denotes a walkable floor. Keeping its Y avoids
        // accidentally placing projections on the daycare roof.
        return new BlockPos(x, center.getY(), z);
    }

    private record ProjectionKey(UUID jobId, int pokemonIndex) {}
}
