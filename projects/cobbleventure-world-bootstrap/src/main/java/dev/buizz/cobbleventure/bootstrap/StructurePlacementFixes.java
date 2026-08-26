package dev.buizz.cobbleventure.bootstrap;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.slf4j.Logger;

/** Repairs modded block state that can be disturbed while a structure template is placed. */
final class StructurePlacementFixes {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AUTHORED_STORAGE_MARKER = "cobbleventureAuthoredStorage";
    private static final ResourceLocation ELEVATOR_PULLEY = id("create", "elevator_pulley");
    private static final ResourceLocation ELEVATOR_CONTACT = id("create", "elevator_contact");
    private static final int ELEVATOR_ASSEMBLY_DELAY_TICKS = 2;
    private static final int ELEVATOR_ASSEMBLY_MAX_ATTEMPTS = 20;
    private static final int COPYCAT_RESTORE_DELAY_TICKS = 2;
    private static final int COPYCAT_RESTORE_RETRY_TICKS = 5;
    private static final int COPYCAT_RESTORE_MAX_ATTEMPTS = 20;
    private static final int COPYCAT_RESTORE_PASSES = 2;
    private static final int COPYCAT_CHUNK_SYNC_DELAY_TICKS = 2;
    private static final int COPYCAT_CHUNK_SYNC_PASSES = 2;
    private static final Map<ElevatorPulleyKey, PendingElevatorAssembly>
        PENDING_ELEVATOR_ASSEMBLIES = new LinkedHashMap<>();
    private static final Map<ElevatorDoorKey, ElevatorLandingDoors>
        ELEVATOR_LANDING_DOORS = new LinkedHashMap<>();
    private static final Map<CopycatBlockKey, PendingCopycatRestore>
        PENDING_COPYCAT_RESTORES = new LinkedHashMap<>();
    private static final Map<CopycatChunkSyncKey, PendingCopycatChunkSync>
        PENDING_COPYCAT_CHUNK_SYNCS = new LinkedHashMap<>();
    private static final Map<Class<?>, Method> COPYCAT_MULTI_SET_MATERIAL_METHODS =
        new LinkedHashMap<>();
    private static final Map<Class<?>, Method> COPYCAT_SINGLE_SET_MATERIAL_METHODS =
        new LinkedHashMap<>();
    private static final Map<Class<?>, Method> COPYCAT_SINGLE_SET_ITEM_METHODS =
        new LinkedHashMap<>();
    private static final Map<ResourceLocation, ResourceLocation> FRIDGE_LOWER_BY_UPPER = Map.of(
        id("cobblefurnies", "light_freezer"), id("cobblefurnies", "light_fridge"),
        id("cobblefurnies", "dark_freezer"), id("cobblefurnies", "dark_fridge")
    );
    private static final Map<ResourceLocation, ResourceLocation> FRIDGE_UPPER_BY_LOWER = Map.of(
        id("cobblefurnies", "light_fridge"), id("cobblefurnies", "light_freezer"),
        id("cobblefurnies", "dark_fridge"), id("cobblefurnies", "dark_freezer")
    );
    private StructurePlacementFixes() {
    }

    static void afterPlacement(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        Vec3i size = template.getSize(settings.getRotation());
        repairFridges(level, origin, size);
        restoreCopycatMaterials(level, origin, template, settings);
        markAuthoredStorageBlocks(level, origin, size);
        scheduleElevatorAssemblies(level, origin, template, settings);
    }

