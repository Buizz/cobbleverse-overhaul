package dev.buizz.cobbleventure.casino.client;

import dev.buizz.cobbleventure.casino.CasinoHudNetwork;
import dev.buizz.cobbleventure.casino.CobbleventureCasino;
import fr.harmex.cobbledollars.common.client.gui.CobbleDollarsOverlay;
import fr.harmex.cobbledollars.common.client.utils.Context;
import fr.harmex.cobbledollars.common.client.utils.GuiUtilsKt;
import java.math.BigInteger;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** CobbleDollars-styled casino balance placed at the top-right of the HUD. */
public final class CasinoBalanceOverlay {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(
        CobbleventureCasino.MOD_ID, "casino_balance"
    );
    private static final ResourceLocation GOLD_CHIP = ResourceLocation.fromNamespaceAndPath(
        "cobblemoncasino", "textures/item/gold_chip.png"
    );
    private static final int WIDTH = 54;

    private CasinoBalanceOverlay() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(CasinoBalanceOverlay::registerLayer);
    }

    private static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER, CasinoBalanceOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker ignored) {
        if (!CasinoHudNetwork.clientVisible()
            || !CobbleDollarsOverlay.Companion.canRender()) return;

        int x = graphics.guiWidth() - 4 - WIDTH;
        int y = 4;
        GuiUtilsKt.renderCobbleDollarsElement(
            graphics, x, y, true,
            BigInteger.valueOf(CasinoHudNetwork.clientBalance()),
            false, Context.BANK, 0xFFFFFF
        );

        // The dollar symbol is baked into the source background. Clear just its inner
        // 12x12 area, retaining CobbleDollars' border and purple accent, then draw a chip.
        graphics.fill(x + 36, y + 1, x + 48, y + 13, 0xFF2F2F2F);
        graphics.blit(GOLD_CHIP, x + 36, y + 1, 0.0F, 0.0F, 12, 12, 16, 16);
    }
}
