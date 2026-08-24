package dev.buizz.cobbleventure.playermenu;

import java.util.Objects;
import java.util.Set;

/** Keeps the retired EasyNPC proximity flow away from CVES-owned NPCs. */
final class LegacyProximityRouting {
    private LegacyProximityRouting() {}

    static boolean accepts(Set<String> entityTags) {
        Objects.requireNonNull(entityTags, "entityTags");
        return entityTags.stream().noneMatch(tag -> tag.startsWith("cves_binding/"));
    }
}
