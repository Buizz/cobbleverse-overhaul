package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.buizz.cobbleventure.playermenu.client.LoopingMusic;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/** Resolves authored music inheritance and emits optional resource-pack sound events. */
public final class MusicPlayback {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONTENT_NAMESPACE = "cobbleventure";
    private static final String NETWORK_VERSION = "1";
    private static final long BATTLE_START_GRACE_TICKS = 20L * 10L;
    private static final long VICTORY_MUSIC_TICKS = 20L * 8L;
    private static final Map<UUID, String> PLAYING = new HashMap<>();
    private static final Map<UUID, BattleMusic> BATTLE_MUSIC = new HashMap<>();
    private static final Map<UUID, VictoryMusic> VICTORY_MUSIC = new HashMap<>();
    private static final Map<UUID, String> ENCOUNTER_MUSIC = new HashMap<>();
    private static final Map<UUID, String> INTERIOR_MUSIC = new HashMap<>();
    private static ResourceManager loadedFrom;
    private static MusicData data;

    private MusicPlayback() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(MusicPlayback::registerPayloads);
        NeoForge.EVENT_BUS.addListener(MusicPlayback::registerCommands);
        NeoForge.EVENT_BUS.addListener(MusicPlayback::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(MusicPlayback::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(MusicPlayback::onPlayerChangedDimension);
        CobblemonEvents.BATTLE_VICTORY.subscribe(
            (Consumer<BattleVictoryEvent>) MusicPlayback::onBattleVictory
        );
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) reset(player);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) reset(player);
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // The client sound engine discards the old level's sound instance. Keep the
            // authored context, but force the next location tick to send it again.
            PLAYING.remove(player.getUUID());
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(PlayPayload.TYPE, PlayPayload.STREAM_CODEC, MusicPlayback::handlePlay);
    }

    private static void handlePlay(PlayPayload payload, IPayloadContext context) {
        LoopingMusic.play(payload.soundEvent());
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_music")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("battle")
                    .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                        .then(Commands.argument("battle_id", StringArgumentType.string())
                            .executes(context -> prepareBattle(
                                net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player"),
                                StringArgumentType.getString(context, "battle_id")
                            )))))
        );
    }

    static int prepareBattle(ServerPlayer player, String battleId) {
        MusicData music = load(player.serverLevel());
        String track = music.resolveBattle(battleId);
        ENCOUNTER_MUSIC.remove(player.getUUID());
        BATTLE_MUSIC.put(player.getUUID(), new BattleMusic(
            track,
            music.resolveVictory(battleId),
            player.serverLevel().getGameTime() + BATTLE_START_GRACE_TICKS
        ));
        play(player, music, track);
        return 1;
    }

    static void prepareEncounter(ServerPlayer player, String track) {
        MusicData music = load(player.serverLevel());
        if (track == null || !music.soundEvents.containsKey(track)) {
            LOGGER.warn("Unknown trainer encounter music: {}", track);
            return;
        }
        ENCOUNTER_MUSIC.put(player.getUUID(), track);
        play(player, music, track);
    }

    static void cancelEncounter(ServerPlayer player) {
        if (ENCOUNTER_MUSIC.remove(player.getUUID()) != null) {
            PLAYING.remove(player.getUUID());
        }
    }

    /** Starts a building interior track, falling back to the generic interior default. */
    public static void enterInterior(ServerPlayer player, String track) {
        MusicData music = load(player.serverLevel());
        String resolved = MusicData.first(track, music.defaults.get("building"));
        if (resolved == null) return;
        INTERIOR_MUSIC.put(player.getUUID(), resolved);
        play(player, music, resolved);
    }

    /** Starts one of the authored facility defaults, such as pokemon_center or pokemart. */
    public static void enterFacility(ServerPlayer player, String context) {
        MusicData music = load(player.serverLevel());
        String resolved = MusicData.first(
            music.defaults.get(context), music.defaults.get("building")
        );
        if (resolved == null) return;
        INTERIOR_MUSIC.put(player.getUUID(), resolved);
        play(player, music, resolved);
    }

    /** Returns music ownership to the world-location resolver. */
    public static void leaveInterior(ServerPlayer player) {
        if (INTERIOR_MUSIC.remove(player.getUUID()) != null) {
            PLAYING.remove(player.getUUID());
        }
    }

    private static void reset(ServerPlayer player) {
        PLAYING.remove(player.getUUID());
        BATTLE_MUSIC.remove(player.getUUID());
        VICTORY_MUSIC.remove(player.getUUID());
        ENCOUNTER_MUSIC.remove(player.getUUID());
        INTERIOR_MUSIC.remove(player.getUUID());
    }

    private static void onBattleVictory(BattleVictoryEvent event) {
        for (BattleActor actor : event.getWinners()) {
            if (!(actor instanceof PlayerBattleActor playerActor)) continue;
            ServerPlayer player = playerActor.getEntity();
            if (player == null) continue;
            UUID playerId = player.getUUID();
            MusicData music = load(player.serverLevel());
            BattleMusic battleMusic = BATTLE_MUSIC.remove(playerId);
            String track = battleMusic != null
                ? battleMusic.victoryTrack
                : music.defaults.get(event.getBattle().isPvW()
                    ? "victory_wild" : "victory_trainer");
            if (track == null || !music.soundEvents.containsKey(track)) continue;
            ENCOUNTER_MUSIC.remove(playerId);
            VICTORY_MUSIC.put(playerId, new VictoryMusic(
                track, player.serverLevel().getGameTime() + VICTORY_MUSIC_TICKS
            ));
            play(player, music, track);
        }
    }

    public static void tick(
        ServerPlayer player, int q, int r, String areaKind, String areaOwner
    ) {
        MusicData music = load(player.serverLevel());
        if (tickPriority(player, music)) return;
        play(player, music, music.resolveLocation(new Cell(q, r), areaKind, areaOwner));
    }

    /** Resolves music for an authored cave or forest region in a separate dimension. */
    public static void tickDimension(ServerPlayer player, String areaKind, String areaOwner) {
        MusicData music = load(player.serverLevel());
        if (tickPriority(player, music)) return;
        play(player, music, music.resolveDimension(areaKind, areaOwner));
    }

    /** Re-sends retained battle, encounter, or building music after a dimension change. */
    public static void tickRetainedContext(ServerPlayer player) {
        tickPriority(player, load(player.serverLevel()));
    }

    private static boolean tickPriority(ServerPlayer player, MusicData music) {
        UUID playerId = player.getUUID();
        long gameTime = player.serverLevel().getGameTime();
        VictoryMusic victoryMusic = VICTORY_MUSIC.get(playerId);
        if (victoryMusic != null) {
            if (gameTime <= victoryMusic.expiresAt) {
                play(player, music, victoryMusic.track);
                return true;
            }
            VICTORY_MUSIC.remove(playerId);
        }
        BattleMusic battleMusic = BATTLE_MUSIC.get(playerId);
        var activeBattle = BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player);
        boolean battling = activeBattle != null;
        if (battling) {
            if (battleMusic == null) {
                battleMusic = new BattleMusic(
                    music.defaults.get("battle"),
                    music.defaults.get(activeBattle.isPvW()
                        ? "victory_wild" : "victory_trainer"),
                    gameTime
                );
                BATTLE_MUSIC.put(playerId, battleMusic);
            }
            battleMusic.started = true;
            play(player, music, battleMusic.track);
            return true;
        }
        if (battleMusic != null) {
            if (!battleMusic.started && gameTime <= battleMusic.expiresAt) return true;
            BATTLE_MUSIC.remove(playerId);
        }

        String encounterTrack = ENCOUNTER_MUSIC.get(playerId);
        if (encounterTrack != null) {
            play(player, music, encounterTrack);
            return true;
        }

        String interiorTrack = INTERIOR_MUSIC.get(playerId);
        if (interiorTrack != null) {
            play(player, music, interiorTrack);
            return true;
        }
        return false;
    }

    private static void play(ServerPlayer player, MusicData music, String track) {
        if (track == null || track.isBlank() || track.equals(PLAYING.get(player.getUUID()))) return;
        PLAYING.put(player.getUUID(), track);
        String soundEvent = music.soundEvents.get(track);
        if (soundEvent != null) {
            PacketDistributor.sendToPlayer(
                player,
                new PlayPayload(ResourceLocation.fromNamespaceAndPath(music.namespace, soundEvent))
            );
        }
    }

    /** Resolves a configured one-shot sound through the same tag catalog as BGM. */
    static ResourceLocation defaultSoundEvent(ServerPlayer player, String context) {
        MusicData music = load(player.serverLevel());
        String track = music.defaults.get(context);
        String soundEvent = track == null ? null : music.soundEvents.get(track);
        return soundEvent == null ? null : ResourceLocation.fromNamespaceAndPath(
            music.namespace, soundEvent
        );
    }

    private static MusicData load(ServerLevel level) {
        ResourceManager resources = level.getServer().getResourceManager();
        if (data != null && loadedFrom == resources) return data;
        loadedFrom = resources;
        try {
            data = MusicData.read(resources);
        } catch (IllegalStateException error) {
            LOGGER.error("Music configuration could not be loaded; music playback is disabled", error);
            data = MusicData.empty();
        }
        PLAYING.clear();
        BATTLE_MUSIC.clear();
        VICTORY_MUSIC.clear();
        ENCOUNTER_MUSIC.clear();
        INTERIOR_MUSIC.clear();
        return data;
    }

    private static JsonObject read(ResourceManager resources, String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            CONTENT_NAMESPACE, path
        );
        Resource resource = resources.getResource(location).orElseThrow(
            () -> new IllegalStateException("Missing packaged music resource: " + location)
        );
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Invalid packaged music resource: " + location, error);
        }
    }

    private static String optionalString(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonPrimitive()
            ? value.get(key).getAsString()
            : null;
    }

    private record Cell(int q, int r) {}

    record PlayPayload(ResourceLocation soundEvent) implements CustomPacketPayload {
        private static final Type<PlayPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, "music_play")
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, PlayPayload> STREAM_CODEC =
            StreamCodec.ofMember(PlayPayload::write, PlayPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeResourceLocation(soundEvent);
        }

        private static PlayPayload read(RegistryFriendlyByteBuf buffer) {
            return new PlayPayload(buffer.readResourceLocation());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final class BattleMusic {
        private final String track;
        private final String victoryTrack;
        private final long expiresAt;
        private boolean started;

        private BattleMusic(String track, String victoryTrack, long expiresAt) {
            this.track = track;
            this.victoryTrack = victoryTrack;
            this.expiresAt = expiresAt;
        }
    }

    private record VictoryMusic(String track, long expiresAt) {}

    private static final class MusicData {
        private final String namespace;
        private final Map<String, String> defaults;
        private final Map<String, String> soundEvents;
        private final Map<Cell, String> tileMusic;
        private final Map<Cell, String> coordinateOverrides;
        private final Map<String, String> routeMusic;
        private final Map<String, String> worldSettlementMusic;
        private final Map<String, String> settlementMusic;
        private final Map<String, String> caveMusic;
        private final Map<String, String> forestMusic;
        private final Map<String, JsonObject> battles;
        private final Map<String, String> gymByTrainer;
        private final Map<String, String> gymMusic;

        private MusicData(
            String namespace,
            Map<String, String> defaults,
            Map<String, String> soundEvents,
            Map<Cell, String> tileMusic,
            Map<Cell, String> coordinateOverrides,
            Map<String, String> routeMusic,
            Map<String, String> worldSettlementMusic,
            Map<String, String> settlementMusic,
            Map<String, String> caveMusic,
            Map<String, String> forestMusic,
            Map<String, JsonObject> battles,
            Map<String, String> gymByTrainer,
            Map<String, String> gymMusic
        ) {
            this.namespace = namespace;
            this.defaults = defaults;
            this.soundEvents = soundEvents;
            this.tileMusic = tileMusic;
            this.coordinateOverrides = coordinateOverrides;
            this.routeMusic = routeMusic;
            this.worldSettlementMusic = worldSettlementMusic;
            this.settlementMusic = settlementMusic;
            this.caveMusic = caveMusic;
            this.forestMusic = forestMusic;
            this.battles = battles;
            this.gymByTrainer = gymByTrainer;
            this.gymMusic = gymMusic;
        }

        private static MusicData read(ResourceManager resources) {
            JsonObject catalog = MusicPlayback.read(resources, "catalogs/music-tracks.json");
            String namespace = catalog.get("namespace").getAsString();
            Map<String, String> defaults = new HashMap<>();
            catalog.getAsJsonObject("defaults").entrySet().forEach(
                entry -> defaults.put(entry.getKey(), entry.getValue().getAsString())
            );
            Map<String, String> soundEvents = new HashMap<>();
            for (JsonElement element : catalog.getAsJsonArray("tracks")) {
                JsonObject track = element.getAsJsonObject();
                soundEvents.put(
                    track.get("id").getAsString(), track.get("sound_event").getAsString()
                );
            }

            JsonObject world = MusicPlayback.read(resources, "hex_worlds/generation_1.json");
            Map<Cell, String> tileMusic = musicByCell(world, "tiles");
            Map<Cell, String> coordinateOverrides = musicByCell(world, "music_overrides");
            Map<String, String> routeMusic = musicById(world, "connections", "id");
            Map<String, String> worldSettlementMusic = musicById(
                world, "settlements", "settlement"
            );

            Map<String, String> settlementMusic = new HashMap<>();
            resources.listResources("settlements", location -> location.getPath().endsWith(".json"))
                .forEach((location, resource) -> {
                    JsonObject settlement = readResource(location, resource);
                    String track = optionalString(settlement, "music_track");
                    if (track != null) settlementMusic.put(settlement.get("id").getAsString(), track);
                });

            Map<String, String> caveMusic = musicByDocument(resources, "caves");
            Map<String, String> forestMusic = musicByDocument(resources, "forests");

            Map<String, JsonObject> battles = new HashMap<>();
            resources.listResources("battles", location -> location.getPath().endsWith(".json"))
                .forEach((location, resource) -> {
                    JsonObject battle = readResource(location, resource);
                    battles.put(battle.get("id").getAsString(), battle);
                });

            Map<String, String> gymByTrainer = new HashMap<>();
            Map<String, String> gymMusic = new HashMap<>();
            JsonObject gyms = MusicPlayback.read(resources, "catalogs/gyms.json");
            for (JsonElement element : gyms.getAsJsonArray("gyms")) {
                JsonObject gym = element.getAsJsonObject();
                String gymId = gym.get("id").getAsString();
                String track = optionalString(gym, "music_track");
                if (track != null) gymMusic.put(gymId, track);
                JsonObject leader = gym.getAsJsonObject("staff").getAsJsonObject("leader");
                gymByTrainer.put(leader.get("trainer_id").getAsString(), gymId);
            }
            return new MusicData(
                namespace, defaults, soundEvents, tileMusic, coordinateOverrides,
                routeMusic, worldSettlementMusic, settlementMusic, caveMusic,
                forestMusic, battles,
                gymByTrainer, gymMusic
            );
        }

        private static MusicData empty() {
            return new MusicData(
                CONTENT_NAMESPACE,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()
            );
        }

        private static Map<String, String> musicByDocument(
            ResourceManager resources, String directory
        ) {
            Map<String, String> result = new HashMap<>();
            resources.listResources(
                directory, location -> location.getPath().endsWith(".json")
            ).forEach((location, resource) -> {
                JsonObject document = readResource(location, resource);
                String track = optionalString(document, "music_track");
                if (track != null) result.put(document.get("id").getAsString(), track);
            });
            return result;
        }

        private static JsonObject readResource(ResourceLocation location, Resource resource) {
            try (Reader reader = resource.openAsReader()) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Invalid packaged music resource: " + location, error);
            }
        }

        private static Map<Cell, String> musicByCell(JsonObject root, String key) {
            Map<Cell, String> result = new HashMap<>();
            if (!root.has(key)) return result;
            for (JsonElement element : root.getAsJsonArray(key)) {
                JsonObject value = element.getAsJsonObject();
                String track = optionalString(value, "music_track");
                if (track != null) {
                    result.put(new Cell(value.get("q").getAsInt(), value.get("r").getAsInt()), track);
                }
            }
            return result;
        }

        private static Map<String, String> musicById(JsonObject root, String key, String idKey) {
            Map<String, String> result = new HashMap<>();
            if (!root.has(key)) return result;
            for (JsonElement element : root.getAsJsonArray(key)) {
                JsonObject value = element.getAsJsonObject();
                String track = optionalString(value, "music_track");
                if (track != null) result.put(value.get(idKey).getAsString(), track);
            }
            return result;
        }

        private String resolveLocation(Cell cell, String areaKind, String areaOwner) {
            String override = coordinateOverrides.get(cell);
            if (override != null) return override;
            if ("town".equals(areaKind)) {
                return first(
                    worldSettlementMusic.get(areaOwner),
                    settlementMusic.get(areaOwner),
                    defaults.get("settlement")
                );
            }
            if ("route".equals(areaKind)) {
                return first(routeMusic.get(areaOwner), defaults.get("road"));
            }
            return first(tileMusic.get(cell), defaults.get("tile"));
        }

        private String resolveDimension(String areaKind, String areaOwner) {
            if ("cave".equals(areaKind)) {
                return first(caveMusic.get(areaOwner), defaults.get("cave"), defaults.get("tile"));
            }
            if ("forest".equals(areaKind)) {
                return first(forestMusic.get(areaOwner), defaults.get("forest"), defaults.get("tile"));
            }
            return defaults.get("tile");
        }

        private String resolveBattle(String battleId) {
            JsonObject preset = battles.get(battleId);
            if (preset == null) return defaults.get("battle");
            JsonObject battle = preset.getAsJsonObject("battle");
            String direct = optionalString(battle, "music_track");
            if (direct != null) return direct;
            String trainer = optionalString(battle, "trainer_id");
            String gym = trainer == null ? null : gymByTrainer.get(trainer);
            if (gym != null) return first(gymMusic.get(gym), defaults.get("gym"));
            return defaults.get("battle");
        }

        private String resolveVictory(String battleId) {
            JsonObject preset = battles.get(battleId);
            if (preset == null) return defaults.get("victory_trainer");
            JsonObject battle = preset.getAsJsonObject("battle");
            String trainer = optionalString(battle, "trainer_id");
            String gym = trainer == null ? null : gymByTrainer.get(trainer);
            return defaults.get(gym == null ? "victory_trainer" : "victory_gym");
        }

        private static String first(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value;
            }
            return null;
        }
    }
}
