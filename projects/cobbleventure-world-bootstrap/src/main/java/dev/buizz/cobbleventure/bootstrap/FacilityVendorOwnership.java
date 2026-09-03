package dev.buizz.cobbleventure.bootstrap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Session-local authority for facility vendors, including entities loaded after placement. */
final class FacilityVendorOwnership {
    private final Map<BlockPos, Scope> scopes = new LinkedHashMap<>();

    void begin(BlockPos origin, AABB bounds) {
        scopes.put(origin.immutable(), new Scope(bounds, new HashMap<>()));
    }

    void recordSpawn(BlockPos position, UUID entityId) {
        Vec3 center = Vec3.atBottomCenterOf(position);
        for (Scope scope : scopes.values()) {
            if (scope.bounds.contains(center)) {
                // Repeated placement at a slot replaces its previous owner, not adds to it.
                scope.owners.put(position.immutable(), entityId);
            }
        }
    }

    List<AABB> activeBounds() {
        return scopes.values().stream().filter(scope -> !scope.owners.isEmpty())
            .map(Scope::bounds).toList();
    }

    boolean isObsolete(Vec3 position, UUID entityId) {
        // Protect a valid owner even when cleanup margins of two facilities overlap.
        if (scopes.values().stream().anyMatch(scope -> scope.owners.containsValue(entityId))) {
            return false;
        }
        return scopes.values().stream().anyMatch(scope ->
            !scope.owners.isEmpty() && scope.bounds.contains(position)
        );
    }

    private record Scope(AABB bounds, Map<BlockPos, UUID> owners) {}
}
