package dev.buizz.cobbleventure.pokefinder.client;

import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

/** Persistent client-only visibility settings for Cobbleventure radar markers. */
public final class RadarDisplaySettings {
    private static final Path FILE = configFile();
    private static final EnumMap<Option, Boolean> VALUES = load(FILE);

    private RadarDisplaySettings() {}

    public static synchronized boolean value(Option option) {
        return VALUES.get(option);
    }

    public static synchronized boolean toggle(Option option) {
        boolean next = !VALUES.get(option);
        VALUES.put(option, next);
        save(FILE, VALUES);
        return next;
    }

    public static boolean visible(RadarMarker marker) {
        Option category = category(marker.type(), marker.id().getPath());
        if (category != null && !value(category)) return false;
        return value(Option.DEFEATED_TRAINERS)
            || marker.state() != RadarMarkerState.DEFEATED
            || (marker.type() != RadarMarkerType.TRAINER
                && marker.type() != RadarMarkerType.GYM_LEADER);
    }

    /** Major travel facilities keep their labels visible even when generic names are hidden. */
    static boolean isLandmark(RadarMarker marker) {
        return marker.type() == RadarMarkerType.POKEMON_CENTER
            || marker.type() == RadarMarkerType.POKEMART
            || (marker.type() == RadarMarkerType.GYM_LEADER
                && !marker.id().getPath().startsWith("npc/"));
    }

    static Option category(RadarMarkerType type, String idPath) {
        return switch (type) {
            case PLAYER -> Option.PLAYERS;
            case TRAINER -> Option.TRAINERS;
            case GYM_LEADER -> idPath.startsWith("npc/")
                ? Option.TRAINERS : Option.FACILITIES;
            case IMPORTANT_NPC -> Option.IMPORTANT_NPCS;
            case POKEMON_CENTER, POKEMART, CASINO, SPECIAL_BUILDING ->
                Option.FACILITIES;
            case CAVE_ENTRANCE, FOREST_ENTRANCE, GATE -> Option.ENTRANCES;
            case OBJECTIVE -> Option.OBJECTIVES;
        };
    }

    static EnumMap<Option, Boolean> decode(Properties properties) {
        EnumMap<Option, Boolean> values = defaults();
        for (Option option : Option.values()) {
            String raw = properties.getProperty(option.key);
            if (raw != null) values.put(option, Boolean.parseBoolean(raw));
        }
        return values;
    }

    private static EnumMap<Option, Boolean> defaults() {
        EnumMap<Option, Boolean> values = new EnumMap<>(Option.class);
        for (Option option : Option.values()) values.put(option, option.defaultValue);
        return values;
    }

    private static EnumMap<Option, Boolean> load(Path path) {
        if (path == null || !Files.isRegularFile(path)) return defaults();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            return decode(properties);
        } catch (IOException error) {
            return defaults();
        }
    }

    private static void save(Path path, EnumMap<Option, Boolean> values) {
        if (path == null) return;
        Properties properties = new Properties();
        values.forEach((option, enabled) ->
            properties.setProperty(option.key, Boolean.toString(enabled))
        );
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Cobbleventure Pokefinder exploration settings");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // A read-only config directory keeps the in-memory selection for this session.
        }
    }

    private static Path configFile() {
        try {
            Path directory = FMLPaths.CONFIGDIR.get();
            return directory == null ? null
                : directory.resolve("cobbleventure-pokefinder.properties");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public enum Option {
        PLAYERS("show_players", true, "다른 플레이어"),
        TRAINERS("show_trainers", true, "트레이너"),
        IMPORTANT_NPCS("show_important_npcs", true, "중요 NPC"),
        FACILITIES("show_facilities", true, "시설"),
        ENTRANCES("show_entrances", true, "입구와 관문"),
        OBJECTIVES("show_objectives", true, "현재 목표"),
        NAMES("show_names", false, "이름 표시"),
        DISTANCES("show_distances", false, "거리 표시"),
        DEFEATED_TRAINERS("show_defeated_trainers", true, "승리한 트레이너");

        private final String key;
        private final boolean defaultValue;
        private final String label;

        Option(String key, boolean defaultValue, String label) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.label = label;
        }

        public String label() {
            return label;
        }

        String key() {
            return key.toLowerCase(Locale.ROOT);
        }
    }
}
