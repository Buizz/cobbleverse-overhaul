package dev.buizz.cobbleventure.themeblocks;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CobbleventureThemeBlocks.MOD_ID)
public final class CobbleventureThemeBlocks {
    public static final String MOD_ID = "cobbleventure_theme_blocks";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final List<DeferredItem<BlockItem>> CREATIVE_ITEMS = new ArrayList<>();

    static {
        registerStoneBlock("underground_light_tiles", MapColor.SAND);
        registerDirectionalStoneBlock("underground_blue_band", MapColor.COLOR_LIGHT_BLUE);
        registerStoneBlock("underground_pale_wall", MapColor.COLOR_LIGHT_GREEN);
        registerDirectionalStoneBlock("underground_cracked_wall", MapColor.COLOR_LIGHT_GREEN);
        registerFixedDirectionalWoodFloor("underground_olive_band", MapColor.TERRACOTTA_YELLOW);

        registerStoneBlock("pokemon_tower_green_mosaic", MapColor.COLOR_GREEN);
        registerStoneBlock("pokemon_tower_purple_plinth", MapColor.COLOR_PURPLE);
        registerStoneBlock("pokemon_tower_purple_pillar", MapColor.COLOR_PURPLE);
        registerStoneBlock("pokemon_tower_purple_cornice", MapColor.COLOR_PURPLE);
        registerPokemonTowerGrave();

        registerDirectionalStoneBlock("rocket_base_olive_vent", MapColor.COLOR_LIGHT_GREEN);
        registerDirectionalStoneBlock("rocket_base_yellow_light_panel", MapColor.COLOR_YELLOW);
        registerStoneBlock("rocket_base_cyan_conduit", MapColor.COLOR_LIGHT_BLUE);
        registerStoneBlock("rocket_base_blue_wall", MapColor.COLOR_LIGHT_BLUE);
        registerStoneBlock("rocket_base_blue_band", MapColor.COLOR_LIGHT_BLUE);

        registerStoneBlock("casino_gold_diamond_tiles", MapColor.GOLD);
        registerDirectionalStoneBlock("casino_coral_band", MapColor.COLOR_ORANGE);
        registerStoneBlock("casino_sky_wall", MapColor.COLOR_LIGHT_BLUE);
        registerDirectionalStoneBlock("casino_sky_chevron_wall", MapColor.COLOR_LIGHT_BLUE);

        registerFixedDirectionalWoodFloor("house_beige_panel_wall", MapColor.SAND);
        registerDirectionalStoneBlock("house_mint_band_wall", MapColor.COLOR_LIGHT_GREEN);
        registerDirectionalStoneBlock("house_blue_band_wall", MapColor.COLOR_LIGHT_BLUE);
        registerStoneBlock("house_cream_base_wall", MapColor.SAND);
        registerStoneBlock("soft_cream_block", MapColor.SAND);
        registerDoubleDisplayCase();
        registerDoubleGlassDisplayCounter();
        registerRocketBaseMachines();
        registerResearchDevices();
        registerBookshelves();
        registerFurniture();
        registerLargeBed();
        registerGlowWindows();
    }

    public CobbleventureThemeBlocks(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(CobbleventureThemeBlocks::addCreativeTabItems);
    }

    private static void registerStoneBlock(String name, MapColor mapColor) {
        DeferredBlock<Block> block = BLOCKS.register(
            name,
            () -> new Block(BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(1.5F, 6.0F)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            name,
            () -> new BlockItem(block.get(), new Item.Properties())
        ));
    }

    private static void registerDirectionalStoneBlock(String name, MapColor mapColor) {
        DeferredBlock<DirectionalStoneBlock> block = BLOCKS.register(
            name,
            () -> new DirectionalStoneBlock(BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(1.5F, 6.0F)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            name,
            () -> new BlockItem(block.get(), new Item.Properties())
        ));
    }

