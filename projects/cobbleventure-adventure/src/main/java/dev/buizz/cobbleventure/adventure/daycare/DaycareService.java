package dev.buizz.cobbleventure.adventure.daycare;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Paid, one-egg daycare lifecycle. Commands provide the first integration test surface. */
public final class DaycareService {
    private static final long SERVICE_FEE = 3_000L;
    private static final int MIN_BREEDING_TICKS = 8_000;
    private static final int MAX_BREEDING_TICKS = 14_000;
    private static final long MILLIS_PER_TICK = 50L;

    private DaycareService() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(DaycareService::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cobbleventure_daycare")
                .then(Commands.literal("deposit")
                    .then(Commands.argument("firstPartySlot", IntegerArgumentType.integer(1, 6))
                        .then(Commands.argument("secondPartySlot", IntegerArgumentType.integer(1, 6))
                            .executes(context -> deposit(
                                context.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(context, "firstPartySlot") - 1,
                                IntegerArgumentType.getInteger(context, "secondPartySlot") - 1
                            )))))
                .then(Commands.literal("status")
                    .executes(context -> status(context.getSource().getPlayerOrException())))
                .then(Commands.literal("collect")
                    .executes(context -> collect(context.getSource().getPlayerOrException())))
                .then(Commands.literal("cancel")
                    .executes(context -> cancel(context.getSource().getPlayerOrException())))
                .then(Commands.literal("force_ready")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> forceReady(
                            context.getSource(), EntityArgument.getPlayer(context, "player")
                        ))))
        );
    }

    private static int deposit(ServerPlayer player, int firstSlot, int secondSlot) {
        if (firstSlot == secondSlot) {
            fail(player, "message.cobbleventure_adventure.daycare.same_slot");
            return 0;
        }
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        if (data.find(player.getUUID()).isPresent()) {
            fail(player, "message.cobbleventure_adventure.daycare.already_active");
            return 0;
        }

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon first = party.get(firstSlot);
        Pokemon second = party.get(secondSlot);
        if (first == null || second == null) {
            fail(player, "message.cobbleventure_adventure.daycare.empty_slot");
            return 0;
        }
        if (party.occupied() - 2 < 1) {
            fail(player, "message.cobbleventure_adventure.daycare.party_required");
            return 0;
        }
        if (first.getEntity() != null || second.getEntity() != null) {
            fail(player, "message.cobbleventure_adventure.daycare.recall_first");
            return 0;
        }
        if (!CobbreedingAdapter.canBreed(first, second)) {
            fail(player, "message.cobbleventure_adventure.daycare.incompatible");
            return 0;
        }

        BigInteger fee = BigInteger.valueOf(SERVICE_FEE);
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO);
        if (balance.compareTo(fee) < 0) {
            fail(player, "message.cobbleventure_adventure.daycare.insufficient_funds", SERVICE_FEE);
            return 0;
        }

        long acceptedAt = Instant.now().toEpochMilli();
        int breedingTicks = RandomSource.create().nextIntBetweenInclusive(
            MIN_BREEDING_TICKS, MAX_BREEDING_TICKS
        );
        DaycareJob job = new DaycareJob(
            UUID.randomUUID(),
            player.getUUID(),
            first.saveToNBT(player.registryAccess(), new CompoundTag()),
            second.saveToNBT(player.registryAccess(), new CompoundTag()),
            first.getUuid(),
            second.getUuid(),
            acceptedAt,
            acceptedAt + breedingTicks * MILLIS_PER_TICK,
            SERVICE_FEE,
            null
        );

        if (!data.create(job)) {
            fail(player, "message.cobbleventure_adventure.daycare.already_active");
            return 0;
        }
        boolean firstRemoved = party.remove(first);
        boolean secondRemoved = firstRemoved && party.remove(second);
        if (!firstRemoved || !secondRemoved) {
            if (firstRemoved) {
                party.add(first);
            }
            data.remove(player.getUUID(), job.jobId());
            fail(player, "message.cobbleventure_adventure.daycare.storage_changed");
            return 0;
        }
        PlayerExtensionKt.setCobbleDollars(player, balance.subtract(fee));
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_adventure.daycare.accepted",
            first.getDisplayName(false), second.getDisplayName(false), SERVICE_FEE,
            Math.max(1L, Duration.ofMillis(job.readyAtMillis() - acceptedAt).toMinutes())
        ));
        return 1;
    }

    private static int status(ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job == null) {
            fail(player, "message.cobbleventure_adventure.daycare.none");
            return 0;
        }
        job = generateEggIfReady(player, data, job);
        if (job.hasEgg()) {
            player.sendSystemMessage(Component.translatable(
                "message.cobbleventure_adventure.daycare.ready"
            ));
            return 1;
        }
        long remainingSeconds = Math.max(
            1L, Duration.ofMillis(job.readyAtMillis() - Instant.now().toEpochMilli()).toSeconds()
        );
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_adventure.daycare.progress",
            (remainingSeconds + 59L) / 60L
        ));
        return 1;
    }

    private static int collect(ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job == null) {
            fail(player, "message.cobbleventure_adventure.daycare.none");
            return 0;
        }
        job = generateEggIfReady(player, data, job);
        if (!job.hasEgg()) {
            fail(player, "message.cobbleventure_adventure.daycare.not_ready");
            return 0;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            fail(player, "message.cobbleventure_adventure.daycare.inventory_full");
            return 0;
        }
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        if (freePcSlots(pc) < 2) {
            fail(player, "message.cobbleventure_adventure.daycare.pc_full");
            return 0;
        }

        Pokemon first = loadPokemon(player, job.parentA());
        Pokemon second = loadPokemon(player, job.parentB());
        ItemStack egg = ItemStack.parseOptional(player.registryAccess(), job.eggStack());
        if (egg.isEmpty()) {
            fail(player, "message.cobbleventure_adventure.daycare.corrupt_egg");
            return 0;
        }
        if (!pc.add(first) || !pc.add(second)) {
            pc.remove(first);
            pc.remove(second);
            fail(player, "message.cobbleventure_adventure.daycare.pc_full");
            return 0;
        }
        if (!player.getInventory().add(egg.copy())) {
            pc.remove(first);
            pc.remove(second);
            fail(player, "message.cobbleventure_adventure.daycare.inventory_full");
            return 0;
        }
        if (!data.remove(player.getUUID(), job.jobId())) {
            player.getInventory().removeItem(egg);
            pc.remove(first);
            pc.remove(second);
            fail(player, "message.cobbleventure_adventure.daycare.storage_changed");
            return 0;
        }
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_adventure.daycare.collected"
        ));
        return 1;
    }

    private static int cancel(ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job == null) {
            fail(player, "message.cobbleventure_adventure.daycare.none");
            return 0;
        }
        if (job.isTimeReady(Instant.now().toEpochMilli()) || job.hasEgg()) {
            fail(player, "message.cobbleventure_adventure.daycare.cancel_ready");
            return 0;
        }
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        if (freePcSlots(pc) < 2) {
            fail(player, "message.cobbleventure_adventure.daycare.pc_full");
            return 0;
        }
        Pokemon first = loadPokemon(player, job.parentA());
        Pokemon second = loadPokemon(player, job.parentB());
        if (!pc.add(first) || !pc.add(second)) {
            pc.remove(first);
            pc.remove(second);
            fail(player, "message.cobbleventure_adventure.daycare.pc_full");
            return 0;
        }
        if (!data.remove(player.getUUID(), job.jobId())) {
            pc.remove(first);
            pc.remove(second);
            fail(player, "message.cobbleventure_adventure.daycare.storage_changed");
            return 0;
        }
        player.sendSystemMessage(Component.translatable(
            "message.cobbleventure_adventure.daycare.cancelled"
        ));
        return 1;
    }

    private static int forceReady(CommandSourceStack source, ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job == null) {
            source.sendFailure(Component.translatable(
                "message.cobbleventure_adventure.daycare.none_target",
                player.getDisplayName()
            ));
            return 0;
        }
        DaycareJob ready = job.readyNow(Instant.now().toEpochMilli());
        data.replace(ready);
        source.sendSuccess(() -> Component.translatable(
            "message.cobbleventure_adventure.daycare.forced_ready",
            player.getDisplayName()
        ), true);
        return 1;
    }

    private static DaycareJob generateEggIfReady(
        ServerPlayer player, DaycareSavedData data, DaycareJob job
    ) {
        if (job.hasEgg() || !job.isTimeReady(Instant.now().toEpochMilli())) {
            return job;
        }
        Pokemon first = loadPokemon(player, job.parentA());
        Pokemon second = loadPokemon(player, job.parentB());
        ItemStack egg = CobbreedingAdapter.createEgg(first, second);
        Tag serialized = egg.save(player.registryAccess(), new CompoundTag());
        if (!(serialized instanceof CompoundTag eggTag)) {
            throw new IllegalStateException("Cobbreeding 알이 CompoundTag로 직렬화되지 않았습니다.");
        }
        DaycareJob completed = job.withEgg(eggTag);
        data.replace(completed);
        return completed;
    }

    private static Pokemon loadPokemon(ServerPlayer player, CompoundTag tag) {
        return new Pokemon().loadFromNBT(player.registryAccess(), tag.copy());
    }

    private static int freePcSlots(PCStore pc) {
        int free = 0;
        for (PCBox box : pc.getBoxes()) {
            free += box.getUnoccupiedSlots();
            if (free >= 2) {
                return free;
            }
        }
        return free;
    }

    private static void fail(ServerPlayer player, String key, Object... arguments) {
        Objects.requireNonNull(player, "player");
        player.sendSystemMessage(Component.translatable(key, arguments));
    }
}
