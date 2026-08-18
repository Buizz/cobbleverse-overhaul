package dev.buizz.cobbleventure.bootstrap.client;

import dev.buizz.cobbleventure.bootstrap.CobbleventureBootstrap;
import dev.buizz.cobbleventure.bootstrap.GateDialogueNetwork;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Reports EasyNPC dialogue screen transitions without a hard mod dependency. */
@EventBusSubscriber(modid = CobbleventureBootstrap.MOD_ID, value = Dist.CLIENT)
public final class GateDialogueScreenTracker {
    private static boolean dialogueOpen;

    private GateDialogueScreenTracker() {}

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            dialogueOpen = false;
            return;
        }
        boolean current = isEasyNpcDialogue(minecraft.screen);
        if (current == dialogueOpen) {
            return;
        }
        dialogueOpen = current;
        GateDialogueNetwork.sendState(current);
    }

    private static boolean isEasyNpcDialogue(Screen screen) {
        if (screen == null) {
            return false;
        }
        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);
        // A gate denial is the only time the server consumes this state, so all
        // EasyNPC-owned screens are accepted even if a release renames its
        // concrete dialogue class.
        return className.contains("easynpc")
            || (className.contains("easy") && className.contains("npc"));
    }
}
