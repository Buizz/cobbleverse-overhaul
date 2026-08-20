package dev.buizz.cobbleventure.adventure.event.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.adventure.event.EventDialogueNetwork;
import dev.buizz.cobbleventure.adventure.event.EventPresentationGateway;
import dev.buizz.cobbleventure.adventure.event.EventRuntimeException;
import dev.buizz.cobbleventure.adventure.event.EventTextRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only text localization and screen entry point. */
public final class EventDialogueClient {
    private static boolean inputHooksRegistered;
    private static boolean awaitInputLocked;
    private static String awaitKind = "";

    private EventDialogueClient() {}

    public static void open(EventDialogueNetwork.OpenPayload payload) {
        JsonElement text = JsonParser.parseString(payload.textJson());
        Map<String, JsonElement> locals = locals(payload.localsJson());
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new EventDialogueScreen(payload, render(text, locals, minecraft)));
    }

    public static void openChoice(EventDialogueNetwork.ChoiceOpenPayload payload) {
        JsonElement prompt = JsonParser.parseString(payload.promptJson());
        JsonArray optionValues = JsonParser.parseString(payload.optionsJson()).getAsJsonArray();
        Map<String, JsonElement> locals = locals(payload.localsJson());
        Minecraft minecraft = Minecraft.getInstance();
        String renderedPrompt = render(prompt, locals, minecraft);
        java.util.List<String> renderedOptions = new java.util.ArrayList<>();
        for (JsonElement option : optionValues) {
            renderedOptions.add(render(option, locals, minecraft));
        }
        minecraft.setScreen(new EventChoiceScreen(payload, renderedPrompt, renderedOptions));
    }

    public static void setAwaitInputLocked(String kind, boolean locked) {
        ensureInputHooksRegistered();
        awaitInputLocked = locked;
        awaitKind = locked && kind != null ? kind : "";
        Minecraft minecraft = Minecraft.getInstance();
        if (locked) {
            if (minecraft.screen == null || !isAuthoredScreen(minecraft.screen, awaitKind)) {
                minecraft.setScreen(new EventMovementLockScreen());
            }
        } else if (minecraft.screen instanceof EventMovementLockScreen) {
            minecraft.setScreen(null);
        }
    }

    private static void ensureInputHooksRegistered() {
        if (inputHooksRegistered) return;
        inputHooksRegistered = true;
        NeoForge.EVENT_BUS.addListener(EventDialogueClient::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(EventDialogueClient::onClientTick);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (Minecraft.getInstance().player == null) {
            awaitInputLocked = false;
            awaitKind = "";
            return;
        }
        if (!awaitInputLocked || event.getNewScreen() == null
            || isAuthoredScreen(event.getNewScreen(), awaitKind)) {
            return;
        }
        event.setCanceled(true);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!awaitInputLocked) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            awaitInputLocked = false;
            awaitKind = "";
        } else if (minecraft.screen == null) {
            minecraft.setScreen(new EventMovementLockScreen());
        }
    }

    private static boolean isAuthoredScreen(Screen screen, String kind) {
        if (screen instanceof EventMovementLockScreen
            || screen instanceof EventDialogueScreen
            || screen instanceof EventChoiceScreen
            || screen instanceof EventFadeScreen) {
            return true;
        }
        String className = screen.getClass().getName();
        if (className.equals(
            "dev.buizz.cobbleventure.playermenu.client.StarterRouletteScreen"
        )) {
            return kind.equals("starter_roulette") || kind.equals("transition");
        }
        if (className.equals(
            "dev.buizz.cobbleventure.playermenu.client.WorldMapScreen"
        )) {
            return kind.equals("map_selection") || kind.equals("transition");
        }
        return (kind.equals("battle") || kind.equals("transition"))
            && className.toLowerCase(java.util.Locale.ROOT).contains("battle");
    }

    public static void setFade(
        EventPresentationGateway.FadeColor color, boolean visible
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (visible) {
            minecraft.setScreen(new EventFadeScreen(color));
        } else if (minecraft.screen instanceof EventFadeScreen) {
            minecraft.setScreen(null);
        }
    }

    private static Map<String, JsonElement> locals(String localsJson) {
        JsonObject localValues = JsonParser.parseString(localsJson).getAsJsonObject();
        Map<String, com.google.gson.JsonElement> locals = new LinkedHashMap<>();
        localValues.entrySet().forEach(
            entry -> locals.put(entry.getKey(), entry.getValue().deepCopy())
        );
        return locals;
    }

    private static String render(
        JsonElement text, Map<String, JsonElement> locals, Minecraft minecraft
    ) {
        String language = minecraft.getLanguageManager().getSelected();
        EventTextRenderer renderer = new EventTextRenderer(EventDialogueClient::resourceName);
        try {
            return renderer.render(text, locals, language);
        } catch (EventRuntimeException error) {
            return "[CVES dialogue error] " + error.getMessage();
        }
    }

    private static String resourceName(String resourceId, String language) {
        ResourceLocation id = ResourceLocation.tryParse(resourceId);
        if (id == null) return resourceId;
        Species species = PokemonSpecies.getByIdentifier(id);
        if (species != null) return species.getTranslatedName().getString();
        String authoredName = EventResourceNameCatalog.defaults().resolve(resourceId, language);
        if (authoredName != null) return authoredName;
        return BuiltInRegistries.ITEM.getOptional(id)
            .map(item -> item.getDescription().getString())
            .orElse(resourceId);
    }
}
