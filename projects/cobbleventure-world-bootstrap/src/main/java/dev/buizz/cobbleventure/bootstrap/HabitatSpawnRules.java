package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.playermenu.MapContent;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Keeps runtime wild spawns and spawn-list integrations on the authored map pool. */
public final class HabitatSpawnRules {
    private HabitatSpawnRules() {}

    public static Set<ResourceLocation> allowedSpecies(
        ServerLevel level, double x, double z
    ) {
        String dimension = level.dimension().location().toString();
        for (MapContent content : MapContent.all()) {
            if (!dimension.equals(content.dimension())) {
                continue;
            }
            MapContent.Hex hex = content.worldToHex(x, z);
            MapContent.BiomeTile tile = content.tileAt(hex.q(), hex.r());
            if (tile == null) {
                return Set.of();
            }
            LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
            for (MapContent.Pokemon pokemon : content.biome(tile).pokemon()) {
                ResourceLocation id = ResourceLocation.tryParse(pokemon.id());
                if (id != null) {
                    result.add(id);
                }
            }
            return Set.copyOf(result);
        }
        return null;
    }

    public static boolean allowsSpawnDetail(
        Set<ResourceLocation> allowedSpecies, String spawnDetailId
    ) {
        if (allowedSpecies == null) {
            return true;
        }
        String path = spawnDetailId;
        int namespaceSeparator = path.indexOf(':');
        if (namespaceSeparator >= 0) {
            path = path.substring(namespaceSeparator + 1);
        }
        for (ResourceLocation species : allowedSpecies) {
            String speciesPath = species.getPath();
            if (path.equals(speciesPath)
                || path.startsWith(speciesPath + "-")
                || path.startsWith(speciesPath + "_")) {
                return true;
            }
        }
        return false;
    }
}
