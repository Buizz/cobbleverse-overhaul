package dev.buizz.cobbleventure.pokefinder;

import dev.buizz.cobbleventure.playermenu.BagApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Resolves PokéNav ownership from this server's inventory and extended bag. */
final class PokenavAccess {
    private PokenavAccess() {}

    static boolean hasPokenav(ServerPlayer player) {
        for (Item item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (isPokenav(id)
                && BagApi.count(player, item) > 0) {
                return true;
            }
        }
        return false;
    }

    static boolean isPokenav(ResourceLocation id) {
        return id.getNamespace().equals("cobblenav")
            && id.getPath().startsWith("pokenav_item_");
    }
}
