package dev.buizz.cobbleventure.adventure.event;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/** Provider boundary for world mods that own translated resource locations. */
public final class EventLocationResolverRegistry {
    @FunctionalInterface
    public interface Resolver {
        Resolution resolve(MinecraftServer server, EventLocationRef.Resource destination);
    }

    public record ResolvedLocation(
        String dimension, double x, double y, double z, Float yaw, Float pitch
    ) {
        public ResolvedLocation {
            // Reuse the closed position contract for resource and finite validation.
            new EventLocationRef.Position(dimension, x, y, z, yaw, pitch);
        }

        public EventLocationRef.Position toPosition() {
            return new EventLocationRef.Position(dimension, x, y, z, yaw, pitch);
        }
    }

    public record Resolution(ResolvedLocation location, String failureReason) {
        public Resolution {
            if ((location == null) == (failureReason == null)) {
                throw new IllegalArgumentException(
                    "location 또는 failureReason 중 하나만 필요합니다."
                );
            }
            if (failureReason != null && !failureReason.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException(
                    "failureReason은 snake_case 코드여야 합니다: " + failureReason
                );
            }
        }

        public static Resolution resolved(ResolvedLocation location) {
            return new Resolution(Objects.requireNonNull(location, "location"), null);
        }

        public static Resolution failed(String failureReason) {
            return new Resolution(null, failureReason);
        }

        public boolean isResolved() { return location != null; }
    }

    private static volatile Map<EventLocationRef.Resource.Kind, Resolver> resolvers = Map.of();

    private EventLocationResolverRegistry() {}

    public static synchronized void register(
        EventLocationRef.Resource.Kind kind, Resolver resolver
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resolver, "resolver");
        if (resolvers.containsKey(kind)) {
            throw new IllegalStateException("location resolver가 이미 등록됐습니다: " + kind);
        }
        EnumMap<EventLocationRef.Resource.Kind, Resolver> updated =
            new EnumMap<>(EventLocationRef.Resource.Kind.class);
        updated.putAll(resolvers);
        updated.put(kind, resolver);
        resolvers = Map.copyOf(updated);
    }

    public static Resolution resolve(
        MinecraftServer server, EventLocationRef.Resource destination
    ) {
        Objects.requireNonNull(destination, "destination");
        Resolver resolver = resolvers.get(destination.kind());
        if (resolver == null) {
            return Resolution.failed(EventMovementFailureReason.LOCATION_PROVIDER_UNAVAILABLE);
        }
        Resolution result = resolver.resolve(server, destination);
        return Objects.requireNonNull(result, "location resolver 결과");
    }

    static synchronized void clearForTests() {
        resolvers = Map.of();
    }
}