    private static void registerFixedDirectionalWoodFloor(String name, MapColor mapColor) {
        DeferredBlock<FixedDirectionalFloorBlock> block = BLOCKS.register(
            name,
            () -> new FixedDirectionalFloorBlock(BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(2.0F, 3.0F)
                .sound(SoundType.WOOD))
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            name,
            () -> new BlockItem(block.get(), new Item.Properties())
        ));
    }

    private static void registerPokemonTowerGrave() {
        DeferredBlock<PokemonTowerGraveBlock> block = BLOCKS.register(
            "pokemon_tower_grave",
            () -> new PokemonTowerGraveBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(1.5F, 6.0F)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops()
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "pokemon_tower_grave",
            () -> new BlockItem(block.get(), new Item.Properties())
        ));
    }

    private static void registerDoubleDisplayCase() {
        DeferredBlock<DoubleDisplayCaseBlock> block = BLOCKS.register(
            "double_display_case",
            () -> new DoubleDisplayCaseBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLUE)
                .strength(2.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops()
                .pushReaction(PushReaction.BLOCK)
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "double_display_case",
            () -> new BlockItem(block.get(), new Item.Properties())
        ));
    }

    private static void registerDoubleGlassDisplayCounter() {
        DeferredBlock<DoubleGlassDisplayCounterBlock> block = BLOCKS.register(
            "double_glass_display_counter",
            () -> new DoubleGlassDisplayCounterBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .strength(2.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops()
                .pushReaction(PushReaction.BLOCK)
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "double_glass_display_counter",
            () -> new BlockItem(block.get(), new Item.Properties())
        ));
    }

    private static void registerRocketBaseMachines() {
        DeferredBlock<RocketBaseMachineOneBlock> machineOne = BLOCKS.register(
            "rocket_base_machine_1",
            () -> new RocketBaseMachineOneBlock(machineProperties())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "rocket_base_machine_1",
            () -> new BlockItem(machineOne.get(), new Item.Properties())
        ));

        DeferredBlock<RocketBaseMachineTwoBlock> machineTwo = BLOCKS.register(
            "rocket_base_machine_2",
            () -> new RocketBaseMachineTwoBlock(machineProperties())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "rocket_base_machine_2",
            () -> new BlockItem(machineTwo.get(), new Item.Properties())
        ));

        DeferredBlock<RocketBaseMachineThreeBlock> machineThree = BLOCKS.register(
            "rocket_base_machine_3",
            () -> new RocketBaseMachineThreeBlock(machineProperties())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "rocket_base_machine_3",
            () -> new BlockItem(machineThree.get(), new Item.Properties())
        ));
    }

    private static BlockBehaviour.Properties machineProperties() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(2.5F, 8.0F)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
            .pushReaction(PushReaction.BLOCK)
            .noOcclusion();
    }

    private static void registerResearchDevices() {
        DeferredBlock<ResearchDeviceBlock> deviceOne = BLOCKS.register(
            "research_device_1",
            () -> new ResearchDeviceBlock(machineProperties())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "research_device_1",
            () -> new BlockItem(deviceOne.get(), new Item.Properties())
        ));
    }

    private static void registerBookshelves() {
        registerBookshelf("white_connecting_bookshelf", MapColor.COLOR_LIGHT_BLUE);
        registerBookshelf("green_connecting_bookshelf", MapColor.COLOR_LIGHT_GREEN);
    }

    private static void registerBookshelf(String name, MapColor mapColor) {
        DeferredBlock<WideTallBookshelfBlock> bookshelf = BLOCKS.register(
            name,
            () -> new WideTallBookshelfBlock(BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(2.0F, 6.0F)
                .sound(SoundType.METAL)
                .pushReaction(PushReaction.BLOCK)
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            name,
            () -> new BlockItem(bookshelf.get(), new Item.Properties())
        ));
    }

    private static void registerFurniture() {
        DeferredBlock<WideTallFurnitureBlock> glassCabinet = BLOCKS.register(
            "glass_storage_cabinet",
            () -> new WideTallFurnitureBlock(furnitureProperties(MapColor.COLOR_LIGHT_BLUE))
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "glass_storage_cabinet",
            () -> new BlockItem(glassCabinet.get(), new Item.Properties())
        ));
        registerDirectionalFurnitureBlock(
            "narrow_drawer_cabinet",
            MapColor.COLOR_YELLOW
        );
    }

    private static void registerDirectionalFurnitureBlock(String name, MapColor mapColor) {
        DeferredBlock<DirectionalStoneBlock> furniture = BLOCKS.register(
            name,
            () -> new DirectionalStoneBlock(furnitureProperties(mapColor))
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            name,
            () -> new BlockItem(furniture.get(), new Item.Properties())
        ));
    }

    private static BlockBehaviour.Properties furnitureProperties(MapColor mapColor) {
        return BlockBehaviour.Properties.of()
            .mapColor(mapColor)
            .strength(2.0F, 6.0F)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
            .pushReaction(PushReaction.BLOCK)
            .noOcclusion();
    }

    private static void registerLargeBed() {
        DeferredBlock<LargeBedBlock> bed = BLOCKS.register(
            "large_bed",
            () -> new LargeBedBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .strength(1.0F, 3.0F)
                .sound(SoundType.WOOD)
                .pushReaction(PushReaction.BLOCK)
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "large_bed",
            () -> new BlockItem(bed.get(), new Item.Properties())
        ));
    }

    private static void registerGlowWindows() {
        registerGlowWindow(
            "sky_view_glow_window",
            MapColor.COLOR_LIGHT_BLUE
        );
        registerGlowWindow(
            "blue_panel_glow_window",
            MapColor.COLOR_BLUE
        );
        registerDoubleGlowWindow();
    }

    private static void registerGlowWindow(String name, MapColor mapColor) {
        DeferredBlock<GlowWindowBlock> window = BLOCKS.register(
            name,
            () -> new GlowWindowBlock(BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(1.5F, 6.0F)
                .sound(SoundType.GLASS)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> 8)
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            name,
            () -> new BlockItem(window.get(), new Item.Properties())
        ));
    }

    private static void registerDoubleGlowWindow() {
        DeferredBlock<DoubleGlowWindowBlock> window = BLOCKS.register(
            "bright_double_glow_window",
            () -> new DoubleGlowWindowBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .strength(1.5F, 6.0F)
                .sound(SoundType.GLASS)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> 8)
                .noOcclusion())
        );
        CREATIVE_ITEMS.add(ITEMS.register(
            "bright_double_glow_window",
            () -> new BlockItem(window.get(), new Item.Properties())
        ));
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            CREATIVE_ITEMS.forEach(event::accept);
        }
    }
}
