package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.PokemonCenterDefeatReturn;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/** Applies the authored starting location on a player's first entry into each generation. */
final class StarterSpawnSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SETTINGS = ResourceLocation.fromNamespaceAndPath(
        "cobbleventure", "catalogs/starter-settings.json"
    );
    private static final ResourceLocation BUILDING_INTERIORS =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "building_interiors");
    private static final String WAITING = "cobbleventureGenerationWaiting";
    private static final String STARTED_PREFIX = "cobbleventureStarterGeneration";
    private static final String VALIDATED_PREFIX = "cobbleventureStarterDestinationValidatedV2";
    private static volatile int defaultGeneration = 1;
    private static volatile Map<Integer, StarterConfig> configs = Map.of();

    private StarterSpawnSystem() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(StarterSpawnSystem::onChangedDimension);
        NeoForge.EVENT_BUS.addListener(StarterSpawnSystem::onLoggedIn);
    }

    static void initialize(MinecraftServer server) {
        Resource resource = server.getResourceManager().getResource(SETTINGS).orElse(null);
        if (resource == null) {
            LOGGER.warn("Starter settings are missing: {}", SETTINGS);
            configs = Map.of();
            defaultGeneration = 1;
            return;
        }
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int parsedDefault = root.get("default_generation").getAsInt();
            Map<Integer, StarterConfig> parsed = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("generations")) {
                JsonObject entry = element.getAsJsonObject();
                int generation = entry.get("generation").getAsInt();
                JsonObject spawn = entry.getAsJsonObject("spawn");
                parsed.put(generation, new StarterConfig(
                    generation,
                    !entry.has("enabled") || entry.get("enabled").getAsBoolean(),
                    entry.get("town").getAsString(),
                    spawn.get("mode").getAsString(),
                    !spawn.has("set_respawn") || spawn.get("set_respawn").getAsBoolean(),
                    optional(spawn, "building"),
                    optional(spawn, "space"),
                    optional(spawn, "npc_slot")
                ));
            }
            defaultGeneration = parsedDefault;
            configs = Map.copyOf(parsed);
            LOGGER.info(
                "Starter settings loaded: defaultGeneration={}, generations={}",
                defaultGeneration, configs.keySet()
            );
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid starter settings: " + SETTINGS, error);
        }
    }

    static boolean movePlayerToDefaultStart(
        ServerPlayer player, ServerLevel fallbackLevel, BlockPos fallbackPosition
    ) {
        StarterConfig config = configs.get(defaultGeneration);
        BuildingRuntimeSystem.SpawnDestination destination = config == null || !config.enabled
            ? null : CobbleventureBootstrap.resolveStarterSpawn(player.getServer(), config);
        if (destination == null) {
            if (config != null && config.enabled) {
                LOGGER.error(
                    "Configured starter destination is unavailable; refusing to confirm the "
                        + "world fallback as the player's start: generation={}, building={}, "
                        + "space={}, slot={}",
                    config.generation, config.building, config.space, config.npcSlot
                );
                return false;
            }
            destination = new BuildingRuntimeSystem.SpawnDestination(
                fallbackLevel, fallbackPosition.above(), 0.0F
            );
        }
        int generation = config == null ? 1 : config.generation;
        // Dimension-change events fire from teleportTo. Persist the one-shot state first so
        // the callback cannot mistake this handoff for another first arrival.
        markStarted(player, generation);
        markValidated(player, generation);
        move(player, destination, config == null || config.setRespawn);
        return true;
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        StarterConfig config = configs.get(defaultGeneration);
        if (config == null || !config.enabled || !hasStarted(player, config.generation)) {
            return;
        }
        if (isValidated(player, config.generation)) {
            String generationDimension = "cobbleventure:generation_" + config.generation;
            BlockPos current = player.blockPosition();
            if (!player.serverLevel().dimension().location().toString()
                    .equals(generationDimension)
                || !player.serverLevel().getBlockState(current.below()).isAir()) {
                return;
            }
            BuildingRuntimeSystem.SpawnDestination repair =
                CobbleventureBootstrap.resolveStarterSpawn(player.getServer(), config);
            if (repair != null) {
                LOGGER.warn(
                    "Repairing unsupported multiplayer starter position: player={}, current={}",
                    player.getGameProfile().getName(), current
                );
                move(player, repair, config.setRespawn);
                CobbleventureBootstrap.markPlayerStarted(player);
            }
            return;
        }
        BuildingRuntimeSystem.SpawnDestination destination =
            CobbleventureBootstrap.resolveStarterSpawn(player.getServer(), config);
        if (destination == null) {
            return;
        }
        BlockPos position = destination.position();
        if (config.setRespawn) {
            player.setRespawnPosition(
                destination.level().dimension(), position, destination.yaw(), true, false
            );
            PokemonCenterDefeatReturn.recordStarterFallback(
                player, destination.level(), position
            );
        }
        BlockPos current = player.blockPosition();
        boolean correctDimension = player.serverLevel().dimension().equals(
            destination.level().dimension()
        );
        if (!correctDimension || current.distSqr(position) > 64.0D
            || player.serverLevel().getBlockState(current.below()).isAir()) {
            move(player, destination, config.setRespawn);
        }
        markValidated(player, config.generation);
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || player.getPersistentData().getBoolean(WAITING)) {
            return;
        }
        String target = event.getTo().location().toString();
        StarterConfig config = configs.values().stream()
            .filter(candidate -> candidate.enabled)
            .filter(candidate -> target.equals("cobbleventure:generation_" + candidate.generation))
            .findFirst().orElse(null);
        if (config == null || hasStarted(player, config.generation)) {
            return;
        }
        if (isBuildingInteriorExit(event.getFrom().location())) {
            // Players created before the starter marker existed can legitimately leave their
            // authored starting room without a STARTED flag. Treat that exit as completion;
            // redirecting them back through the starter teleport causes the one-time roof/void
            // correction seen while generation_1 chunks are handed to the client.
            markStarted(player, config.generation);
            markValidated(player, config.generation);
            CobbleventureBootstrap.markPlayerStarted(player);
            LOGGER.info(
                "Accepted starter building exit as generation arrival: player={}, generation={}",
                player.getGameProfile().getName(), config.generation
            );
            return;
        }
        BuildingRuntimeSystem.SpawnDestination destination =
            CobbleventureBootstrap.resolveStarterSpawn(player.getServer(), config);
        if (destination != null) {
            markStarted(player, config.generation);
            markValidated(player, config.generation);
            move(player, destination, config.setRespawn);
            CobbleventureBootstrap.markPlayerStarted(player);
        }
    }

    static boolean isBuildingInteriorExit(ResourceLocation sourceDimension) {
        return BUILDING_INTERIORS.equals(sourceDimension);
    }

    private static void move(
        ServerPlayer player, BuildingRuntimeSystem.SpawnDestination destination,
        boolean setRespawn
    ) {
        BlockPos position = destination.position();
        destination.level().getChunk(position.getX() >> 4, position.getZ() >> 4);
        player.stopRiding();
        player.teleportTo(
            destination.level(),
            position.getX() + 0.5D,
            position.getY() + 0.05D,
            position.getZ() + 0.5D,
            destination.yaw(),
            0.0F
        );
        InteriorMusicSystem.sync(player);
        if (setRespawn) {
            player.setRespawnPosition(
                destination.level().dimension(), position, destination.yaw(), true, false
            );
            PokemonCenterDefeatReturn.recordStarterFallback(
                player, destination.level(), position
            );
        }
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.resetFallDistance();
    }

    private static boolean hasStarted(ServerPlayer player, int generation) {
        return player.getPersistentData().getBoolean(STARTED_PREFIX + generation);
    }

    private static void markStarted(ServerPlayer player, int generation) {
        player.getPersistentData().putBoolean(STARTED_PREFIX + generation, true);
    }

    private static boolean isValidated(ServerPlayer player, int generation) {
        return player.getPersistentData().getBoolean(VALIDATED_PREFIX + generation);
    }

    private static void markValidated(ServerPlayer player, int generation) {
        player.getPersistentData().putBoolean(VALIDATED_PREFIX + generation, true);
    }

    private static String optional(JsonObject object, String field) {
        return object.has(field) ? object.get(field).getAsString() : null;
    }

    record StarterConfig(
        int generation, boolean enabled, String town, String mode, boolean setRespawn,
        String building, String space, String npcSlot
    ) {
    }
}
