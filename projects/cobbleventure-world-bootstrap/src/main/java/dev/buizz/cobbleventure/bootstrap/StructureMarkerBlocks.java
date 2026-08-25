package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Authoring-only structure markers that are resolved during NBT placement. */
final class StructureMarkerBlocks {
    static final String EXCAVATION_MARKER_ID = "excavation_marker";

    private static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(CobbleventureBootstrap.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CobbleventureBootstrap.MOD_ID);

    static final DeferredBlock<Block> EXCAVATION_MARKER = BLOCKS.register(
        EXCAVATION_MARKER_ID,
        () -> new ExcavationMarkerBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_LIGHT_BLUE)
            .strength(0.2F)
            .sound(SoundType.GLASS)
            .noCollission()
            .noOcclusion())
    );
    private static final DeferredItem<BlockItem> EXCAVATION_MARKER_ITEM = ITEMS.register(
        EXCAVATION_MARKER_ID,
        () -> new BlockItem(EXCAVATION_MARKER.get(), new Item.Properties())
    );

    private StructureMarkerBlocks() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(StructureMarkerBlocks::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.OP_BLOCKS) {
            event.accept(EXCAVATION_MARKER_ITEM);
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
