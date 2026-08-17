package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;

/** Shared player-condition parser and evaluator used by gates, doors, gyms, and NPC adapters. */
public final class PlayerConditions {
    private static final String INSTANCE_DEFEATED_FLAG =
        "cobbleventure:runtime/npc_instance_defeated";
    private static final String STARTER_RECEIVED_FLAG =
        "cobbleventure:flag/story/starter_received";

    private PlayerConditions() {}

    public interface Condition {
        boolean matches(ServerPlayer player);
    }

    public static Condition parse(JsonObject value) {
        String type = requiredString(value, "type");
        return switch (type) {
            case "variable" -> new VariableCondition(
                optionalString(value, "source", "scoreboard"),
                requiredString(value, "key"),
                optionalString(value, "operator", ">="),
                value.get("value").getAsDouble()
            );
            case "flag", "flag_equals" -> new FlagCondition(
                requiredString(value, "key"),
                numericValue(value, "value", 1.0D)
            );
            case "item", "has_item" -> new ItemCondition(
                requiredString(value, "item"),
                value.has("count") ? value.get("count").getAsInt() : 1,
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            case "badge" -> new BadgeCondition(
                requiredString(value, "badge"),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            case "pokemon" -> new PokemonCondition(
                requiredString(value, "species"),
                value.has("negate") && value.get("negate").getAsBoolean()
            );
            case "party_count" -> new PartyCountCondition(
                optionalString(value, "operator", ">="),
                value.get("value").getAsInt()
            );
            case "always" -> player -> true;
            default -> throw new IllegalStateException(
                "Unsupported player condition: " + type
            );
        };
    }

    public static boolean matches(
        ServerPlayer player, String mode, List<? extends Condition> conditions
    ) {
        return mode.equals("any")
            ? conditions.stream().anyMatch(condition -> condition.matches(player))
            : conditions.stream().allMatch(condition -> condition.matches(player));
    }

    public static String flagObjective(String key) {
        if (key.equals(INSTANCE_DEFEATED_FLAG)) return "cv_npc_defeated";
        if (key.equals(STARTER_RECEIVED_FLAG)) return "cv_starter_recv";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                key.getBytes(StandardCharsets.UTF_8)
            );
            return "cvf_" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is unavailable", error);
        }
    }

    private static double scoreboardValue(ServerPlayer player, String objectiveName) {
        Objective objective = player.getScoreboard().getObjective(objectiveName);
        return objective == null ? 0.0D
            : player.getScoreboard().getOrCreatePlayerScore(player, objective).get();
    }

    private static boolean compare(double actual, String operator, double expected) {
        return switch (operator) {
            case "==" -> actual == expected;
            case "!=" -> actual != expected;
            case ">" -> actual > expected;
            case "<" -> actual < expected;
            case "<=" -> actual <= expected;
            default -> actual >= expected;
        };
    }

    private static int itemCount(ServerPlayer player, Item item) {
        int count = player.getInventory().countItem(item);
        for (ItemStack stack : BagStorage.load(player)) {
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int partyCount(ServerPlayer player) {
        int count = 0;
        for (Pokemon ignored : Cobblemon.INSTANCE.getStorage().getParty(player)) count++;
        return count;
    }

    private static String requiredString(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalStateException("Missing player condition field: " + key);
        }
        return value.get(key).getAsString();
    }

    private static String optionalString(JsonObject value, String key, String fallback) {
        return value.has(key) ? value.get(key).getAsString() : fallback;
    }

    private static double numericValue(JsonObject value, String key, double fallback) {
        if (!value.has(key)) return fallback;
        if (value.get(key).isJsonPrimitive()
            && value.get(key).getAsJsonPrimitive().isBoolean()) {
            return value.get(key).getAsBoolean() ? 1.0D : 0.0D;
        }
        return value.get(key).getAsDouble();
    }

    private record VariableCondition(
        String source, String key, String operator, double value
    ) implements Condition {
        @Override public boolean matches(ServerPlayer player) {
            double actual = source.equals("persistent_data")
                ? player.getPersistentData().getDouble(key)
                : scoreboardValue(player, key);
            return compare(actual, operator, value);
        }
    }

    private record FlagCondition(String key, double value) implements Condition {
        @Override public boolean matches(ServerPlayer player) {
            return scoreboardValue(player, flagObjective(key)) == value;
        }
    }

    private record ItemCondition(String item, int count, boolean negate)
        implements Condition {
        @Override public boolean matches(ServerPlayer player) {
            ResourceLocation id = ResourceLocation.tryParse(item);
            Item required = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            boolean present = required != null && itemCount(player, required) >= count;
            return negate != present;
        }
    }

    private record BadgeCondition(String badge, boolean negate) implements Condition {
        @Override public boolean matches(ServerPlayer player) {
            return negate != BadgeProgressNetwork.hasBadge(player, badge);
        }
    }

    private record PokemonCondition(String species, boolean negate) implements Condition {
        @Override public boolean matches(ServerPlayer player) {
            boolean present = false;
            for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                if (pokemon.getSpecies().getResourceIdentifier().toString().equals(species)) {
                    present = true;
                    break;
                }
            }
            return negate != present;
        }
    }

    private record PartyCountCondition(String operator, int value) implements Condition {
        @Override public boolean matches(ServerPlayer player) {
            return compare(partyCount(player), operator, value);
        }
    }
}
