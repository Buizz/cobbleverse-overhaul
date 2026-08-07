package dev.buizz.cobbleventure.playermenu;

import dev.buizz.cobbleventure.playermenu.client.PlayerMenuClient;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

final class PlayerMenuItems {
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CobbleventurePlayerMenu.MOD_ID);

    static final DeferredItem<Item> WORLD_MAP = ITEMS.register(
        "world_map",
        () -> new WorldMapItem(new Item.Properties().stacksTo(1))
    );

    private PlayerMenuItems() {}

    static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(PlayerMenuItems::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(WORLD_MAP);
        }
    }

    private static final class WorldMapItem extends Item {
        private WorldMapItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level.isClientSide()) {
                PlayerMenuClient.openWorldMap();
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
    }
}
