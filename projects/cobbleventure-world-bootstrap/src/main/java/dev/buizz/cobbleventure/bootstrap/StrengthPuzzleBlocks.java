package dev.buizz.cobbleventure.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Server-authoritative blocks used by Strength gym puzzles. */
final class StrengthPuzzleBlocks {
    static final String NAMESPACE = CobbleventureBootstrap.MOD_ID;
    static final String BOULDER_ID = "strength_boulder";
    static final String PLATE_ID = "strength_plate";
    private static final String DATA_FILE = "cobbleventure_strength_boulders";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(NAMESPACE);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NAMESPACE);

    static final DeferredBlock<StrengthBoulderBlock> BOULDER = BLOCKS.register(
        BOULDER_ID,
        () -> new StrengthBoulderBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.DEEPSLATE)
            .pushReaction(PushReaction.BLOCK))
    );
    static final DeferredBlock<StrengthPlateBlock> PLATE = BLOCKS.register(
        PLATE_ID,
        () -> new StrengthPlateBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_GRAY)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.DEEPSLATE)
            .noOcclusion()
            .pushReaction(PushReaction.BLOCK))
    );
    static final DeferredItem<BlockItem> BOULDER_ITEM = ITEMS.register(
        BOULDER_ID, () -> new BlockItem(BOULDER.get(), new Item.Properties())
    );
    static final DeferredItem<BlockItem> PLATE_ITEM = ITEMS.register(
        PLATE_ID, () -> new BlockItem(PLATE.get(), new Item.Properties())
    );

    private StrengthPuzzleBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(StrengthPuzzleBlocks::addCreativeTabItems);
        NeoForge.EVENT_BUS.addListener(StrengthPuzzleBlocks::onRegisterCommands);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(BOULDER_ITEM);
            event.accept(PLATE_ITEM);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cobbleventure_strength")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("reset").executes(context -> {
                ServerLevel level = context.getSource().getLevel();
                int reset = data(level).reset(level);
                context.getSource().sendSuccess(
                    () -> Component.literal("[Cobbleventure] 괴력 바위 " + reset + "개를 초기 위치로 되돌렸습니다."),
                    true
                );
                return reset;
            })));
    }

    private static BoulderData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(BoulderData::new, BoulderData::load), DATA_FILE
        );
    }

    private static BlockPos corePosition(BlockPos position, BlockState state) {
        int part = state.getValue(StrengthBoulderBlock.PART);
        return position.offset(-(part & 1), -((part >> 2) & 1), -((part >> 1) & 1));
    }

    private static List<BlockPos> positions(BlockPos core) {
        List<BlockPos> positions = new ArrayList<>(8);
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    positions.add(core.offset(x, y, z));
                }
            }
        }
        return positions;
    }

    private static int partAt(BlockPos core, BlockPos position) {
        return position.getX() - core.getX()
            | (position.getZ() - core.getZ()) << 1
            | (position.getY() - core.getY()) << 2;
    }

    private static boolean isCompleteBoulder(LevelReader level, BlockPos core) {
        for (BlockPos position : positions(core)) {
            BlockState state = level.getBlockState(position);
            if (!state.is(BOULDER.get()) || state.getValue(StrengthBoulderBlock.PART) != partAt(core, position)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canOccupy(ServerLevel level, BlockPos destination, BlockPos current) {
        AABB target = new AABB(
            destination.getX(), destination.getY(), destination.getZ(),
            destination.getX() + 2.0D, destination.getY() + 2.0D, destination.getZ() + 2.0D
        );
        if (!level.getWorldBorder().isWithinBounds(target)) {
            return false;
        }
        List<BlockPos> currentPositions = positions(current);
        for (BlockPos position : positions(destination)) {
            if (!currentPositions.contains(position) && !level.getBlockState(position).canBeReplaced()) {
                return false;
            }
        }
        return level.getEntities((net.minecraft.world.entity.Entity) null, target, entity -> entity.isAlive()).isEmpty();
    }

    private static void writeBoulder(ServerLevel level, BlockPos oldCore, BlockPos newCore) {
        List<BlockPos> oldPositions = positions(oldCore);
        List<BlockPos> newPositions = positions(newCore);
        for (BlockPos position : newPositions) {
            level.setBlock(position, BOULDER.get().defaultBlockState()
                .setValue(StrengthBoulderBlock.PART, partAt(newCore, position)), Block.UPDATE_CLIENTS);
        }
        for (BlockPos position : oldPositions) {
            if (!newPositions.contains(position)) {
                level.removeBlock(position, false);
            }
        }
        for (BlockPos position : oldPositions) {
            notifyPlate(level, position.below());
        }
        for (BlockPos position : newPositions) {
            notifyPlate(level, position.below());
        }
    }

    private static void notifyPlate(ServerLevel level, BlockPos position) {
        Block block = level.getBlockState(position).getBlock();
        level.updateNeighborsAt(position, block);
        level.updateNeighborsAt(position.below(), block);
    }

    static final class StrengthBoulderBlock extends Block {
        static final IntegerProperty PART = IntegerProperty.create("part", 0, 7);

        StrengthBoulderBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(PART, 0));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(PART);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockPos core = context.getClickedPos();
            for (BlockPos position : positions(core)) {
                if (!context.getLevel().getBlockState(position).canBeReplaced()) {
                    return null;
                }
            }
            return defaultBlockState();
        }

        @Override
        public void setPlacedBy(
            Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack
        ) {
            super.setPlacedBy(level, position, state, placer, stack);
            if (level.isClientSide()) {
                return;
            }
            ServerLevel serverLevel = (ServerLevel) level;
            for (BlockPos partPosition : positions(position)) {
                serverLevel.setBlock(partPosition, defaultBlockState()
                    .setValue(PART, partAt(position, partPosition)), Block.UPDATE_CLIENTS);
            }
            data(serverLevel).register(position);
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos position, BlockState state, Player player) {
            if (!level.isClientSide() && player.isCreative()) {
                ServerLevel serverLevel = (ServerLevel) level;
                BlockPos core = corePosition(position, state);
                for (BlockPos partPosition : positions(core)) {
                    if (serverLevel.getBlockState(partPosition).is(this)) {
                        serverLevel.removeBlock(partPosition, false);
                    }
                }
                data(serverLevel).remove(core);
            }
            return super.playerWillDestroy(level, position, state, player);
        }

        @Override
        protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit
        ) {
            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }
            ServerPlayer serverPlayer = (ServerPlayer) player;
            if (!FieldMoveRidingAccess.isActive(serverPlayer, "strength")) {
                serverPlayer.displayClientMessage(Component.literal("괴력을 보유하고 ON으로 설정해야 바위를 밀 수 있습니다."), true);
                return InteractionResult.CONSUME;
            }
            ServerLevel serverLevel = (ServerLevel) level;
            BlockPos core = corePosition(position, state);
            if (!isCompleteBoulder(serverLevel, core)) {
                serverPlayer.displayClientMessage(Component.literal("괴력 바위가 손상되어 움직일 수 없습니다."), true);
                return InteractionResult.CONSUME;
            }
            Direction direction = serverPlayer.getDirection();
            BlockPos destination = core.relative(direction);
            if (!canOccupy(serverLevel, destination, core)) {
                serverPlayer.displayClientMessage(Component.literal("그 방향으로는 바위를 밀 수 없습니다."), true);
                return InteractionResult.CONSUME;
            }
            BoulderData data = data(serverLevel);
            data.register(core);
            writeBoulder(serverLevel, core, destination);
            data.move(core, destination);
            serverLevel.playSound(null, core, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.7F, 0.65F);
            return InteractionResult.CONSUME;
        }
    }

    static final class StrengthPlateBlock extends Block {
        private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 2.0D, 15.0D);

        StrengthPlateBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos position, CollisionContext context
        ) {
            return SHAPE;
        }

        @Override
        protected boolean isSignalSource(BlockState state) {
            return true;
        }

        @Override
        protected int getSignal(BlockState state, BlockGetter level, BlockPos position, Direction direction) {
            return level.getBlockState(position.above()).is(BOULDER.get()) ? 15 : 0;
        }

        @Override
        protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos position, Direction direction) {
            return direction == Direction.UP ? getSignal(state, level, position, direction) : 0;
        }
    }

    private static final class BoulderData extends SavedData {
        private final Map<Long, Long> originsByCurrent = new LinkedHashMap<>();

        static BoulderData load(CompoundTag tag, HolderLookup.Provider registries) {
            BoulderData data = new BoulderData();
            ListTag entries = tag.getList("boulders", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                data.originsByCurrent.put(entry.getLong("current"), entry.getLong("origin"));
            }
            return data;
        }

        void register(BlockPos core) {
            if (!originsByCurrent.containsKey(core.asLong())) {
                originsByCurrent.put(core.asLong(), core.asLong());
                setDirty();
            }
        }

        void move(BlockPos current, BlockPos destination) {
            long origin = originsByCurrent.getOrDefault(current.asLong(), current.asLong());
            originsByCurrent.remove(current.asLong());
            originsByCurrent.put(destination.asLong(), origin);
            setDirty();
        }

        void remove(BlockPos core) {
            if (originsByCurrent.remove(core.asLong()) != null) {
                setDirty();
            }
        }

        int reset(ServerLevel level) {
            int reset = 0;
            Map<Long, Long> updated = new LinkedHashMap<>();
            for (Map.Entry<Long, Long> entry : originsByCurrent.entrySet()) {
                BlockPos current = BlockPos.of(entry.getKey());
                BlockPos origin = BlockPos.of(entry.getValue());
                if (!level.hasChunkAt(current) || !level.hasChunkAt(origin)
                    || !isCompleteBoulder(level, current)) {
                    updated.put(entry.getKey(), entry.getValue());
                    continue;
                }
                if (!current.equals(origin) && !canOccupy(level, origin, current)) {
                    updated.put(entry.getKey(), entry.getValue());
                    continue;
                }
                if (!current.equals(origin)) {
                    writeBoulder(level, current, origin);
                    reset++;
                }
                updated.put(origin.asLong(), origin.asLong());
            }
            originsByCurrent.clear();
            originsByCurrent.putAll(updated);
            setDirty();
            return reset;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag entries = new ListTag();
            for (Map.Entry<Long, Long> boulder : originsByCurrent.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putLong("current", boulder.getKey());
                entry.putLong("origin", boulder.getValue());
                entries.add(entry);
            }
            tag.put("boulders", entries);
            return tag;
        }
    }
}
