package dev.buizz.cobbleventure.bootstrap;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Gates Cobblemon's liquid and air mounts behind the matching field-move flags. */
final class FieldMoveRidingAccess {
    private static final String FLAG_PREFIX = "cobbleventureFieldMove.";
    private static final String ACTIVE_PREFIX = "cobbleventureFieldMoveActive.";
    private static final String MESSAGE_COOLDOWN = "cobbleventureRideFieldMoveMessageCooldown";
    private static boolean registered;

    private FieldMoveRidingAccess() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CobblemonEvents.RIDE_EVENT_PRE.subscribe(
            (Consumer<RidePokemonEvent.Pre>) FieldMoveRidingAccess::onRideAttempt
        );
    }

    static boolean isEnabled(ServerPlayer player, String move) {
        return player.getPersistentData().getBoolean(FLAG_PREFIX + normalize(move));
    }

    static void setEnabled(ServerPlayer player, String move, boolean enabled) {
        String normalized = normalize(move);
        player.getPersistentData().putBoolean(FLAG_PREFIX + normalized, enabled);
        if (!enabled && isToggleable(normalized)) {
            player.getPersistentData().putBoolean(ACTIVE_PREFIX + normalized, false);
        }
    }

    static boolean isActive(ServerPlayer player, String move) {
        String normalized = normalize(move);
        return isEnabled(player, normalized)
            && (!isToggleable(normalized)
                || player.getPersistentData().getBoolean(ACTIVE_PREFIX + normalized));
    }

    static boolean setActive(ServerPlayer player, String move, boolean active) {
        String normalized = normalize(move);
        if (!isEnabled(player, normalized) || !isToggleable(normalized)) {
            return false;
        }
        player.getPersistentData().putBoolean(ACTIVE_PREFIX + normalized, active);
        return true;
    }

    static boolean isToggleable(String move) {
        return switch (normalize(move)) {
            case "rock_climb", "flash", "strength", "rock_smash" -> true;
            default -> false;
        };
    }

    static boolean isSupported(String move) {
        return switch (normalize(move)) {
            case "surf", "fly", "flash", "defog", "rock_climb", "whirlpool", "strength", "rock_smash" -> true;
            default -> false;
        };
    }

    static boolean isValidSurfRide(ServerPlayer player) {
        if (!isEnabled(player, "surf")
            || !(player.getVehicle() instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)) {
            return false;
        }
        Map<RidingStyle, RidingBehaviourSettings> behaviours =
            pokemon.getRideProp().getBehaviours();
        return behaviours != null && behaviours.containsKey(RidingStyle.LIQUID);
    }

    static String displayName(String move) {
        return switch (normalize(move)) {
            case "surf" -> "파도타기";
            case "fly" -> "공중날기";
            case "flash" -> "플래쉬";
            case "defog" -> "안개제거";
            case "rock_climb" -> "락클레임";
            case "whirlpool" -> "바다회오리";
            case "strength" -> "괴력";
            case "rock_smash" -> "바위깨기";
            default -> move;
        };
    }

    private static void onRideAttempt(RidePokemonEvent.Pre event) {
        Map<RidingStyle, RidingBehaviourSettings> behaviours =
            event.getPokemon().getRideProp().getBehaviours();
        if (behaviours == null || behaviours.isEmpty()) {
            return;
        }

        ServerPlayer player = event.getPlayer();
        List<String> missingMoves = new ArrayList<>(2);
        if (behaviours.containsKey(RidingStyle.LIQUID) && !isEnabled(player, "surf")) {
            missingMoves.add(displayName("surf"));
        }
        if (behaviours.containsKey(RidingStyle.AIR) && !isEnabled(player, "fly")) {
            missingMoves.add(displayName("fly"));
        }
        if (missingMoves.isEmpty()) {
            return;
        }

        event.cancel();
        long gameTime = player.level().getGameTime();
        if (player.getPersistentData().getLong(MESSAGE_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(MESSAGE_COOLDOWN, gameTime + 40L);
            player.displayClientMessage(Component.literal(
                "[Cobbleventure] 이 포켓몬에 탑승하려면 "
                    + String.join(" 및 ", missingMoves) + " 플래그가 필요합니다."
            ), true);
        }
    }

    private static String normalize(String move) {
        return move.toLowerCase(Locale.ROOT);
    }
}