    /** Marks inventory blocks copied from an authored template, without affecting player placements. */
    private static void markAuthoredStorageBlocks(
        ServerLevel level, BlockPos origin, Vec3i size
    ) {
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        int marked = 0;
        for (BlockPos cursor : BlockPos.betweenClosed(origin, end)) {
            BlockPos position = cursor.immutable();
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null || !hasItemStorage(level, position, blockEntity)) {
                continue;
            }
            blockEntity.getPersistentData().putBoolean(AUTHORED_STORAGE_MARKER, true);
            blockEntity.setChanged();
            marked++;
        }
        if (marked > 0) {
            LOGGER.debug("Locked {} authored storage blocks at {}", marked, origin);
        }
    }

    private static boolean hasItemStorage(
        ServerLevel level, BlockPos position, BlockEntity blockEntity
    ) {
        if (blockEntity instanceof Container) {
            return true;
        }
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, position, null) != null) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (level.getCapability(
                Capabilities.ItemHandler.BLOCK, position, direction
            ) != null) {
                return true;
            }
        }
        return false;
    }

    static boolean isAuthoredStorage(BlockEntity blockEntity) {
        return blockEntity != null
            && blockEntity.getPersistentData().getBoolean(AUTHORED_STORAGE_MARKER);
    }

    static void clearPendingElevatorAssemblies() {
        PENDING_ELEVATOR_ASSEMBLIES.clear();
        ELEVATOR_LANDING_DOORS.clear();
        PENDING_COPYCAT_RESTORES.clear();
        PENDING_COPYCAT_CHUNK_SYNCS.clear();
    }

    static void tickPendingElevatorAssemblies(MinecraftServer server) {
        tickPendingCopycatChunkSyncs(server);
        tickPendingCopycatRestores(server);
        tickElevatorLandingDoors(server);
        Iterator<Map.Entry<ElevatorPulleyKey, PendingElevatorAssembly>> iterator =
            PENDING_ELEVATOR_ASSEMBLIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ElevatorPulleyKey, PendingElevatorAssembly> entry = iterator.next();
            ElevatorPulleyKey key = entry.getKey();
            PendingElevatorAssembly pending = entry.getValue();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || level.getGameTime() < pending.nextAttemptTick()) {
                continue;
            }

            ElevatorAssemblyResult result = tryAssembleElevator(level, key.position(), pending);
            if (result == ElevatorAssemblyResult.COMPLETE) {
                iterator.remove();
                continue;
            }

            int attempts = pending.attempts() + 1;
            if (result == ElevatorAssemblyResult.ABORT
                || attempts >= ELEVATOR_ASSEMBLY_MAX_ATTEMPTS) {
                LOGGER.warn(
                    "Create elevator could not be assembled after structure placement: "
                        + "dimension={}, pulley={}, attempts={}",
                    key.dimension().location(), key.position(), attempts
                );
                iterator.remove();
                continue;
            }
            entry.setValue(new PendingElevatorAssembly(
                attempts,
                level.getGameTime() + ELEVATOR_ASSEMBLY_DELAY_TICKS,
                pending.triggered() || result == ElevatorAssemblyResult.RETRY_TRIGGERED,
                pending.minContactY(), pending.maxContactY()
            ));
        }
    }

    /**
     * A structure can finish restoring its copycat materials before a player starts tracking
     * the containing chunk. In that ordering, the early block-entity packet is discarded by
     * the client and its already-built chunk model keeps the default copycat-base material.
     * Resend the authoritative block-entity data just after the chunk becomes visible.
     */
    static void scheduleCopycatChunkSync(ServerPlayer player, ChunkPos chunkPos) {
        ServerLevel level = player.serverLevel();
        CopycatChunkSyncKey key = new CopycatChunkSyncKey(
            player.getUUID(), level.dimension(), chunkPos.toLong()
        );
        PENDING_COPYCAT_CHUNK_SYNCS.put(
            key,
            new PendingCopycatChunkSync(
                COPYCAT_CHUNK_SYNC_PASSES,
                level.getGameTime() + COPYCAT_CHUNK_SYNC_DELAY_TICKS
            )
        );
    }

    private static void tickPendingCopycatChunkSyncs(MinecraftServer server) {
        Iterator<Map.Entry<CopycatChunkSyncKey, PendingCopycatChunkSync>> iterator =
            PENDING_COPYCAT_CHUNK_SYNCS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CopycatChunkSyncKey, PendingCopycatChunkSync> entry = iterator.next();
            CopycatChunkSyncKey key = entry.getKey();
            PendingCopycatChunkSync pending = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(key.playerId());
            ServerLevel level = server.getLevel(key.dimension());
            if (player == null || level == null
                || !player.serverLevel().dimension().equals(key.dimension())) {
                iterator.remove();
                continue;
            }
            if (level.getGameTime() < pending.nextSyncTick()) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(key.chunkKey());
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                iterator.remove();
                continue;
            }

            int synced = 0;
            LinkedHashSet<BlockPos> syncedPositions = new LinkedHashSet<>();
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(
                    blockEntity.getBlockState().getBlock()
                );
                if (!isCopycatBlock(blockId)) {
                    continue;
                }
                var packet = blockEntity.getUpdatePacket();
                if (packet != null) {
                    player.connection.send(packet);
                    syncedPositions.add(blockEntity.getBlockPos().immutable());
                    synced++;
                }
            }
            // Receiving an identical block-entity packet does not run Copycats' public material
            // setter. Send the exact positions after those packets so the client can apply the
            // authoritative values through the same lifecycle used by a player interaction.
            CopycatRenderSyncNetwork.sync(player, syncedPositions);
            if (synced > 0) {
                LOGGER.debug(
                    "Resent {} copycat block entities to {} for tracked chunk {}",
                    synced, player.getGameProfile().getName(), chunkPos
                );
            }

            int remainingPasses = pending.remainingPasses() - 1;
            if (remainingPasses <= 0) {
                iterator.remove();
            } else {
                entry.setValue(new PendingCopycatChunkSync(
                    remainingPasses, level.getGameTime() + COPYCAT_RESTORE_RETRY_TICKS
                ));
            }
        }
    }

    private static void tickPendingCopycatRestores(MinecraftServer server) {
        Iterator<Map.Entry<CopycatBlockKey, PendingCopycatRestore>> iterator =
            PENDING_COPYCAT_RESTORES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CopycatBlockKey, PendingCopycatRestore> entry = iterator.next();
            CopycatBlockKey key = entry.getKey();
            PendingCopycatRestore pending = entry.getValue();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || level.getGameTime() < pending.nextAttemptTick()) {
                continue;
            }

            BlockState state = level.getBlockState(key.position());
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(pending.blockId())) {
                iterator.remove();
                continue;
            }

            BlockEntity blockEntity = level.getBlockEntity(key.position());
            if (blockEntity == null) {
                int attempts = pending.attempts() + 1;
                if (attempts >= COPYCAT_RESTORE_MAX_ATTEMPTS) {
                    LOGGER.warn(
                        "Copycat material could not be restored after structure placement: "
                            + "dimension={}, position={}, block={}",
                        key.dimension().location(), key.position(), pending.blockId()
                    );
                    iterator.remove();
                } else {
                    entry.setValue(pending.withRetry(
                        attempts, level.getGameTime() + COPYCAT_RESTORE_RETRY_TICKS
                    ));
                }
                continue;
            }

            try {
                applyCopycatMaterial(level, key.position(), state, blockEntity, pending.data());
                int remainingPasses = pending.remainingPasses() - 1;
                if (remainingPasses <= 0) {
                    iterator.remove();
                } else {
                    entry.setValue(pending.withPass(
                        remainingPasses,
                        level.getGameTime() + COPYCAT_RESTORE_RETRY_TICKS
                    ));
                }
            } catch (RuntimeException error) {
                LOGGER.warn(
                    "Failed delayed copycat material restore at {} for {}",
                    key.position(), pending.blockId(), error
                );
                iterator.remove();
            }
        }
    }

    static void scheduleElevatorAssemblies(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        ElevatorContactRange contactRange =
            repairElevatorColumnTarget(level, origin, template, settings);
        registerElevatorLandingDoors(level, origin, template, settings);
        Block pulleyBlock = BuiltInRegistries.BLOCK.get(ELEVATOR_PULLEY);
        if (BuiltInRegistries.BLOCK.getKey(pulleyBlock).equals(ELEVATOR_PULLEY)) {
            for (StructureTemplate.StructureBlockInfo info
                : template.filterBlocks(origin, settings, pulleyBlock)) {
                ElevatorPulleyKey key = new ElevatorPulleyKey(
                    level.dimension(), info.pos().immutable()
                );
                PENDING_ELEVATOR_ASSEMBLIES.put(key, new PendingElevatorAssembly(
                    0,
                    level.getGameTime() + ELEVATOR_ASSEMBLY_DELAY_TICKS,
                    false,
                    contactRange == null ? Integer.MAX_VALUE : contactRange.minY(),
                    contactRange == null ? Integer.MIN_VALUE : contactRange.maxY()
                ));
                LOGGER.debug(
                    "Scheduled Create elevator assembly at {} in {}",
                    info.pos(), level.dimension().location()
                );
            }
        }
    }

    private static ElevatorContactRange repairElevatorColumnTarget(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        Block contactBlock = BuiltInRegistries.BLOCK.get(ELEVATOR_CONTACT);
        if (!BuiltInRegistries.BLOCK.getKey(contactBlock).equals(ELEVATOR_CONTACT)) {
            return null;
        }
        List<StructureTemplate.StructureBlockInfo> contacts =
            template.filterBlocks(origin, settings, contactBlock);
        if (contacts.isEmpty()) {
            return null;
        }
        int minContactY = contacts.stream().mapToInt(info -> info.pos().getY()).min()
            .orElseThrow();
        int maxContactY = contacts.stream().mapToInt(info -> info.pos().getY()).max()
            .orElseThrow();
        Integer targetY = null;
        for (StructureTemplate.StructureBlockInfo contact : contacts) {
            CompoundTag sourceData = contact.nbt();
            if (sourceData == null) {
                continue;
            }
            String currentFloor = sourceData.getString("LastReportedCurrentFloor");
            if (!currentFloor.isBlank()
                && sourceData.getString("ShortName").equals(currentFloor)) {
                targetY = contact.pos().getY();
                break;
            }
        }
        if (targetY == null) {
            return new ElevatorContactRange(minContactY, maxContactY);
        }

        int repaired = 0;
        for (StructureTemplate.StructureBlockInfo contact : contacts) {
            BlockEntity blockEntity = level.getBlockEntity(contact.pos());
            if (blockEntity == null) {
                continue;
            }
            CompoundTag data = blockEntity.saveWithFullMetadata(level.registryAccess());
            data.putInt("ColumnTarget", targetY);
            blockEntity.loadWithComponents(data, level.registryAccess());
            blockEntity.setChanged();
            BlockState state = level.getBlockState(contact.pos());
            level.sendBlockUpdated(contact.pos(), state, state, 16);
            repaired++;
        }
        if (repaired > 0) {
            LOGGER.debug(
                "Relocated Create elevator column target to Y={} for {} contacts at {}",
                targetY, repaired, origin
            );
        }
        return new ElevatorContactRange(minContactY, maxContactY);
    }

    /**
     * Create opens a door carried by the elevator and then tries to find the matching
     * stationary door from the moving actor's world position. After a contraption is
     * restored from structure-entity NBT, that adjacent-door lookup can fail even though
     * the elevator and its carried doors work correctly. Bind authored landing doors
     * directly to their contact so existing interiors do not need to be rebuilt.
     */
    private static void registerElevatorLandingDoors(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        Block contactBlock = BuiltInRegistries.BLOCK.get(ELEVATOR_CONTACT);
        if (!BuiltInRegistries.BLOCK.getKey(contactBlock).equals(ELEVATOR_CONTACT)) {
            return;
        }
        for (StructureTemplate.StructureBlockInfo contact
            : template.filterBlocks(origin, settings, contactBlock)) {
            CompoundTag sourceData = contact.nbt();
            if (sourceData != null
                && sourceData.contains("DoorControl")
                && sourceData.getString("DoorControl").equalsIgnoreCase("NONE")) {
                continue;
            }
            List<BlockPos> doors = findLandingDoors(level, contact.pos());
            ElevatorDoorKey key = new ElevatorDoorKey(
                level.dimension(), contact.pos().immutable()
            );
            if (doors.isEmpty()) {
                ELEVATOR_LANDING_DOORS.remove(key);
            } else {
                ELEVATOR_LANDING_DOORS.put(key, new ElevatorLandingDoors(doors));
            }
        }
    }

    private static List<BlockPos> findLandingDoors(
        ServerLevel level, BlockPos contact
    ) {
        LinkedHashSet<BlockPos> doors = new LinkedHashSet<>();
        int doorY = contact.getY() + 2;
        for (int deltaX = -3; deltaX <= 3; deltaX++) {
            for (int deltaZ = -3; deltaZ <= 3; deltaZ++) {
                BlockPos position = new BlockPos(
                    contact.getX() + deltaX, doorY, contact.getZ() + deltaZ
                );
                BlockState state = level.getBlockState(position);
                if (state.getBlock() instanceof DoorBlock
                    && state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                    doors.add(position.immutable());
                }
            }
        }
        return List.copyOf(doors);
    }

    private static void tickElevatorLandingDoors(MinecraftServer server) {
        for (Map.Entry<ElevatorDoorKey, ElevatorLandingDoors> entry
            : ELEVATOR_LANDING_DOORS.entrySet()) {
            ElevatorDoorKey key = entry.getKey();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !level.isLoaded(key.contact())) {
                continue;
            }
            BlockState contactState = level.getBlockState(key.contact());
            if (!BuiltInRegistries.BLOCK.getKey(contactState.getBlock())
                .equals(ELEVATOR_CONTACT)) {
                continue;
            }
            boolean open = booleanProperty(contactState, "powering");
            for (BlockPos doorPosition : entry.getValue().lowerDoors()) {
                BlockState doorState = level.getBlockState(doorPosition);
                if (!(doorState.getBlock() instanceof DoorBlock door)
                    || !doorState.hasProperty(DoorBlock.OPEN)
                    || doorState.getValue(DoorBlock.OPEN) == open) {
                    continue;
                }
                door.setOpen(null, level, doorState, doorPosition, open);
            }
        }
    }

    private static boolean booleanProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return state.getValue(property).toString().equals("true");
            }
        }
        return false;
    }

    private static ElevatorAssemblyResult tryAssembleElevator(
        ServerLevel level,
        BlockPos position,
        PendingElevatorAssembly pending
    ) {
        if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock())
            .equals(ELEVATOR_PULLEY)) {
            return ElevatorAssemblyResult.ABORT;
        }
        BlockEntity pulley = level.getBlockEntity(position);
        if (pulley == null) {
            return ElevatorAssemblyResult.RETRY;
        }

        repairElevatorReachability(pulley, pending);

        Boolean assembled = elevatorIsAssembled(pulley);
        if (Boolean.TRUE.equals(assembled)) {
            LOGGER.info(
                "Assembled Create elevator after structure placement at {} in {}",
                position, level.dimension().location()
            );
            return ElevatorAssemblyResult.COMPLETE;
        }
        if (pending.triggered() && assembled == null) {
            // Never invoke the toggle twice when Create's running state cannot be inspected:
            // a second click could disassemble an elevator that assembled successfully.
            LOGGER.info(
                "Triggered Create elevator assembly at {} in {}; state verification is "
                    + "unavailable",
                position, level.dimension().location()
            );
            return ElevatorAssemblyResult.COMPLETE;
        }

        Double speed = invokeNumberMethod(pulley, "getSpeed");
        if (speed == null || Math.abs(speed) < 0.0001D) {
            return ElevatorAssemblyResult.RETRY;
        }
        try {
            invokeNoArgMethod(pulley, "clicked");
            return ElevatorAssemblyResult.RETRY_TRIGGERED;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn(
                "Failed to trigger Create elevator pulley at {} in {}",
                position, level.dimension().location(), error
            );
            return ElevatorAssemblyResult.ABORT;
        }
    }

    private static Boolean elevatorIsAssembled(BlockEntity pulley) {
        Object contraption = readField(pulley, "movedContraption");
        if (contraption != FIELD_UNAVAILABLE) {
            return contraption != null;
        }
        Object running = readField(pulley, "running");
        return running instanceof Boolean value ? value : null;
    }

    private static void repairElevatorReachability(
        BlockEntity pulley, PendingElevatorAssembly pending
    ) {
        Object movedContraption = readField(pulley, "movedContraption");
        if (movedContraption == null || movedContraption == FIELD_UNAVAILABLE) {
            return;
        }
        Object contraption = readField(movedContraption, "contraption");
        if (contraption == null || contraption == FIELD_UNAVAILABLE) {
            return;
        }
        Object currentMin = readField(contraption, "minContactY");
        Object currentMax = readField(contraption, "maxContactY");
        if (currentMin instanceof Integer
            && pending.minContactY() != Integer.MAX_VALUE) {
            writeField(contraption, "minContactY", pending.minContactY());
        }
        if (currentMax instanceof Integer
            && pending.maxContactY() != Integer.MIN_VALUE) {
            writeField(contraption, "maxContactY", pending.maxContactY());
        }
    }

    private static Double invokeNumberMethod(Object target, String name) {
        try {
            Object value = findMethod(target.getClass(), name).invoke(target);
            return value instanceof Number number ? number.doubleValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void invokeNoArgMethod(Object target, String name)
        throws ReflectiveOperationException {
        try {
            findMethod(target.getClass(), name).invoke(target);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw error;
        }
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through Create's block entity superclass hierarchy.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "()");
    }

    private static final Object FIELD_UNAVAILABLE = new Object();

    private static Object readField(Object target, String name) {
        for (Class<?> current = target.getClass(); current != null;
            current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue through Create's block entity superclass hierarchy.
            } catch (IllegalAccessException | RuntimeException error) {
                return FIELD_UNAVAILABLE;
            }
        }
        return FIELD_UNAVAILABLE;
    }

    private static void writeField(Object target, String name, Object value) {
        for (Class<?> current = target.getClass(); current != null;
            current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Continue through Create's contraption class hierarchy.
            } catch (IllegalAccessException | RuntimeException ignored) {
                return;
            }
        }
    }

    /**
     * Structure placement can leave Create and Copycats+ block entities with their default
     * material even though the source template still contains the authored material NBT.
     * Reapply that source data before the final update packet is sent to clients.
     */
    private static void restoreCopycatMaterials(
        ServerLevel level,
        BlockPos origin,
        StructureTemplate template,
        StructurePlaceSettings settings
    ) {
        int restored = 0;
        // Resolve this after registries have finished loading. Keeping this list in a static
        // field can capture only Create's blocks when this helper is initialized before
        // Copycats+ finishes registering its multi-state blocks.
        for (Block copycatBlock : copycatBlocks()) {
            for (StructureTemplate.StructureBlockInfo info
                : template.filterBlocks(origin, settings, copycatBlock)) {
                CompoundTag sourceData = info.nbt();
                if (sourceData == null) {
                    continue;
                }
                BlockState state = level.getBlockState(info.pos());
                if (!state.is(copycatBlock)) {
                    continue;
                }
                CompoundTag transformedData = transformCopycatMaterialData(
                    sourceData,
                    level.holderLookup(Registries.BLOCK),
                    settings.getMirror(),
                    settings.getRotation()
                );
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(copycatBlock);
                PENDING_COPYCAT_RESTORES.put(
                    new CopycatBlockKey(level.dimension(), info.pos().immutable()),
                    new PendingCopycatRestore(
                        blockId,
                        transformedData.copy(),
                        0,
                        COPYCAT_RESTORE_PASSES,
                        level.getGameTime() + COPYCAT_RESTORE_DELAY_TICKS
                    )
                );
                try {
                    // Copycats+ validates the consumed item and material while reading NBT.
                    // BlockEntity.loadStatic() performs that read before attaching a Level,
                    // so level-sensitive copycats can reject valid authored material and
                    // reset themselves to create:copycat_base. Reuse the entity already
                    // registered in the chunk and reload it now that it has a Level. Replacing
                    // an entity after placement can leave the chunk's lifecycle and pending
                    // synchronization associated with the original default-material instance.
                    BlockEntity placedEntity = level.getBlockEntity(info.pos());
                    if (placedEntity == null) {
                        LOGGER.warn(
                            "Copycat material NBT found no block entity at {} for {}",
                            info.pos(), BuiltInRegistries.BLOCK.getKey(copycatBlock)
                        );
                        continue;
                    }
                    applyCopycatMaterial(
                        level, info.pos(), state, placedEntity, transformedData
                    );
                    restored++;
                } catch (RuntimeException error) {
                    LOGGER.warn(
                        "Failed to restore copycat material at {} for {}",
                        info.pos(), BuiltInRegistries.BLOCK.getKey(copycatBlock), error
                    );
                }
            }
        }
        if (restored > 0) {
            LOGGER.debug("Restored {} copycat material block entities at {}", restored, origin);
        }
    }

    private static void applyCopycatMaterial(
        ServerLevel level,
        BlockPos position,
        BlockState state,
        BlockEntity blockEntity,
        CompoundTag data
    ) {
        blockEntity.loadWithComponents(data.copy(), level.registryAccess());
        applySingleStateCopycatMaterial(
            blockEntity, data, level.holderLookup(Registries.BLOCK)
        );
        applyMultiStateCopycatMaterials(
            blockEntity, data, level.holderLookup(Registries.BLOCK)
        );
        blockEntity.setChanged();
        level.sendBlockUpdated(
            position, state, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
        );
        level.getChunkSource().blockChanged(position);
        // A structure can be placed after a player already started tracking its chunk.
        // Send the block-entity payload as well as the state update so Copycats+ rebuilds
        // the model data for both single-state copycats and top/bottom copycat slabs.
        var updatePacket = blockEntity.getUpdatePacket();
        if (updatePacket != null) {
            for (ServerPlayer player : level.players()) {
                player.connection.send(updatePacket);
            }
        }
    }

    /**
     * Copycats+' legacy single-state blocks store their appearance in {@code Material} rather
     * than {@code material_data}. Loading the tag updates the saved field, but it does not
     * consistently rebuild the rendered model after a structure is placed. Reapply the authored
     * state through Copycats+' public setter so half panels and the other single-state variants
     * take the same redraw path as a material applied by a player.
     */
    private static void applySingleStateCopycatMaterial(
        BlockEntity blockEntity,
        CompoundTag data,
        HolderGetter<Block> blocks
    ) {
        if (!data.contains("Material", Tag.TAG_COMPOUND)) {
            return;
        }
        Method setMaterial = COPYCAT_SINGLE_SET_MATERIAL_METHODS.computeIfAbsent(
            blockEntity.getClass(), StructurePlacementFixes::findSingleStateSetMaterialMethod
        );
        BlockState material = NbtUtils.readBlockState(blocks, data.getCompound("Material"));
        invokeCopycatMaterialSetter(
            setMaterial,
            blockEntity,
            new Object[] {material},
            "single-state"
        );
        // Copycats validates Material against Item whenever the chunk is loaded. A material-only
        // repair can therefore look correct until the chunk unloads and then revert to the X
        // textured copycat base. Persist the matching consumed item together with the material.
        Method setConsumedItem = COPYCAT_SINGLE_SET_ITEM_METHODS.computeIfAbsent(
            blockEntity.getClass(), StructurePlacementFixes::findSingleStateSetItemMethod
        );
        invokeCopycatMaterialSetter(
            setConsumedItem,
            blockEntity,
            new Object[] {new ItemStack(material.getBlock())},
            "single-state consumed-item"
        );
    }

    /**
     * Loading the raw block-entity tag is sufficient for storage, but Copycats+' multi-state
     * blocks (notably {@code copycat_slab}) also rebuild their client model through the public
     * {@code setMaterial(part, state)} path. Invoke that path for every authored top/bottom part
     * instead of treating the slab like Create's single-material copycat panel.
     */
    private static void applyMultiStateCopycatMaterials(
        BlockEntity blockEntity,
        CompoundTag data,
        HolderGetter<Block> blocks
    ) {
        if (!data.contains("material_data", Tag.TAG_COMPOUND)) {
            return;
        }
        Method setMaterial = COPYCAT_MULTI_SET_MATERIAL_METHODS.computeIfAbsent(
            blockEntity.getClass(), StructurePlacementFixes::findMultiStateSetMaterialMethod
        );
        CompoundTag materialData = data.getCompound("material_data");
        for (String part : materialData.getAllKeys()) {
            if (!materialData.contains(part, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag partData = materialData.getCompound(part);
            if (!partData.contains("material", Tag.TAG_COMPOUND)) {
                continue;
            }
            BlockState material = NbtUtils.readBlockState(
                blocks, partData.getCompound("material")
            );
            invokeCopycatMaterialSetter(
                setMaterial,
                blockEntity,
                new Object[] {part, material},
                "multi-state"
            );
        }
    }

    private static Method findSingleStateSetMaterialMethod(Class<?> blockEntityClass) {
        try {
            return blockEntityClass.getMethod("setMaterial", BlockState.class);
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(
                "Block entity contains Material but has no single-state material setter: "
                    + blockEntityClass.getName(),
                error
            );
        }
    }

    private static Method findSingleStateSetItemMethod(Class<?> blockEntityClass) {
        try {
            return blockEntityClass.getMethod("setConsumedItem", ItemStack.class);
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(
                "Block entity contains Material but has no single-state consumed-item setter: "
                    + blockEntityClass.getName(),
                error
            );
        }
    }

    private static Method findMultiStateSetMaterialMethod(Class<?> blockEntityClass) {
        try {
            return blockEntityClass.getMethod(
                "setMaterial", String.class, BlockState.class
            );
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(
                "Block entity contains material_data but has no multi-state material setter: "
                    + blockEntityClass.getName(),
                error
            );
        }
    }

    private static void invokeCopycatMaterialSetter(
        Method setter,
        BlockEntity blockEntity,
        Object[] arguments,
        String kind
    ) {
        try {
            setter.invoke(blockEntity, arguments);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(
                "Cannot access Copycats+ " + kind + " material setter", error
            );
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                "Copycats+ " + kind + " material setter failed", cause
            );
        }
    }

    /** Applies the same structure transform to materials stored inside copycat NBT. */
    private static CompoundTag transformCopycatMaterialData(
        CompoundTag sourceData,
        HolderGetter<Block> blocks,
        Mirror mirror,
        Rotation rotation
    ) {
        CompoundTag transformed = sourceData.copy();
        transformMaterialTag(transformed, "Material", blocks, mirror, rotation);

        if (transformed.contains("material_data", Tag.TAG_COMPOUND)) {
            CompoundTag materialData = transformed.getCompound("material_data");
            CompoundTag remappedMaterialData = new CompoundTag();
            for (String part : materialData.getAllKeys()) {
                if (!materialData.contains(part, Tag.TAG_COMPOUND)) {
                    remappedMaterialData.put(part, materialData.get(part).copy());
                    continue;
                }
                CompoundTag partData = materialData.getCompound(part).copy();
                transformMaterialTag(
                    partData, "material", blocks, mirror, rotation
                );
                remappedMaterialData.put(
                    transformCopycatPartKey(part, mirror, rotation), partData
                );
            }
            transformed.put("material_data", remappedMaterialData);
        }
        return transformed;
    }

    /**
     * Copycats+ stores multi-state materials under the same directional part names used by
     * the block state. StructureTemplate rotates the state, so those names must follow it.
     */
    static String transformCopycatPartKey(
        String part, Mirror mirror, Rotation rotation
    ) {
        int separator = part.lastIndexOf('_');
        if (separator < 0 || separator == part.length() - 1) {
            return part;
        }
        String quadrant = part.substring(separator + 1);
        Direction northSouth;
        Direction eastWest;
        if (quadrant.startsWith("north")) {
            northSouth = Direction.NORTH;
        } else if (quadrant.startsWith("south")) {
            northSouth = Direction.SOUTH;
        } else {
            return part;
        }
        if (quadrant.endsWith("east")) {
            eastWest = Direction.EAST;
        } else if (quadrant.endsWith("west")) {
            eastWest = Direction.WEST;
        } else {
            return part;
        }

        Direction first = rotation.rotate(mirror.mirror(northSouth));
        Direction second = rotation.rotate(mirror.mirror(eastWest));
        Direction transformedNorthSouth = first.getAxis() == Direction.Axis.Z
            ? first : second;
        Direction transformedEastWest = first.getAxis() == Direction.Axis.X
            ? first : second;
        return part.substring(0, separator + 1)
            + transformedNorthSouth.getName() + transformedEastWest.getName();
    }

    private static void transformMaterialTag(
        CompoundTag owner,
        String key,
        HolderGetter<Block> blocks,
        Mirror mirror,
        Rotation rotation
    ) {
        if (!owner.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        BlockState material = NbtUtils.readBlockState(blocks, owner.getCompound(key));
        owner.put(key, NbtUtils.writeBlockState(material.mirror(mirror).rotate(rotation)));
    }

    private static List<Block> copycatBlocks() {
        return BuiltInRegistries.BLOCK.entrySet().stream()
            .filter(entry -> isCopycatBlock(entry.getKey().location()))
            .map(Map.Entry::getValue)
            .toList();
    }

    private static void repairFridges(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockPos end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
        for (BlockPos cursor : BlockPos.betweenClosed(origin, end)) {
            BlockPos position = cursor.immutable();
            BlockState reference = level.getBlockState(position);
            ResourceLocation referenceId = BuiltInRegistries.BLOCK.getKey(reference.getBlock());
            ResourceLocation expectedId = FRIDGE_LOWER_BY_UPPER.get(referenceId);
            Direction direction = Direction.DOWN;
            if (expectedId == null) {
                expectedId = FRIDGE_UPPER_BY_LOWER.get(referenceId);
                direction = Direction.UP;
            }
            if (expectedId == null) {
                continue;
            }

            BlockPos counterpart = position.relative(direction);
            ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(counterpart).getBlock()
            );
            if (currentId.equals(expectedId) || !level.getBlockState(counterpart).isAir()) {
                continue;
            }

            BlockState repaired = matchingState(expectedId, reference);
            if (level.setBlock(counterpart, repaired, 3)) {
                LOGGER.info("Repaired structure fridge half at {} with {}", counterpart, expectedId);
            }
        }
    }

    private static BlockState matchingState(ResourceLocation blockId, BlockState reference) {
        BlockState state = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            && reference.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(
                BlockStateProperties.HORIZONTAL_FACING,
                reference.getValue(BlockStateProperties.HORIZONTAL_FACING)
            );
        }
        if (state.hasProperty(BlockStateProperties.OPEN)
            && reference.hasProperty(BlockStateProperties.OPEN)) {
            state = state.setValue(
                BlockStateProperties.OPEN,
                reference.getValue(BlockStateProperties.OPEN)
            );
        }
        return state;
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static boolean isCopycatBlock(ResourceLocation blockId) {
        return blockId.getNamespace().equals("copycats")
            || blockId.getNamespace().equals("create") && blockId.getPath().contains("copycat");
    }

    private enum ElevatorAssemblyResult {
        COMPLETE,
        RETRY,
        RETRY_TRIGGERED,
        ABORT
    }

    private record ElevatorPulleyKey(ResourceKey<Level> dimension, BlockPos position) {
    }

    private record ElevatorDoorKey(ResourceKey<Level> dimension, BlockPos contact) {
    }

    private record ElevatorLandingDoors(List<BlockPos> lowerDoors) {
    }

    private record ElevatorContactRange(int minY, int maxY) {
    }

    private record CopycatBlockKey(ResourceKey<Level> dimension, BlockPos position) {
    }

    private record CopycatChunkSyncKey(
        UUID playerId,
        ResourceKey<Level> dimension,
        long chunkKey
    ) {
    }

    private record PendingCopycatChunkSync(int remainingPasses, long nextSyncTick) {
    }

    private record PendingCopycatRestore(
        ResourceLocation blockId,
        CompoundTag data,
        int attempts,
        int remainingPasses,
        long nextAttemptTick
    ) {
        private PendingCopycatRestore withRetry(int newAttempts, long newAttemptTick) {
            return new PendingCopycatRestore(
                blockId, data, newAttempts, remainingPasses, newAttemptTick
            );
        }

        private PendingCopycatRestore withPass(int newRemainingPasses, long newAttemptTick) {
            return new PendingCopycatRestore(
                blockId, data, attempts, newRemainingPasses, newAttemptTick
            );
        }
    }

    private record PendingElevatorAssembly(
        int attempts,
        long nextAttemptTick,
        boolean triggered,
        int minContactY,
        int maxContactY
    ) {
    }
}
