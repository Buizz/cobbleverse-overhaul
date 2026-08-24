package dev.buizz.cobbleventure.casino;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoItems {
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(CobbleventureCasino.MOD_ID);
    public static final DeferredItem<Item> COIN_CASE = ITEMS.register(
        "coin_case", () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
    );
    public static final DeferredItem<Item> GACHA_TICKET = ITEMS.register(
        "gacha_ticket", () -> new Item(new Item.Properties().stacksTo(64).rarity(Rarity.UNCOMMON))
    );
    private CasinoItems() {}
    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener(CasinoItems::addToTab);
    }
    private static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(COIN_CASE);
            event.accept(GACHA_TICKET);
        }
    }
}
