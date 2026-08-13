package dev.buizz.cobbleventure.adventure;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** World-owned queries consumed by the platform-neutral adventure rules. */
public interface AdventureWorldContext {
    Integer averageWildSpawnLevel(ServerLevel level, double x, double z);

    String authoredWeatherAt(ServerPlayer player);
}
