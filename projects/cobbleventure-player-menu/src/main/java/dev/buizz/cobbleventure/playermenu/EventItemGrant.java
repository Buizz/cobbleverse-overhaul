package dev.buizz.cobbleventure.playermenu;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/** Idempotent item-reward command used by the CVES runtime adapter. */
public final class EventItemGrant {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<CallbackKey, PendingCallback> PENDING = new HashMap<>();

    private EventItemGrant() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventItemGrant::registerCommands);
        NeoForge.EVENT_BUS.addListener(EventItemGrant::onServerTick);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_item_grant_session")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("token", StringArgumentType.word())
                        .then(Commands.argument("operation", StringArgumentType.string())
                            .then(Commands.argument("item", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 262_144))
                                    .then(Commands.argument("notify", BoolArgumentType.bool())
                                        .executes(context -> grant(
                                            EntityArgument.getPlayer(context, "player"),
                                            StringArgumentType.getString(context, "token"),
                                            StringArgumentType.getString(context, "operation"),
                                            StringArgumentType.getString(context, "item"),
                                            IntegerArgumentType.getInteger(context, "count"),
                                            BoolArgumentType.getBool(context, "notify")
                                        ))))))))
        );
    }

    private static int grant(
        ServerPlayer player,
        String token,
        String operationId,
        String itemIdValue,
        int count,
        boolean notify
    ) {
        ResourceLocation itemId = ResourceLocation.tryParse(itemIdValue);
        Item item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) {
            LOGGER.warn("CVES item reward uses an unknown item: {}", itemIdValue);
            queue(player, token, count, 0, count, 1);
            return 0;
        }

        ItemStack prototype = new ItemStack(item);
        BagStorage.EventRewardResult result = BagStorage.grantEventReward(
            player, operationId, prototype, count
        );
        if (result.status() == BagStorage.EventRewardStatus.CONFLICT) {
            LOGGER.error(
                "CVES reward operation was reused with a different payload: player={}, operation={}",
                player.getGameProfile().getName(), operationId
            );
        }
        boolean newlyGranted = result.status() == BagStorage.EventRewardStatus.GRANTED;
        if (newlyGranted && notify) {
            ItemAcquisition.show(player, prototype, result.granted());
        }
        queue(
            player,
            token,
            result.requested(),
            result.granted(),
            result.remaining(),
            newlyGranted && notify ? ItemAcquisition.NOTICE_DURATION_TICKS : 1
        );
        return result.remaining() == 0 ? 1 : 0;
    }

    private static void queue(
        ServerPlayer player,
        String token,
        int requested,
        int granted,
        int remaining,
        int delayTicks
    ) {
        CallbackKey key = new CallbackKey(player.getUUID(), token);
        PENDING.put(key, new PendingCallback(
            player.getServer().getTickCount() + delayTicks,
            requested,
            granted,
            remaining
        ));
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
            event.getServer().getCommands().performPrefixedCommand(
                event.getServer().createCommandSourceStack()
                    .withPermission(4)
                    .withSuppressedOutput(),
                command
            );
        }
    }

    private record CallbackKey(UUID playerId, String token) {}
    private record PendingCallback(int atTick, int requested, int granted, int remaining) {}
}
