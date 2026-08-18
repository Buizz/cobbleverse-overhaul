package dev.buizz.cobbleventure.bootstrap.client;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/** Client-side view of blocker NPCs hidden for the local player. */
public final class GymBlockerVisibility {
    private static volatile Set<UUID> hiddenBlockers = Set.of();

    private GymBlockerVisibility() {}

    public static void replaceHidden(Collection<UUID> hidden) {
        hiddenBlockers = Set.copyOf(hidden);
    }

    public static boolean isHidden(Entity entity) {
        return hiddenBlockers.contains(entity.getUUID());
    }
}
