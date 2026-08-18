package dev.buizz.cobbleventure.adventure;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Prevents players from damaging or knocking each other back. */
final class PlayerVersusPlayerProtection {
    private PlayerVersusPlayerProtection() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(PlayerVersusPlayerProtection::onIncomingDamage);
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Player
            && event.getSource().getEntity() instanceof Player) {
            event.setCanceled(true);
        }
    }
}
