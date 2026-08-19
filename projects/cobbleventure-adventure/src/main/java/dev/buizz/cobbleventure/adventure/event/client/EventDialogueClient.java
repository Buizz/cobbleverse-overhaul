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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Client-only text localization and screen entry point. */
public final class EventDialogueClient {
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

    public static void setMovementInputLocked(boolean locked) {
        Minecraft minecraft = Minecraft.getInstance();
        if (locked) {
            if (!(minecraft.screen instanceof EventMovementLockScreen)) {
                minecraft.setScreen(new EventMovementLockScreen());
            }
        } else if (minecraft.screen instanceof EventMovementLockScreen) {
            minecraft.setScreen(null);
        }
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
