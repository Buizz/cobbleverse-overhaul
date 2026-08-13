package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.bootstrap.client.LocalWeatherEffects;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/** Resolves biome weather defaults and per-hex overrides for each player. */
final class LocalWeatherSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NETWORK_VERSION = "1";
    private static final Map<UUID, String> PLAYING = new HashMap<>();
    private static ResourceManager loadedFrom;
    private static Map<String, String> weatherByBiome = Map.of();

    private LocalWeatherSystem() {}

    static void register(IEventBus modBus) {
        modBus.addListener(LocalWeatherSystem::registerPayloads);
    }

    static void tick(
        ServerPlayer player,
        CobbleventureBootstrap.HexWorldPlan world,
        CobbleventureBootstrap.TerrainSample sample
    ) {
        String configured = authoredWeatherAt(player, world, sample);
        send(player, configured == null
            ? naturalWeather(player.serverLevel()) : configured, true);
    }

    /** Returns only catalog/map-authored weather, never Minecraft's global weather. */
    static String authoredWeatherAt(
        ServerPlayer player,
        CobbleventureBootstrap.HexWorldPlan world,
        CobbleventureBootstrap.TerrainSample sample
    ) {
        String configured = null;
        CobbleventureBootstrap.HexCoord coordinate = world.grid().worldToHex(
            player.getX(), player.getZ()
        );
        CobbleventureBootstrap.EnvironmentOverride override =
            world.environmentOverrides().get(coordinate);
        if (override != null) {
            configured = override.weather();
        }
        if (configured == null && sample != null) {
            configured = load(player.serverLevel()).get(sample.biome());
        }
        return configured == null || configured.equals("inherit") ? null : configured;
    }

    static void clear(ServerPlayer player) {
        send(player, naturalWeather(player.serverLevel()), false);
    }

    static void reset(ServerPlayer player) {
        PLAYING.remove(player.getUUID());
    }

    private static String naturalWeather(ServerLevel level) {
        if (level.isThundering()) return "natural_thunder";
        if (level.isRaining()) return "natural_rain";
        return "natural_clear";
    }

    private static void send(ServerPlayer player, String weather, boolean refresh) {
        if (!refresh && weather.equals(PLAYING.put(player.getUUID(), weather))) return;
        PLAYING.put(player.getUUID(), weather);
        PacketDistributor.sendToPlayer(player, new WeatherPayload(weather));
    }

    private static Map<String, String> load(ServerLevel level) {
        ResourceManager resources = level.getServer().getResourceManager();
        if (loadedFrom == resources) return weatherByBiome;
        loadedFrom = resources;
        PLAYING.clear();
        try {
            weatherByBiome = read(resources);
        } catch (IllegalStateException error) {
            LOGGER.error("Biome weather configuration could not be loaded", error);
            weatherByBiome = Map.of();
        }
        return weatherByBiome;
    }

    private static Map<String, String> read(ResourceManager resources) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            "cobbleventure", "catalogs/biome-profiles.json"
        );
        Resource resource = resources.getResource(location).orElseThrow(() ->
            new IllegalStateException("Missing biome weather catalog: " + location)
        );
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, String> result = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("profiles")) {
                JsonObject profile = element.getAsJsonObject();
                String weather = profile.has("weather")
                    ? profile.get("weather").getAsString() : "inherit";
                if (weather.equals("inherit")) continue;
                if (!profile.has("minecraft_biomes")) continue;
                for (JsonElement biome : profile.getAsJsonArray("minecraft_biomes")) {
                    String id = biome.getAsString();
                    String previous = result.putIfAbsent(id, weather);
                    if (previous != null && !previous.equals(weather)) {
                        throw new IllegalStateException(
                            "Conflicting biome weather defaults for " + id
                                + ": " + previous + " / " + weather
                        );
                    }
                }
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid biome weather catalog: " + location, error);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(
            WeatherPayload.TYPE, WeatherPayload.STREAM_CODEC, LocalWeatherSystem::apply
        );
    }

    private static void apply(WeatherPayload payload, IPayloadContext context) {
        LocalWeatherEffects.apply(payload.weather());
    }

    private record WeatherPayload(String weather) implements CustomPacketPayload {
        private static final Type<WeatherPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "local_weather"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, WeatherPayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> buffer.writeUtf(payload.weather()),
                buffer -> new WeatherPayload(buffer.readUtf())
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
