package dev.buizz.cobbleventure.playermenu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Mirrors shared player conditions into boolean objectives understood by Easy NPC. */
final class BagConditionTracker {
    private static final ResourceLocation CONDITIONS = ResourceLocation.fromNamespaceAndPath(
        CobbleventurePlayerMenu.MOD_ID, "bag/bag-conditions.json"
    );
    private static MinecraftServer loadedServer;
    private static List<Condition> conditions = List.of();

    private BagConditionTracker() {}

    static void sync(ServerPlayer player) {
        List<Condition> active = load(player.getServer());
        if (active.isEmpty()) return;
        for (Condition condition : active) {
            int value = condition.condition().matches(player) ? 1 : 0;
            Objective objective = player.getScoreboard().getObjective(condition.objective());
            if (objective == null) {
                objective = player.getScoreboard().addObjective(
                    condition.objective(), ObjectiveCriteria.DUMMY,
                    Component.literal(condition.objective()),
                    ObjectiveCriteria.RenderType.INTEGER, false, null
                );
            }
            var score = player.getScoreboard().getOrCreatePlayerScore(player, objective);
            if (score.get() != value) score.set(value);
        }
    }

    private static List<Condition> load(MinecraftServer server) {
        if (server == loadedServer) return conditions;
        loadedServer = server;
        List<Condition> loaded = new ArrayList<>();
        try {
            var resource = server.getResourceManager().getResource(CONDITIONS);
            if (resource.isEmpty()) return conditions = List.of();
            try (Reader reader = resource.get().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (var entry : root.getAsJsonObject("conditions").entrySet()) {
                    JsonObject value = entry.getValue().getAsJsonObject();
                    if (entry.getKey().length() > 16) continue;
                    loaded.add(new Condition(
                        entry.getKey(), PlayerConditions.parse(value)
                    ));
                }
            }
        } catch (Exception ignored) {
            loaded.clear();
        }
        return conditions = List.copyOf(loaded);
    }

    private record Condition(
        String objective, PlayerConditions.Condition condition
    ) {}
}
