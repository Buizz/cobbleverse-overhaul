package dev.buizz.cobbleventure.pokefinder.server;

import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.pokefinder.CobbleventurePokefinder;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarker;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerState;
import dev.buizz.cobbleventure.pokefinder.marker.RadarMarkerType;
import dev.buizz.cobbleventure.pokefinder.marker.RadarRanges;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Server-safe compatibility boundary for the world-bootstrap location API. */
public final class WorldBootstrapRadarProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CATALOG =
        "dev.buizz.cobbleventure.bootstrap.RadarLocationCatalog";
    private static volatile boolean failureLogged;

    private WorldBootstrapRadarProvider() {}

    public static List<RadarMarker> markers(ServerPlayer player) {
        try {
            Class<?> catalog = Class.forName(CATALOG);
            Method locations = catalog.getMethod("locations", ServerPlayer.class);
            Method npcLocations = catalog.getMethod("npcLocations", ServerPlayer.class);
            Method objectiveLocations = catalog.getMethod(
                "objectiveLocations", ServerPlayer.class
            );
            List<RadarMarker> result = new ArrayList<>();
            append(result, locations.invoke(null, player), player);
            append(result, npcLocations.invoke(null, player), player);
            append(result, objectiveLocations.invoke(null, player), player);
            return List.copyOf(result);
        } catch (ClassNotFoundException | NoSuchMethodException
                 | IllegalAccessException | InvocationTargetException error) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.error("World-bootstrap radar location API is unavailable", error);
            }
            return List.of();
        }
    }

    static RadarMarkerType markerType(String kind) {
        return switch (kind) {
            case "GYM" -> RadarMarkerType.GYM_LEADER;
            case "TRAINER" -> RadarMarkerType.TRAINER;
            case "GYM_LEADER" -> RadarMarkerType.GYM_LEADER;
            case "IMPORTANT_NPC" -> RadarMarkerType.IMPORTANT_NPC;
            case "OBJECTIVE" -> RadarMarkerType.OBJECTIVE;
            case "POKEMON_CENTER" -> RadarMarkerType.POKEMON_CENTER;
            case "POKEMART" -> RadarMarkerType.POKEMART;
            case "CASINO" -> RadarMarkerType.CASINO;
            case "CAVE_ENTRANCE" -> RadarMarkerType.CAVE_ENTRANCE;
            case "FOREST_ENTRANCE" -> RadarMarkerType.FOREST_ENTRANCE;
            case "GATE" -> RadarMarkerType.GATE;
            default -> RadarMarkerType.SPECIAL_BUILDING;
        };
    }

    static String safePath(String id) {
        String normalized = id.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9/._-]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    static RadarMarkerState markerState(String state) {
        try {
            return RadarMarkerState.valueOf(state);
        } catch (IllegalArgumentException error) {
            return RadarMarkerState.AVAILABLE;
        }
    }

    private static void append(
        List<RadarMarker> result, Object value, ServerPlayer player
    ) {
        if (!(value instanceof List<?> entries)) return;
        for (Object entry : entries) {
            RadarMarker marker = marker(entry);
            if (marker != null
                && (marker.type() == RadarMarkerType.OBJECTIVE
                    || marker.position().distanceTo(player.position())
                        <= RadarRanges.MAX_FALLBACK)) {
                result.add(marker);
            }
        }
    }

    private static RadarMarker marker(Object entry) {
        try {
            Class<?> type = entry.getClass();
            String rawId = (String) type.getMethod("id").invoke(entry);
            RadarMarkerType markerType = markerType(
                type.getMethod("kind").invoke(entry).toString()
            );
            ResourceLocation dimension = (ResourceLocation)
                type.getMethod("dimension").invoke(entry);
            double x = (double) type.getMethod("x").invoke(entry);
            double y = (double) type.getMethod("y").invoke(entry);
            double z = (double) type.getMethod("z").invoke(entry);
            String label = (String) type.getMethod("label").invoke(entry);
            String areaId = (String) type.getMethod("areaId").invoke(entry);
            RadarMarkerState state = RadarMarkerState.AVAILABLE;
            boolean playerSpecific = false;
            try {
                state = markerState((String) type.getMethod("state").invoke(entry));
                playerSpecific = true;
            } catch (NoSuchMethodException ignored) {
                // Static location records predate player-specific marker state.
            }
            return new RadarMarker(
                ResourceLocation.fromNamespaceAndPath(
                    CobbleventurePokefinder.MOD_ID, safePath(rawId)
                ),
                markerType,
                dimension,
                new Vec3(x, y, z),
                label,
                ResourceLocation.fromNamespaceAndPath(
                    CobbleventurePokefinder.MOD_ID,
                    "radar/" + markerType.name().toLowerCase(Locale.ROOT)
                ),
                priority(markerType),
                state,
                areaId,
                RadarRanges.DEFAULT_LOCAL,
                markerType == RadarMarkerType.OBJECTIVE || !playerSpecific
            );
        } catch (ReflectiveOperationException | ClassCastException error) {
            return null;
        }
    }

    private static int priority(RadarMarkerType type) {
        return switch (type) {
            case GYM_LEADER -> 500;
            case OBJECTIVE -> 600;
            case TRAINER, IMPORTANT_NPC -> 400;
            case POKEMON_CENTER, POKEMART, CASINO, SPECIAL_BUILDING -> 300;
            case CAVE_ENTRANCE, FOREST_ENTRANCE, GATE -> 200;
            default -> 100;
        };
    }
}
