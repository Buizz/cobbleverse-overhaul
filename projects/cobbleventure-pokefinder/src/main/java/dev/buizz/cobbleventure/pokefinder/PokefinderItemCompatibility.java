package dev.buizz.cobbleventure.pokefinder;

import com.metacontent.cobblenav.item.Pokefinder;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Keeps the removed standalone item compatible with old worlds without exposing it to players. */
final class PokefinderItemCompatibility {
    private static final ResourceLocation POKENAV =
        ResourceLocation.fromNamespaceAndPath("cobblenav", "pokenav_item_base");

    private PokefinderItemCompatibility() {}

    static void hideCreativeEntries(BuildCreativeModeTabContentsEvent event) {
        for (ItemStack stack : List.copyOf(event.getParentEntries())) {
            if (stack.getItem() instanceof Pokefinder) {
                event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
            }
        }
        for (ItemStack stack : List.copyOf(event.getSearchEntries())) {
            if (stack.getItem() instanceof Pokefinder) {
                event.remove(stack, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
            }
        }
    }

    static void migrateLegacyInventory(PlayerEvent.PlayerLoggedInEvent event) {
        Item replacement = BuiltInRegistries.ITEM.getOptional(POKENAV).orElse(Items.AIR);
        if (replacement == Items.AIR) return;

        var inventory = event.getEntity().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof Pokefinder)) continue;
            ItemStack migrated = new ItemStack(replacement, stack.getCount());
            inventory.setItem(slot, migrated);
        }
    }
}
