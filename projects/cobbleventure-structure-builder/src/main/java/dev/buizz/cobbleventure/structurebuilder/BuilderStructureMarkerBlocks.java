package dev.buizz.cobbleventure.structurebuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Builder-pack definition of the production excavation marker block ID. */
final class BuilderStructureMarkerBlocks {
    private static final String GAMEPLAY_NAMESPACE = "cobbleventure_bootstrap";
    private static final String MARKER_ID = "excavation_marker";
    private static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(GAMEPLAY_NAMESPACE);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(GAMEPLAY_NAMESPACE);

    private static final DeferredBlock<Block> MARKER = BLOCKS.register(
        MARKER_ID,
        () -> new ExcavationMarkerBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_LIGHT_BLUE)
            .strength(0.2F)
            .sound(SoundType.GLASS)
            .noCollission()
            .noOcclusion())
    );
    static final DeferredItem<BlockItem> MARKER_ITEM = ITEMS.register(
        MARKER_ID, () -> new BlockItem(MARKER.get(), new Item.Properties())
    );

    private BuilderStructureMarkerBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(BuilderStructureMarkerBlocks::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.OP_BLOCKS
            || event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(MARKER_ITEM);
        }
    }

    private static final class ExcavationMarkerBlock extends Block {
        private static final VoxelShape SHAPE = Block.box(5.0D, 5.0D, 5.0D, 11.0D, 11.0D, 11.0D);

        private ExcavationMarkerBlock(Properties properties) {
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
