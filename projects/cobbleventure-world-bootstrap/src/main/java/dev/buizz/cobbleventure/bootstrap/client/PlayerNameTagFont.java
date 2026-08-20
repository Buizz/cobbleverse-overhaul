package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/** Keeps multiplayer player nameplates legible with the vanilla Unicode font. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class PlayerNameTagFont {
    private static final ResourceLocation WORLD_NAME_FONT =
        ResourceLocation.withDefaultNamespace("uniform");

    private PlayerNameTagFont() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        event.setContent(
            event.getContent().copy().withStyle(style -> style.withFont(WORLD_NAME_FONT))
        );
    }
}
