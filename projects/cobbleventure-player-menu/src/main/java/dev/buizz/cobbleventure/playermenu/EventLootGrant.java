package dev.buizz.cobbleventure.playermenu;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Idempotent, all-or-none loot-table reward command used by the CVES runtime. */
public final class EventLootGrant {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<CallbackKey, PendingCallback> PENDING = new HashMap<>();

    private EventLootGrant() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventLootGrant::registerCommands);
        NeoForge.EVENT_BUS.addListener(EventLootGrant::onServerTick);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_loot_grant_session")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("token", StringArgumentType.word())
                        .then(Commands.argument("operation", StringArgumentType.string())
                            .then(Commands.argument("loot_table", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1024))
                                    .then(Commands.argument("notify", BoolArgumentType.bool())
                                        .executes(context -> grant(
                                            EntityArgument.getPlayer(context, "player"),
                                            StringArgumentType.getString(context, "token"),
                                            StringArgumentType.getString(context, "operation"),
                                            StringArgumentType.getString(context, "loot_table"),
                                            IntegerArgumentType.getInteger(context, "count"),
                                            BoolArgumentType.getBool(context, "notify")
                                        ))))))))
        );
    }

    private static int grant(
        ServerPlayer player,
        String token,
        String operationId,
        String lootTableIdValue,
        int rollCount,
        boolean notify
    ) {
        ResourceLocation lootTableId = ResourceLocation.tryParse(lootTableIdValue);
        if (lootTableId == null) {
            LOGGER.warn("CVES loot reward uses an invalid loot table: {}", lootTableIdValue);
            queue(player, token, rollCount, 0, rollCount, 1, "invalid_resource_id");
            return 0;
        }

        BagStorage.EventRewardResult result;
        try {
            result = BagStorage.grantEventLootReward(
                player,
                operationId,
                lootTableId.toString(),
                rollCount,
                () -> generate(player, lootTableId, rollCount)
            );
        } catch (MissingLootTableException error) {
            LOGGER.error("CVES loot table does not exist: {}", lootTableId);
            queue(player, token, rollCount, 0, rollCount, 1, "loot_table_not_found");
            return 0;
        } catch (RuntimeException error) {
            LOGGER.error("CVES loot table generation failed: {}", lootTableId, error);
            queue(player, token, rollCount, 0, rollCount, 1, "loot_generation_failed");
            return 0;
        }
        if (result.status() == BagStorage.EventRewardStatus.CONFLICT) {
            LOGGER.error(
                "CVES loot operation was reused with a different payload: player={}, operation={}",
                player.getGameProfile().getName(), operationId
            );
        }
        boolean newlyGranted = result.status() == BagStorage.EventRewardStatus.GRANTED;
        boolean notified = newlyGranted && notify && result.granted() > 0;
        if (notified) {
            ItemAcquisition.showLoot(player, result.granted());
        }
        queue(
            player,
            token,
            result.requested(),
            result.granted(),
            result.remaining(),
            notified ? ItemAcquisition.NOTICE_DURATION_TICKS : 1,
            switch (result.status()) {
                case FULL -> "bag_full";
                case CONFLICT -> "operation_conflict";
                default -> null;
            }
        );
        return result.remaining() == 0 ? 1 : 0;
    }

    private static List<ItemStack> generate(
        ServerPlayer player, ResourceLocation lootTableId, int rollCount
    ) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, lootTableId);
        LootTable lootTable = player.getServer().reloadableRegistries().getLootTable(key);
        if (lootTable == LootTable.EMPTY) throw new MissingLootTableException();
        LootParams params = new LootParams.Builder(player.serverLevel())
            .withParameter(LootContextParams.ORIGIN, player.position())
            .withParameter(LootContextParams.THIS_ENTITY, player)
            .create(LootContextParamSets.GIFT);
        List<ItemStack> generated = new ArrayList<>();
        for (int roll = 0; roll < rollCount; roll++) {
            generated.addAll(lootTable.getRandomItems(params));
        }
        return generated;
    }

    private static void queue(
        ServerPlayer player,
        String token,
        int requested,
        int granted,
        int remaining,
        int delayTicks,
        String failureReason
    ) {
        PENDING.put(
            new CallbackKey(player.getUUID(), token),
            new PendingCallback(
                player.getServer().getTickCount() + delayTicks,
                requested,
                granted,
                remaining,
                failureReason
            )
        );
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        List<CallbackKey> ready = PENDING.entrySet().stream()
            .filter(entry -> entry.getValue().atTick() <= tick)
            .map(Map.Entry::getKey)
            .toList();
        for (CallbackKey key : ready) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(key.playerId());
            if (player == null) continue;
            PendingCallback pending = PENDING.remove(key);
            String command = "cobbleventure_event item_result " + player.getUUID() + " "
                + key.token() + " " + pending.requested() + " " + pending.granted()
                + " " + pending.remaining();
            if (pending.failureReason() != null) {
                command += " " + StringArgumentType.escapeIfRequired(pending.failureReason());
            }
            event.getServer().getCommands().performPrefixedCommand(
                event.getServer().createCommandSourceStack()
                    .withPermission(4)
                    .withSuppressedOutput(),
                command
            );
        }
    }

    private record CallbackKey(UUID playerId, String token) {}
    private record PendingCallback(
        int atTick, int requested, int granted, int remaining, String failureReason
    ) {}
    private static final class MissingLootTableException extends RuntimeException {}
}
