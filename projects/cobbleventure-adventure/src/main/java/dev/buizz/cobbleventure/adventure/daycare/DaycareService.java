package dev.buizz.cobbleventure.adventure.daycare;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.AddExperienceResult;
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Paid multi-Pokemon daycare storage with periodic, non-guaranteed egg discovery. */
public final class DaycareService {
    static final long SERVICE_FEE = 3_000L;
    static final long TRAINING_COST_PER_INTERVAL =
        DaycarePolicy.TRAINING_COST_PER_INTERVAL;
    static final int MAX_TRAINING_EXPERIENCE = DaycarePolicy.MAX_TRAINING_EXPERIENCE;
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
                    .then(Commands.argument("partySlot", IntegerArgumentType.integer(1, 6))
                        .executes(context -> deposit(
                            context.getSource().getPlayerOrException(),
                            IntegerArgumentType.getInteger(context, "partySlot") - 1
                        ))
                        .then(Commands.argument("training", BoolArgumentType.bool())
                            .executes(context -> deposit(
                                context.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(context, "partySlot") - 1,
                                BoolArgumentType.getBool(context, "training")
                            )))))
                .then(Commands.literal("withdraw")
                    .then(Commands.argument("daycareSlot", IntegerArgumentType.integer(1, 6))
                        .executes(context -> withdraw(
                            context.getSource().getPlayerOrException(),
                            IntegerArgumentType.getInteger(context, "daycareSlot") - 1
                        ))))
                .then(Commands.literal("status")
                    .executes(context -> status(context.getSource().getPlayerOrException())))
                .then(Commands.literal("collect")
                    .executes(context -> collect(context.getSource().getPlayerOrException())))
                .then(Commands.literal("force_ready")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> forceReady(
                            context.getSource(), EntityArgument.getPlayer(context, "player")
                        ))))
        );
    }

    static int deposit(ServerPlayer player, int partySlot) {
        return deposit(player, partySlot, false);
    }

    static int deposit(ServerPlayer player, int partySlot, boolean training) {
        return depositWithFeedback(
            player, partySlot, training,
            player.level().dimension().location(), player.blockPosition()
        ).result();
    }

    static ServiceOutcome depositWithFeedback(
        ServerPlayer player, int partySlot, boolean training,
        ResourceLocation facilityDimension, BlockPos paddockCenter
    ) {
        if (partySlot < 0 || partySlot >= 6) {
            return failure(player, "message.cobbleventure_adventure.daycare.invalid_slot");
        }
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon selected = party.get(partySlot);
        if (selected == null) {
            return failure(player, "message.cobbleventure_adventure.daycare.empty_slot");
        }
        if (party.occupied() <= 1) {
            return failure(player, "message.cobbleventure_adventure.daycare.party_required");
        }
        if (selected.getEntity() != null) {
            return failure(player, "message.cobbleventure_adventure.daycare.recall_first");
        }

        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob current = data.find(player.getUUID()).orElse(null);
        long now = Instant.now().toEpochMilli();
        if (current != null) current = initializeTrainingClock(data, current, now);
        if (current != null && current.pokemonCount() >= DaycareJob.MAX_POKEMON) {
            return failure(player, "message.cobbleventure_adventure.daycare.full");
        }
        if (current != null && (!current.facilityDimension().equals(facilityDimension)
            || !current.paddockCenter().equals(paddockCenter))) {
            return failure(player, "message.cobbleventure_adventure.daycare.other_facility");
        }

        BigInteger fee = BigInteger.valueOf(SERVICE_FEE);
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player).max(BigInteger.ZERO);
        if (balance.compareTo(fee) < 0) {
            return failure(
                player, "message.cobbleventure_adventure.daycare.insufficient_funds", SERVICE_FEE
            );
        }

        DaycareJob.StoredPokemon stored = new DaycareJob.StoredPokemon(
            selected.getUuid(), selected.saveToNBT(player.registryAccess(), new CompoundTag()),
            training, training ? now : 0L
        );
        DaycareJob updated = current == null
            ? new DaycareJob(
                UUID.randomUUID(), player.getUUID(), List.of(stored), now,
                nextEggCheck(now), SERVICE_FEE, List.of(), facilityDimension, paddockCenter
            )
            : current.addPokemon(stored, SERVICE_FEE);
        if (current == null ? !data.create(updated) : !replace(data, updated)) {
            return failure(player, "message.cobbleventure_adventure.daycare.storage_changed");
        }
        if (!party.remove(selected)) {
            if (current == null) data.remove(player.getUUID(), updated.jobId());
            else data.replace(current);
            return failure(player, "message.cobbleventure_adventure.daycare.storage_changed");
        }
        PlayerExtensionKt.setCobbleDollars(player, balance.subtract(fee));
        return success(player, training
                ? "message.cobbleventure_adventure.daycare.accepted_training"
                : "message.cobbleventure_adventure.daycare.accepted_single",
            selected.getDisplayName(false), SERVICE_FEE,
            updated.pokemonCount(), DaycareJob.MAX_POKEMON,
            DaycarePolicy.TRAINING_EXPERIENCE_PER_INTERVAL,
            DaycarePolicy.TRAINING_INTERVAL_SECONDS / 60L,
            TRAINING_COST_PER_INTERVAL, MAX_TRAINING_EXPERIENCE
        );
    }

    static ServiceOutcome withdrawWithFeedback(ServerPlayer player, int daycareSlot) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job != null) {
            job = initializeTrainingClock(
                data, job, Instant.now().toEpochMilli()
            );
        }
        if (job == null || daycareSlot < 0 || daycareSlot >= job.pokemonCount()) {
            return failure(player, "message.cobbleventure_adventure.daycare.invalid_stored_slot");
        }
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        PCStore pc = Cobblemon.INSTANCE.getStorage().getPC(player);
        boolean toParty = party.occupied() < 6;
        if (!toParty && freePcSlots(pc) < 1) {
            return failure(player, "message.cobbleventure_adventure.daycare.return_storage_full");
        }
        DaycareJob.StoredPokemon stored = job.pokemon(daycareSlot);
        Pokemon pokemon = loadPokemon(player, stored.data());
        TrainingResult training = applyTraining(player, pokemon, stored);

        boolean removeJob = job.pokemonCount() == 1 && job.eggCount() == 0;
        if (removeJob) {
            if (!data.remove(player.getUUID(), job.jobId())) {
                return failure(player, "message.cobbleventure_adventure.daycare.storage_changed");
            }
        } else {
            data.replace(job.removePokemon(daycareSlot));
        }
        if (toParty) {
            party.add(pokemon);
        } else if (!pc.add(pokemon)) {
            if (removeJob) data.create(job); else data.replace(job);
            return failure(player, "message.cobbleventure_adventure.daycare.return_storage_full");
        }
        if (training.cost().signum() > 0) {
            BigInteger balance = PlayerExtensionKt.getCobbleDollars(player);
            PlayerExtensionKt.setCobbleDollars(
                player, DaycarePolicy.balanceAfterTraining(balance, training.experience())
            );
        }
        if (stored.training()) {
            return success(
                player, "message.cobbleventure_adventure.daycare.withdrawn_trained",
                pokemon.getDisplayName(false), destination(toParty),
                training.experience(), training.oldLevel(), training.newLevel(), training.cost()
            );
        }
        return success(
            player, "message.cobbleventure_adventure.daycare.withdrawn",
            pokemon.getDisplayName(false), destination(toParty)
        );
    }

    static int withdraw(ServerPlayer player, int daycareSlot) {
        return withdrawWithFeedback(player, daycareSlot).result();
    }

    static ServiceOutcome collectWithFeedback(ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = refreshState(player);
        if (job == null || job.eggCount() == 0) {
            return failure(player, "message.cobbleventure_adventure.daycare.no_eggs");
        }
        if (freeInventorySlots(player) < job.eggCount()) {
            return failure(
                player, "message.cobbleventure_adventure.daycare.inventory_slots_required",
                job.eggCount()
            );
        }
        List<ItemStack> eggs = job.eggStacks().stream()
            .map(tag -> ItemStack.parseOptional(player.registryAccess(), tag)).toList();
        if (eggs.stream().anyMatch(ItemStack::isEmpty)) {
            return failure(player, "message.cobbleventure_adventure.daycare.corrupt_egg");
        }
        for (ItemStack egg : eggs) player.getInventory().add(egg.copy());
        if (job.pokemonCount() == 0) data.remove(player.getUUID(), job.jobId());
        else data.replace(job.withoutEggs());
        return success(
            player, "message.cobbleventure_adventure.daycare.eggs_collected", eggs.size()
        );
    }

    static int collect(ServerPlayer player) { return collectWithFeedback(player).result(); }

    static int status(ServerPlayer player) {
        DaycareJob job = refreshState(player);
        if (job == null) return failure(player, "message.cobbleventure_adventure.daycare.none").result();
        List<Pokemon> occupants = loadPokemon(player, job);
        if (job.eggCount() > 0) {
            return success(
                player, "message.cobbleventure_adventure.daycare.eggs_waiting", job.eggCount()
            ).result();
        }
        if (!CobbreedingAdapter.canBreed(occupants)) {
            return success(
                player, "message.cobbleventure_adventure.daycare.no_compatible_pair"
            ).result();
        }
        return success(
            player, "message.cobbleventure_adventure.daycare.next_check",
            remainingMinutes(job)
        ).result();
    }

    static DaycareJob refreshState(ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job != null) {
            job = initializeTrainingClock(
                data, job, Instant.now().toEpochMilli()
            );
        }
        if (job == null || job.pokemonCount() < 2 || job.eggCount() >= DaycareJob.MAX_EGGS) {
            return job;
        }
        long now = Instant.now().toEpochMilli();
        if (!job.isEggCheckReady(now)) return job;
        List<Pokemon> occupants = loadPokemon(player, job);
        CompoundTag discovered = null;
        if (CobbreedingAdapter.canBreed(occupants)
            && DaycarePolicy.discoversEgg(
                RandomSource.create(job.jobId().getMostSignificantBits() ^ now).nextFloat()
            )) {
            ItemStack egg = CobbreedingAdapter.createEgg(occupants);
            Tag serialized = egg.save(player.registryAccess(), new CompoundTag());
            if (!(serialized instanceof CompoundTag eggTag)) {
                throw new IllegalStateException("Cobbreeding 알이 CompoundTag로 직렬화되지 않았습니다.");
            }
            discovered = eggTag;
        }
        DaycareJob updated = job.afterEggCheck(nextEggCheck(now), discovered);
        data.replace(updated);
        return updated;
    }

    static boolean hasCompatiblePair(ServerPlayer player, DaycareJob job) {
        return job != null && CobbreedingAdapter.canBreed(loadPokemon(player, job));
    }

    static long remainingMinutes(DaycareJob job) {
        return Math.max(1L, Duration.ofMillis(
            job.nextEggCheckAtMillis() - Instant.now().toEpochMilli()
        ).toMinutes());
    }

    static int accruedTrainingExperience(DaycareJob.StoredPokemon stored) {
        return DaycarePolicy.accruedTrainingExperience(
            stored, Instant.now().toEpochMilli()
        );
    }

    static long secondsUntilNextTrainingGain(DaycareJob.StoredPokemon stored) {
        return DaycarePolicy.secondsUntilNextTrainingGain(
            stored, Instant.now().toEpochMilli()
        );
    }

    private static int forceReady(CommandSourceStack source, ServerPlayer player) {
        DaycareSavedData data = DaycareSavedData.get(player.getServer());
        DaycareJob job = data.find(player.getUUID()).orElse(null);
        if (job == null) {
            source.sendFailure(Component.translatable(
                "message.cobbleventure_adventure.daycare.none_target", player.getDisplayName()
            ));
            return 0;
        }
        data.replace(job.readyNow(Instant.now().toEpochMilli()));
        source.sendSuccess(() -> Component.translatable(
            "message.cobbleventure_adventure.daycare.forced_ready", player.getDisplayName()
        ), true);
        return 1;
    }

    private static List<Pokemon> loadPokemon(ServerPlayer player, DaycareJob job) {
        return job.pokemon().stream().map(value -> loadPokemon(player, value.data())).toList();
    }

    private static Pokemon loadPokemon(ServerPlayer player, CompoundTag tag) {
        return new Pokemon().loadFromNBT(player.registryAccess(), tag.copy());
    }

    private static TrainingResult applyTraining(
        ServerPlayer player, Pokemon pokemon, DaycareJob.StoredPokemon stored
    ) {
        if (!stored.training()) {
            return new TrainingResult(0, pokemon.getLevel(), pokemon.getLevel(), BigInteger.ZERO);
        }
        int available = DaycarePolicy.accruedTrainingExperience(
            stored, Instant.now().toEpochMilli()
        );
        int requested = Math.min(available, pokemon.getExperienceToLevel(100));
        int oldLevel = pokemon.getLevel();
        AddExperienceResult result = pokemon.addExperience(
            new SidemodExperienceSource("cobbleventure_adventure"), requested
        );
        int applied = Math.max(0, result.getExperienceAdded());
        return new TrainingResult(
            applied, oldLevel, pokemon.getLevel(),
            DaycarePolicy.trainingCost(applied)
        );
    }

    private static Component destination(boolean party) {
        return Component.translatable(party
            ? "message.cobbleventure_adventure.daycare.destination_party"
            : "message.cobbleventure_adventure.daycare.destination_pc");
    }

    private static DaycareJob initializeTrainingClock(
        DaycareSavedData data, DaycareJob job, long nowMillis
    ) {
        DaycareJob initialized = job.initializeLegacyTraining(nowMillis);
        if (initialized != job) data.replace(initialized);
        return initialized;
    }

    private static long nextEggCheck(long now) {
        int ticks = RandomSource.create().nextIntBetweenInclusive(
            MIN_BREEDING_TICKS, MAX_BREEDING_TICKS
        );
        return now + ticks * MILLIS_PER_TICK;
    }

    private static boolean replace(DaycareSavedData data, DaycareJob updated) {
        data.replace(updated);
        return true;
    }

    private static int freePcSlots(PCStore pc) {
        int free = 0;
        for (PCBox box : pc.getBoxes()) free += box.getUnoccupiedSlots();
        return free;
    }

    private static int freeInventorySlots(ServerPlayer player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.isEmpty()) free++;
        return free;
    }

    private static ServiceOutcome failure(ServerPlayer player, String key, Object... arguments) {
        return notify(player, 0, key, arguments);
    }

    private static ServiceOutcome success(ServerPlayer player, String key, Object... arguments) {
        return notify(player, 1, key, arguments);
    }

    private static ServiceOutcome notify(
        ServerPlayer player, int result, String key, Object... arguments
    ) {
        Objects.requireNonNull(player, "player");
        Component message = Component.translatable(key, arguments);
        player.sendSystemMessage(message);
        return new ServiceOutcome(result, message);
    }

    record ServiceOutcome(int result, Component message) {}
    private record TrainingResult(
        int experience, int oldLevel, int newLevel, BigInteger cost
    ) {}
}
