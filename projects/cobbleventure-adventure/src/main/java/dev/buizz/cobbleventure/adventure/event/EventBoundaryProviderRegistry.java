package dev.buizz.cobbleventure.adventure.event;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Optional world-mod boundary index consumed by representation-neutral CVES triggers. */
public final class EventBoundaryProviderRegistry {
    @FunctionalInterface
    public interface Provider {
        Snapshot snapshot(ServerPlayer player);
    }

    public record Snapshot(
        Set<String> regions,
        Set<String> anchors,
        Set<String> buildings,
        Set<String> dimensions
    ) {
        public Snapshot {
            regions = validatedCopy(regions, "region");
            anchors = validatedCopy(anchors, "anchor");
            buildings = validatedCopy(buildings, "building");
            dimensions = validatedCopy(dimensions, "dimension");
        }

        private static Set<String> validatedCopy(Set<String> values, String kind) {
            Objects.requireNonNull(values, kind + "s");
            for (String value : values) {
                if (ResourceLocation.tryParse(value) == null) {
                    throw new IllegalArgumentException(
                        kind + " boundary는 리소스 ID여야 합니다: " + value
                    );
                }
            }
            return Set.copyOf(values);
        }
    }

    private static volatile Provider provider;

    private EventBoundaryProviderRegistry() {}

    public static synchronized void register(Provider value) {
        Objects.requireNonNull(value, "provider");
        if (provider != null) {
            throw new IllegalStateException("event boundary provider가 이미 등록됐습니다.");
        }
        provider = value;
    }

    public static Optional<Snapshot> snapshot(ServerPlayer player) {
        Provider current = provider;
        if (current == null) return Optional.empty();
        return Optional.of(Objects.requireNonNull(
            current.snapshot(Objects.requireNonNull(player, "player")),
            "event boundary snapshot"
        ));
    }

    static synchronized void clearForTests() {
        provider = null;
    }
}
