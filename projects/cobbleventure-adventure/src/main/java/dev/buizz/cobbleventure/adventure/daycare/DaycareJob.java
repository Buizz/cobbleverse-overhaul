package dev.buizz.cobbleventure.adventure.daycare;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Immutable persisted state for one player's multi-Pokemon daycare enclosure. */
final class DaycareJob {
    static final int MAX_POKEMON = 6;
    static final int MAX_EGGS = 6;

    private final UUID jobId;
    private final UUID ownerId;
    private final List<StoredPokemon> pokemon;
    private final long openedAtMillis;
    private final long nextEggCheckAtMillis;
    private final long feePaid;
    private final List<CompoundTag> eggStacks;
    private final ResourceLocation facilityDimension;
    private final BlockPos paddockCenter;

    DaycareJob(
        UUID jobId, UUID ownerId, List<StoredPokemon> pokemon,
        long openedAtMillis, long nextEggCheckAtMillis, long feePaid,
        List<CompoundTag> eggStacks, ResourceLocation facilityDimension,
        BlockPos paddockCenter
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.pokemon = List.copyOf(pokemon);
        if (this.pokemon.size() > MAX_POKEMON
            || (this.pokemon.isEmpty() && eggStacks.isEmpty())) {
            throw new IllegalArgumentException("키우미집 보관 포켓몬 수가 올바르지 않습니다.");
        }
        long distinct = this.pokemon.stream().map(StoredPokemon::pokemonId).distinct().count();
        if (distinct != this.pokemon.size()) {
            throw new IllegalArgumentException("키우미집 포켓몬 UUID가 중복되었습니다.");
        }
        if (openedAtMillis < 0 || nextEggCheckAtMillis < openedAtMillis || feePaid < 0) {
            throw new IllegalArgumentException("키우미집 시간 또는 요금이 올바르지 않습니다.");
        }
        this.openedAtMillis = openedAtMillis;
        this.nextEggCheckAtMillis = nextEggCheckAtMillis;
        this.feePaid = feePaid;
        this.eggStacks = eggStacks.stream().map(CompoundTag::copy).toList();
        if (this.eggStacks.size() > MAX_EGGS) {
            throw new IllegalArgumentException("키우미집 알 보관 수가 올바르지 않습니다.");
        }
        this.facilityDimension = Objects.requireNonNull(facilityDimension, "facilityDimension");
        this.paddockCenter = Objects.requireNonNull(paddockCenter, "paddockCenter").immutable();
    }

    UUID jobId() { return jobId; }
    UUID ownerId() { return ownerId; }
    long openedAtMillis() { return openedAtMillis; }
    long nextEggCheckAtMillis() { return nextEggCheckAtMillis; }
    long feePaid() { return feePaid; }
    ResourceLocation facilityDimension() { return facilityDimension; }
    BlockPos paddockCenter() { return paddockCenter; }
    int pokemonCount() { return pokemon.size(); }
    int eggCount() { return eggStacks.size(); }
    boolean isEggCheckReady(long nowMillis) { return nowMillis >= nextEggCheckAtMillis; }

    List<StoredPokemon> pokemon() {
        return pokemon.stream().map(StoredPokemon::copy).toList();
    }

    StoredPokemon pokemon(int index) { return pokemon.get(index).copy(); }

    List<CompoundTag> eggStacks() {
        return eggStacks.stream().map(CompoundTag::copy).toList();
    }

    DaycareJob addPokemon(StoredPokemon value, long additionalFee) {
        if (pokemon.size() >= MAX_POKEMON) throw new IllegalStateException("키우미집이 가득 찼습니다.");
        List<StoredPokemon> updated = new ArrayList<>(pokemon);
        updated.add(value.copy());
        return copy(updated, nextEggCheckAtMillis, feePaid + additionalFee, eggStacks);
    }

    DaycareJob removePokemon(int index) {
        List<StoredPokemon> updated = new ArrayList<>(pokemon);
        updated.remove(index);
        if (updated.isEmpty() && eggStacks.isEmpty()) {
            throw new IllegalStateException("마지막 포켓몬 제거는 저장 상태 삭제로 처리해야 합니다.");
        }
        return copy(updated, nextEggCheckAtMillis, feePaid, eggStacks);
    }

    DaycareJob afterEggCheck(long nextCheckAtMillis, CompoundTag discoveredEgg) {
        List<CompoundTag> updatedEggs = new ArrayList<>(eggStacks);
        if (discoveredEgg != null && !discoveredEgg.isEmpty()) {
            if (updatedEggs.size() >= MAX_EGGS) throw new IllegalStateException("알 보관함이 가득 찼습니다.");
            updatedEggs.add(discoveredEgg.copy());
        }
        return copy(pokemon, nextCheckAtMillis, feePaid, updatedEggs);
    }

    DaycareJob withoutEggs() {
        return copy(pokemon, nextEggCheckAtMillis, feePaid, List.of());
    }

    DaycareJob readyNow(long nowMillis) {
        return copy(pokemon, Math.max(openedAtMillis, nowMillis), feePaid, eggStacks);
    }

