package dev.buizz.cobbleventure.structurebuilder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Builder-only definitions for the production puzzle block IDs. The builder
 * pack and gameplay bootstrap are intentionally not loaded at the same time.
 */
final class BuilderStrengthBlocks {
    private static final String GAMEPLAY_NAMESPACE = "cobbleventure_bootstrap";
    private static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(GAMEPLAY_NAMESPACE);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(GAMEPLAY_NAMESPACE);

    private static final DeferredBlock<BuilderBoulderBlock> BOULDER = BLOCKS.register(
        "strength_boulder",
        () -> new BuilderBoulderBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.DEEPSLATE)
            .pushReaction(PushReaction.BLOCK))
    );
    private static final DeferredBlock<BuilderPlateBlock> PLATE = BLOCKS.register(
        "strength_plate",
        () -> new BuilderPlateBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_GRAY)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.DEEPSLATE)
            .noOcclusion()
            .pushReaction(PushReaction.BLOCK))
    );
    private static final DeferredBlock<Block> ROCK_SMASH_ROCK = BLOCKS.register(
        "rock_smash_rock",
        () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(-1.0F, 3_600_000.0F)
            .sound(SoundType.STONE)
            .pushReaction(PushReaction.BLOCK))
    );
    private static final DeferredItem<BlockItem> BOULDER_ITEM = ITEMS.register(
        "strength_boulder", () -> new BlockItem(BOULDER.get(), new Item.Properties())
    );
    private static final DeferredItem<BlockItem> PLATE_ITEM = ITEMS.register(
        "strength_plate", () -> new BlockItem(PLATE.get(), new Item.Properties())
    );
    private static final DeferredItem<BlockItem> ROCK_SMASH_ROCK_ITEM = ITEMS.register(
        "rock_smash_rock", () -> new BlockItem(ROCK_SMASH_ROCK.get(), new Item.Properties())
    );

    private BuilderStrengthBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(BuilderStrengthBlocks::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(BOULDER_ITEM);
            event.accept(PLATE_ITEM);
            event.accept(ROCK_SMASH_ROCK_ITEM);
        }
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

    private static BlockPos corePosition(BlockPos position, BlockState state) {
        int part = state.getValue(BuilderBoulderBlock.PART);
        return position.offset(-(part & 1), -((part >> 2) & 1), -((part >> 1) & 1));
    }

    private static final class BuilderBoulderBlock extends Block {
        private static final IntegerProperty PART = IntegerProperty.create("part", 0, 7);

        private BuilderBoulderBlock(Properties properties) {
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
            for (BlockPos partPosition : positions(position)) {
                level.setBlock(partPosition, defaultBlockState()
                    .setValue(PART, partAt(position, partPosition)), Block.UPDATE_CLIENTS);
            }
        }

        @Override
        public BlockState playerWillDestroy(Level level, BlockPos position, BlockState state, Player player) {
            if (!level.isClientSide() && player.isCreative()) {
                BlockPos core = corePosition(position, state);
                for (BlockPos partPosition : positions(core)) {
                    if (level.getBlockState(partPosition).is(this)) {
                        level.removeBlock(partPosition, false);
                    }
                }
            }
            return super.playerWillDestroy(level, position, state, player);
        }
    }

    private static final class BuilderPlateBlock extends Block {
        private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 2.0D, 15.0D);

        private BuilderPlateBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos position, CollisionContext context
        ) {
            return SHAPE;
        }
    }
}
