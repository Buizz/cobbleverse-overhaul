package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonObject;
import dev.buizz.cobbleventure.playermenu.PlayerConditions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Authored actor settings, separate from random encounter pools and gate geometry. */
record GatePokemonConfig(
    String species, int level, String pose, float scale,
    double width, double height, double depth, String completionFlag,
    List<PlayerConditions.Condition> activationConditions, String eventBinding, String activationItem
) {
    static GatePokemonConfig parse(JsonObject properties, String gateId) {
        if (!"pokemon".equals(properties.get("center_placement").getAsString())) return null;
        if (!properties.has("pokemon")) throw new IllegalArgumentException("Pokemon gate requires pokemon settings: " + gateId);
        JsonObject value = properties.getAsJsonObject("pokemon");
        String species = resource(value, "species", null);
        double rawLevel = number(value, "level", Double.NaN, 1, 100);
        if (rawLevel != Math.rint(rawLevel)) throw new IllegalArgumentException("Gate Pokemon level must be an integer");
        int level = (int) rawLevel;
        String pose = value.has("pose") ? value.get("pose").getAsString() : "stand";
        if (!List.of("stand", "sleep").contains(pose)) throw new IllegalArgumentException("Gate Pokemon pose must be stand or sleep");
        float scale = (float) number(value, "scale", 1, 0.25, 4);
        JsonObject collision = value.getAsJsonObject("collision");
        if (collision == null) throw new IllegalArgumentException("Pokemon gate requires collision dimensions");
        double width = number(collision, "width", Double.NaN, 0.5, 16);
        int passage = properties.has("passage_width") ? properties.get("passage_width").getAsInt()
            : properties.has("opening_width") ? properties.get("opening_width").getAsInt() : 7;
        if (width * scale < passage) throw new IllegalArgumentException("Gate Pokemon collision must cover the passage width");
        List<PlayerConditions.Condition> activation = new ArrayList<>();
        if (value.has("activation_conditions")) value.getAsJsonArray("activation_conditions")
            .forEach(condition -> activation.add(PlayerConditions.parse(condition.getAsJsonObject())));
        return new GatePokemonConfig(
            species, level, pose, scale,
            width,
            number(collision, "height", Double.NaN, 0.5, 8),
            number(collision, "depth", Double.NaN, 0.5, 16),
            resource(value, "completion_flag", "cobbleventure:flag/gate/" + gateId + "_cleared"),
            List.copyOf(activation), resource(value, "event_binding", null, false),
            resource(value, "activation_item", null, false)
        );
    }

    private static double number(JsonObject value, String key, double fallback, double min, double max) {
        if (value.has(key) && (!value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber()))
            throw new IllegalArgumentException("Invalid Pokemon gate " + key);
        double number = value.has(key) ? value.get(key).getAsDouble() : fallback;
        if (!Double.isFinite(number) || number < min || number > max)
            throw new IllegalArgumentException("Invalid Pokemon gate " + key);
        return number;
    }

    private static String resource(JsonObject value, String key, String fallback) {
        return resource(value, key, fallback, true);
    }

    private static String resource(JsonObject value, String key, String fallback, boolean required) {
        String id = value.has(key) ? value.get(key).getAsString() : fallback;
        if (id == null && !required) return null;
        if (id == null || ResourceLocation.tryParse(id) == null)
            throw new IllegalArgumentException("Invalid Pokemon gate " + key);
        return id;
    }

    AABB bounds(Vec3 feet, String facing) {
        boolean eastWest = facing.equals("east") || facing.equals("west");
        double x = (eastWest ? depth : width) * scale / 2;
        double z = (eastWest ? width : depth) * scale / 2;
        return new AABB(feet.x - x, feet.y, feet.z - z, feet.x + x, feet.y + height * scale, feet.z + z);
    }

    boolean acceptsActivationItem(String usedItem) {
        return activationItem == null || activationItem.equals(usedItem);
    }

    String poseWhileChallenged(boolean challenged) {
        return challenged ? "stand" : pose;
    }
}
