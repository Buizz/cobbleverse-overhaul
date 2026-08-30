package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.resources.ResourceLocation;

/** Separates authored V5 building IDs from internal per-instance registry keys. */
final class BuildingEventSpaceIds {
    private BuildingEventSpaceIds() {}

    static boolean isPublic(String value) {
        return ResourceLocation.tryParse(value) != null;
    }
}