    DaycareJob initializeLegacyTraining(long nowMillis) {
        boolean changed = false;
        List<StoredPokemon> updated = new ArrayList<>(pokemon.size());
        for (StoredPokemon value : pokemon) {
            StoredPokemon initialized = value.initializeTrainingClock(nowMillis);
            updated.add(initialized);
            changed |= initialized != value;
        }
        return changed
            ? copy(updated, nextEggCheckAtMillis, feePaid, eggStacks)
            : this;
    }

    private DaycareJob copy(
        List<StoredPokemon> updatedPokemon, long updatedNextCheck,
        long updatedFee, List<CompoundTag> updatedEggs
    ) {
        return new DaycareJob(
            jobId, ownerId, updatedPokemon, openedAtMillis, updatedNextCheck,
            updatedFee, updatedEggs, facilityDimension, paddockCenter
        );
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("jobId", jobId);
        tag.putUUID("ownerId", ownerId);
        ListTag stored = new ListTag();
        for (StoredPokemon value : pokemon) stored.add(value.save());
        tag.put("pokemon", stored);
        tag.putLong("openedAtMillis", openedAtMillis);
        tag.putLong("nextEggCheckAtMillis", nextEggCheckAtMillis);
        tag.putLong("feePaid", feePaid);
        ListTag eggs = new ListTag();
        eggStacks.forEach(egg -> eggs.add(egg.copy()));
        tag.put("eggStacks", eggs);
        tag.putString("facilityDimension", facilityDimension.toString());
        tag.putLong("paddockCenter", paddockCenter.asLong());
        return tag;
    }

    static DaycareJob load(CompoundTag tag) {
        if (!tag.hasUUID("jobId") || !tag.hasUUID("ownerId")) {
            throw new IllegalArgumentException("키우미집 작업 UUID가 누락되었습니다.");
        }
        List<StoredPokemon> stored = new ArrayList<>();
        if (tag.contains("pokemon", Tag.TAG_LIST)) {
            ListTag entries = tag.getList("pokemon", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                stored.add(StoredPokemon.load(entries.getCompound(index)));
            }
        } else {
            stored.add(new StoredPokemon(tag.getUUID("parentAId"), tag.getCompound("parentA")));
            stored.add(new StoredPokemon(tag.getUUID("parentBId"), tag.getCompound("parentB")));
        }
        List<CompoundTag> eggs = new ArrayList<>();
        if (tag.contains("eggStacks", Tag.TAG_LIST)) {
            ListTag entries = tag.getList("eggStacks", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) eggs.add(entries.getCompound(index));
        } else if (tag.contains("eggStack", Tag.TAG_COMPOUND)) {
            eggs.add(tag.getCompound("eggStack"));
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("facilityDimension"));
        if (dimension == null) dimension = ResourceLocation.withDefaultNamespace("overworld");
        long openedAt = tag.contains("openedAtMillis")
            ? tag.getLong("openedAtMillis") : tag.getLong("acceptedAtMillis");
        long nextCheck = tag.contains("nextEggCheckAtMillis")
            ? tag.getLong("nextEggCheckAtMillis") : tag.getLong("readyAtMillis");
        return new DaycareJob(
            tag.getUUID("jobId"), tag.getUUID("ownerId"), stored,
            openedAt, nextCheck, tag.getLong("feePaid"), eggs, dimension,
            tag.contains("paddockCenter") ? BlockPos.of(tag.getLong("paddockCenter")) : BlockPos.ZERO
        );
    }

    record StoredPokemon(
        UUID pokemonId, CompoundTag data, boolean training, long trainingStartedAtMillis
    ) {
        private static final long UNINITIALIZED_TIME = -1L;

        StoredPokemon {
            Objects.requireNonNull(pokemonId, "pokemonId");
            data = Objects.requireNonNull(data, "data").copy();
            if (trainingStartedAtMillis < UNINITIALIZED_TIME) {
                throw new IllegalArgumentException("육성 시작 시간이 올바르지 않습니다.");
            }
        }

        StoredPokemon(UUID pokemonId, CompoundTag data) {
            this(pokemonId, data, false, 0L);
        }

        StoredPokemon copy() {
            return new StoredPokemon(pokemonId, data, training, trainingStartedAtMillis);
        }

        StoredPokemon initializeTrainingClock(long nowMillis) {
            if (!training || trainingStartedAtMillis != UNINITIALIZED_TIME) return this;
            return new StoredPokemon(pokemonId, data, true, Math.max(0L, nowMillis));
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("pokemonId", pokemonId);
            tag.put("data", data.copy());
            tag.putBoolean("training", training);
            tag.putLong("trainingStartedAtMillis", trainingStartedAtMillis);
            return tag;
        }

        static StoredPokemon load(CompoundTag tag) {
            if (!tag.hasUUID("pokemonId")) throw new IllegalArgumentException("포켓몬 UUID가 누락되었습니다.");
            boolean training = tag.getBoolean("training");
            long startedAt = tag.contains("trainingStartedAtMillis")
                ? tag.getLong("trainingStartedAtMillis")
                : training ? UNINITIALIZED_TIME : 0L;
            return new StoredPokemon(
                tag.getUUID("pokemonId"), tag.getCompound("data"), training, startedAt
            );
        }
    }
}
