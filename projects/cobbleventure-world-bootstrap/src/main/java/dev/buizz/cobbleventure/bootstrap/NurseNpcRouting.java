package dev.buizz.cobbleventure.bootstrap;

import java.util.Objects;
import java.util.Set;

/** Keeps legacy nurse compatibility from intercepting a V5-bound representation. */
final class NurseNpcRouting {
    private static final String NURSE_TAG =
        "cobbleventure_npc/cobbleventure/npc/pokemon_center_nurse";

    private NurseNpcRouting() {}

    static boolean usesLegacyFallback(Set<String> tags) {
        Objects.requireNonNull(tags, "tags");
        return tags.contains(NURSE_TAG)
            && tags.stream().noneMatch(tag -> tag.startsWith("cves_binding/"));
    }
}
