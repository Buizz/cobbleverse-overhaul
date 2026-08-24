package dev.buizz.cobbleventure.casino;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class CasinoCashier {
    public static final String CASHIER_TAG = "cobbleventure_npc/cobbleventure/npc/casino_cashier";
    private static final long SESSION_TICKS = 20L * 60L * 5L;
    private static final double MAX_DISTANCE_SQUARED = 64.0D;
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private CasinoCashier() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CasinoCashier::registerCommands);
        NeoForge.EVENT_BUS.addListener(CasinoCashier::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(CasinoCashier::onChipTableInteract);
        NeoForge.EVENT_BUS.addListener(CasinoCashier::onLegacyWalletUse);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cvcasino")
            .then(Commands.literal("exchange")
                .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                    .executes(CasinoCashier::exchangeAmount)))
            );
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !event.getTarget().getTags().contains(CASHIER_TAG)) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        SESSIONS.put(player.getUUID(), new Session(
            event.getTarget().getUUID(), player.serverLevel().getGameTime() + SESSION_TICKS));
    }

    private static void onChipTableInteract(PlayerInteractEvent.RightClickBlock event) {
        ResourceLocation id = event.getLevel().registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
            .getKey(event.getLevel().getBlockState(event.getPos()).getBlock());
        if (id == null || !"cobblemoncasino".equals(id.getNamespace())
            || !"chip_table".equals(id.getPath())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof ServerPlayer player) player.displayClientMessage(
            Component.translatable("message.cobbleventure_casino.cashier.use_npc"), true);
    }

    private static void onLegacyWalletUse(PlayerInteractEvent.RightClickItem event) {
        ResourceLocation id = event.getEntity().registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
            .getKey(event.getItemStack().getItem());
        if (id == null || !"cobblemoncasino".equals(id.getNamespace())
            || !"wallet".equals(id.getPath())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof ServerPlayer player) player.displayClientMessage(
            Component.translatable("message.cobbleventure_casino.cashier.wallet_disabled"), true);
    }

    private static int exchangeAmount(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try { player = context.getSource().getPlayerOrException(); }
        catch (Exception ignored) { return 0; }
        return exchange(player, LongArgumentType.getLong(context, "amount"));
    }

    private static int exchange(ServerPlayer player, long amount) {
        Entity cashier = validCashier(player);
        if (cashier == null) {
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_casino.cashier.session_expired"), true);
            return 0;
        }
        if (!hasCoinCase(player)) {
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_casino.cashier.coin_case_required"), true);
            return 0;
        }
        if (amount <= 0L) return 0;
        BigInteger requested = BigInteger.valueOf(amount);
        BigInteger before = PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO);
        if (before.compareTo(requested) < 0) {
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_casino.cashier.insufficient"), true);
            return 0;
        }
        var balances = net.narrnouille.cobblemoncasino.data.PlayerCasinoBalanceData.get(player.getServer());
        long casinoBefore = balances.getBalance(player.getUUID());
        if (Long.MAX_VALUE - casinoBefore < amount) {
            player.displayClientMessage(Component.translatable(
                "message.cobbleventure_casino.cashier.balance_limit"), true);
            return 0;
        }
        PlayerExtensionKt.setCobbleDollars(player, before.subtract(requested));
        balances.setBalance(player.getUUID(), casinoBefore + amount);
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_casino.cashier.success", amount, casinoBefore + amount), true);
        return 1;
    }

    private static Entity validCashier(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || player.serverLevel().getGameTime() > session.expiresAt()) {
            SESSIONS.remove(player.getUUID());
            return null;
        }
        Entity cashier = player.serverLevel().getEntity(session.cashier());
        if (cashier == null || !cashier.isAlive() || !cashier.getTags().contains(CASHIER_TAG)
            || player.distanceToSqr(cashier) > MAX_DISTANCE_SQUARED) {
            SESSIONS.remove(player.getUUID());
            return null;
        }
        return cashier;
    }

    public static boolean hasCoinCase(ServerPlayer player) {
        if (player.getInventory().contains(new ItemStack(CasinoItems.COIN_CASE.get()))) return true;
        try {
            Class<?> storage = Class.forName("dev.buizz.cobbleventure.playermenu.BagStorage");
            Method load = storage.getMethod("load", ServerPlayer.class);
            Object value = load.invoke(null, player);
            if (value instanceof Iterable<?> stacks) for (Object entry : stacks) {
                if (entry instanceof ItemStack stack && stack.is(CasinoItems.COIN_CASE.get())) return true;
            }
        } catch (ReflectiveOperationException ignored) {}
        return false;
    }

    public static void showDepositDisabled(ServerPlayer player) {
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_casino.cashier.deposit_at_npc"), true);
    }
    public static void showWithdrawalDisabled(ServerPlayer player) {
        player.displayClientMessage(Component.translatable(
            "message.cobbleventure_casino.cashier.withdraw_disabled"), true);
    }
    private record Session(UUID cashier, long expiresAt) {}
}
