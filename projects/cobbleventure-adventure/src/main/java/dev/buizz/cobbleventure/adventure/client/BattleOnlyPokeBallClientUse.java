package dev.buizz.cobbleventure.adventure.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.item.PokeBallItem;
import dev.buizz.cobbleventure.adventure.CobbleventureAdventure;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Cancels the local use prediction before a Poké Ball stack can visually shrink. */
@EventBusSubscriber(modid = CobbleventureAdventure.MOD_ID, value = Dist.CLIENT)
public final class BattleOnlyPokeBallClientUse {
    private BattleOnlyPokeBallClientUse() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getItemStack().getItem() instanceof PokeBallItem)
            || CobblemonClient.INSTANCE.getBattle() != null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }
}
