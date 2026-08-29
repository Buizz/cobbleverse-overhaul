package dev.buizz.cobbleventure.adventure.daycare;

import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Server-authoritative progression gate for player-operated Cobbreeding pastures. */
public final class FarmBreedingGate {
    public static final int REQUIRED_BADGES = 4;
    private static final String BADGE_DATA_KEY = "cobbleventureBadges";
    private static final ResourceLocation FARM_PLOTS =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "farm_plots");

    private FarmBreedingGate() {}

    public static Denial denial(ServerPlayer player) {
        int badges = player.getPersistentData().getList(BADGE_DATA_KEY, Tag.TAG_STRING).size();
        return denial(badges, player.level().dimension().location());
    }

    static Denial denial(int badges, ResourceLocation dimension) {
        if (badges < REQUIRED_BADGES) {
            return Denial.BADGES;
        }
        if (!dimension.equals(FARM_PLOTS)) {
            return Denial.FARM_ONLY;
        }
        return Denial.NONE;
    }

    public enum Denial {
        NONE,
        BADGES,
        FARM_ONLY
    }
}
