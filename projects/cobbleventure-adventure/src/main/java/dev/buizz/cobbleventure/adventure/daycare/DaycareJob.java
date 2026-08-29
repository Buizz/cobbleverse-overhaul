package dev.buizz.cobbleventure.adventure.daycare;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Immutable persisted state for one paid daycare breeding request. */
final class DaycareJob {
    private final UUID jobId;
    private final UUID ownerId;
    private final CompoundTag parentA;
    private final CompoundTag parentB;
    private final UUID parentAId;
    private final UUID parentBId;
    private final long acceptedAtMillis;
    private final long readyAtMillis;
    private final long feePaid;
    private final CompoundTag eggStack;
    private final ResourceLocation facilityDimension;
    private final BlockPos paddockCenter;

    DaycareJob(
        UUID jobId,
        UUID ownerId,
        CompoundTag parentA,
        CompoundTag parentB,
        UUID parentAId,
        UUID parentBId,
        long acceptedAtMillis,
        long readyAtMillis,
        long feePaid,
        CompoundTag eggStack
    ) {
        this(
            jobId, ownerId, parentA, parentB, parentAId, parentBId,
            acceptedAtMillis, readyAtMillis, feePaid, eggStack,
            ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO
        );
    }

    DaycareJob(
        UUID jobId,
        UUID ownerId,
        CompoundTag parentA,
        CompoundTag parentB,
        UUID parentAId,
        UUID parentBId,
        long acceptedAtMillis,
        long readyAtMillis,
        long feePaid,
        CompoundTag eggStack,
        ResourceLocation facilityDimension,
        BlockPos paddockCenter
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.parentA = Objects.requireNonNull(parentA, "parentA").copy();
        this.parentB = Objects.requireNonNull(parentB, "parentB").copy();
        this.parentAId = Objects.requireNonNull(parentAId, "parentAId");
        this.parentBId = Objects.requireNonNull(parentBId, "parentBId");
        if (parentAId.equals(parentBId)) {
            throw new IllegalArgumentException("부모 포켓몬 UUID는 서로 달라야 합니다.");
        }
        if (acceptedAtMillis < 0 || readyAtMillis < acceptedAtMillis || feePaid < 0) {
            throw new IllegalArgumentException("키우미 작업 시간 또는 요금이 올바르지 않습니다.");
        }
        this.acceptedAtMillis = acceptedAtMillis;
        this.readyAtMillis = readyAtMillis;
        this.feePaid = feePaid;
        this.eggStack = eggStack == null || eggStack.isEmpty() ? null : eggStack.copy();
        this.facilityDimension = Objects.requireNonNull(facilityDimension, "facilityDimension");
        this.paddockCenter = Objects.requireNonNull(paddockCenter, "paddockCenter").immutable();
    }

    UUID jobId() {
        return jobId;
    }

    UUID ownerId() {
        return ownerId;
    }

    CompoundTag parentA() {
        return parentA.copy();
    }

    CompoundTag parentB() {
        return parentB.copy();
    }

    UUID parentAId() {
        return parentAId;
    }

    UUID parentBId() {
        return parentBId;
    }

    long acceptedAtMillis() {
        return acceptedAtMillis;
    }

    long readyAtMillis() {
        return readyAtMillis;
    }

    long feePaid() {
        return feePaid;
    }

    boolean isTimeReady(long nowMillis) {
        return nowMillis >= readyAtMillis;
    }

    boolean hasEgg() {
        return eggStack != null;
    }

    CompoundTag eggStack() {
        return eggStack == null ? null : eggStack.copy();
    }

    ResourceLocation facilityDimension() {
        return facilityDimension;
    }

    BlockPos paddockCenter() {
        return paddockCenter;
    }

    DaycareJob withEgg(CompoundTag value) {
        if (hasEgg()) {
            throw new IllegalStateException("키우미 작업의 알은 한 번만 생성할 수 있습니다.");
        }
        return new DaycareJob(
            jobId, ownerId, parentA, parentB, parentAId, parentBId,
            acceptedAtMillis, readyAtMillis, feePaid,
            Objects.requireNonNull(value, "value"), facilityDimension, paddockCenter
        );
    }

    DaycareJob readyNow(long nowMillis) {
        return new DaycareJob(
            jobId, ownerId, parentA, parentB, parentAId, parentBId,
            acceptedAtMillis, Math.max(acceptedAtMillis, nowMillis), feePaid, eggStack,
            facilityDimension, paddockCenter
        );
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("jobId", jobId);
        tag.putUUID("ownerId", ownerId);
        tag.put("parentA", parentA.copy());
        tag.put("parentB", parentB.copy());
        tag.putUUID("parentAId", parentAId);
        tag.putUUID("parentBId", parentBId);
        tag.putLong("acceptedAtMillis", acceptedAtMillis);
        tag.putLong("readyAtMillis", readyAtMillis);
        tag.putLong("feePaid", feePaid);
        tag.putString("facilityDimension", facilityDimension.toString());
        tag.putLong("paddockCenter", paddockCenter.asLong());
        if (eggStack != null) {
            tag.put("eggStack", eggStack.copy());
        }
        return tag;
    }

    static DaycareJob load(CompoundTag tag) {
        if (!tag.hasUUID("jobId") || !tag.hasUUID("ownerId")
            || !tag.hasUUID("parentAId") || !tag.hasUUID("parentBId")) {
            throw new IllegalArgumentException("키우미 작업 UUID가 누락되었습니다.");
        }
        ResourceLocation dimension = ResourceLocation.tryParse(
            tag.getString("facilityDimension")
        );
        if (dimension == null) {
            dimension = ResourceLocation.withDefaultNamespace("overworld");
        }
        return new DaycareJob(
            tag.getUUID("jobId"),
            tag.getUUID("ownerId"),
            tag.getCompound("parentA"),
            tag.getCompound("parentB"),
            tag.getUUID("parentAId"),
            tag.getUUID("parentBId"),
            tag.getLong("acceptedAtMillis"),
            tag.getLong("readyAtMillis"),
            tag.getLong("feePaid"),
            tag.contains("eggStack") ? tag.getCompound("eggStack") : null,
            dimension,
            tag.contains("paddockCenter")
                ? BlockPos.of(tag.getLong("paddockCenter")) : BlockPos.ZERO
        );
    }
}
