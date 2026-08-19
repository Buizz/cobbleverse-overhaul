package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Gates Cobblemon's liquid and air mounts behind the matching field-move flags. */
public final class FieldMoveRidingAccess {
    private static final String FLAG_PREFIX = "cobbleventureFieldMove.";
    private static final String ACTIVE_PREFIX = "cobbleventureFieldMoveActive.";
    private static final String MESSAGE_COOLDOWN = "cobbleventureRideFieldMoveMessageCooldown";
    private static final ResourceLocation FOREST_DIMENSION =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "forests");
    private static final Map<UUID, RideMotionSnapshot> RIDE_MOTION = new HashMap<>();
    private static boolean registered;

    private FieldMoveRidingAccess() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(FieldMoveRidingAccess::onServerTick);
        CobblemonEvents.RIDE_EVENT_PRE.subscribe(
            (Consumer<RidePokemonEvent.Pre>) FieldMoveRidingAccess::onRideAttempt
        );
    }

    public static boolean isEnabled(ServerPlayer player, String move) {
        return player.getPersistentData().getBoolean(FLAG_PREFIX + normalize(move));
    }

    public static void setEnabled(ServerPlayer player, String move, boolean enabled) {
        String normalized = normalize(move);
        player.getPersistentData().putBoolean(FLAG_PREFIX + normalized, enabled);
        if (!enabled && isToggleable(normalized)) {
            player.getPersistentData().putBoolean(ACTIVE_PREFIX + normalized, false);
        }
    }

    public static boolean isActive(ServerPlayer player, String move) {
        String normalized = normalize(move);
        return isEnabled(player, normalized)
            && (!isToggleable(normalized)
                || player.getPersistentData().getBoolean(ACTIVE_PREFIX + normalized));
    }

    public static boolean setActive(ServerPlayer player, String move, boolean active) {
        String normalized = normalize(move);
        if (!isEnabled(player, normalized) || !isToggleable(normalized)) {
            return false;
        }
        player.getPersistentData().putBoolean(ACTIVE_PREFIX + normalized, active);
        return true;
    }

    public static boolean isToggleable(String move) {
        return switch (normalize(move)) {
            case "rock_climb", "flash", "strength", "rock_smash" -> true;
            default -> false;
        };
    }

    public static boolean isSupported(String move) {
        return switch (normalize(move)) {
            case "surf", "fly", "flash", "defog", "rock_climb", "whirlpool", "strength", "rock_smash" -> true;
            default -> false;
        };
    }

    public static boolean isValidSurfRide(ServerPlayer player) {
        if (!isEnabled(player, "surf")
            || !(player.getVehicle() instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)) {
            return false;
        }
        Map<RidingStyle, RidingBehaviourSettings> behaviours =
            pokemon.getRideProp().getBehaviours();
        return behaviours != null && behaviours.containsKey(RidingStyle.LIQUID);
    }

    public static String displayName(String move) {
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
        boolean hasLand = behaviours.containsKey(RidingStyle.LAND);
        boolean hasLiquid = behaviours.containsKey(RidingStyle.LIQUID);
        boolean hasAir = behaviours.containsKey(RidingStyle.AIR);
        boolean forest = player.level().dimension().location().equals(FOREST_DIMENSION);
        boolean liquidAllowed = hasLiquid && isEnabled(player, "surf");
        boolean airAllowed = hasAir && isEnabled(player, "fly") && !forest;
        if (hasLand || liquidAllowed || airAllowed) {
            return;
        }

        if (forest && hasAir && !hasLand && !liquidAllowed) {
            event.cancel();
            displayRideMessage(
                player,
                "[Cobbleventure] 숲에서는 공중을 날 수 있는 포켓몬에 탑승할 수 없습니다."
            );
            return;
        }

        List<String> missingMoves = new ArrayList<>(2);
        if (hasLiquid && !isEnabled(player, "surf")) {
            missingMoves.add(displayName("surf"));
        }
        if (hasAir && !isEnabled(player, "fly")) {
            missingMoves.add(displayName("fly"));
        }

        event.cancel();
        displayRideMessage(
            player,
            "[Cobbleventure] 이 포켓몬의 탑승 방식 중 하나를 사용하려면 "
                + String.join(" 또는 ", missingMoves) + " 플래그가 필요합니다."
        );
    }

    private static void displayRideMessage(ServerPlayer player, String message) {
        long gameTime = player.level().getGameTime();
        if (player.getPersistentData().getLong(MESSAGE_COOLDOWN) <= gameTime) {
            player.getPersistentData().putLong(MESSAGE_COOLDOWN, gameTime + 40L);
            player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            if (!(player.getVehicle()
                instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)) {
                RIDE_MOTION.remove(playerId);
                continue;
            }
            Map<RidingStyle, RidingBehaviourSettings> behaviours =
                pokemon.getRideProp().getBehaviours();
            var controller = pokemon.getRidingController();
            var context = controller == null ? null : controller.getContext();
            RidingStyle style = context == null ? null : context.getStyle();
            RideMotionSnapshot previous = RIDE_MOTION.get(playerId);
            if (previous == null || !previous.pokemonId().equals(pokemon.getUUID())
                || !previous.dimension().equals(player.level().dimension().location())) {
                previous = new RideMotionSnapshot(
                    pokemon.getUUID(), player.level().dimension().location(), pokemon.position()
                );
            }

            if (style == null || behaviours == null) {
                rememberRidePosition(playerId, pokemon);
                continue;
            }

            if (style == RidingStyle.AIR
                && player.level().dimension().location().equals(FOREST_DIMENSION)) {
                player.stopRiding();
                RIDE_MOTION.remove(playerId);
                displayRideMessage(
                    player,
                    "[Cobbleventure] 숲에서는 공중날기를 사용할 수 없습니다."
                );
                continue;
            }
            if (style == RidingStyle.LIQUID && !isEnabled(player, "surf")) {
                blockLockedLiquidMovement(player, pokemon, previous);
                continue;
            }
            if (style == RidingStyle.AIR && !isEnabled(player, "fly")) {
                landLockedAirMovement(player, pokemon, previous);
                continue;
            }
            rememberRidePosition(playerId, pokemon);
        }
    }

    private static void blockLockedLiquidMovement(
        ServerPlayer player,
        com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon,
        RideMotionSnapshot previous
    ) {
        Vec3 current = pokemon.position();
        Vec3 allowed = previous.position();
        pokemon.setPos(allowed.x(), current.y(), allowed.z());
        pokemon.setDeltaMovement(0.0D, 0.0D, 0.0D);
        pokemon.hurtMarked = true;
        RIDE_MOTION.put(player.getUUID(), new RideMotionSnapshot(
            pokemon.getUUID(), player.level().dimension().location(), pokemon.position()
        ));
        displayRideMessage(
            player, "[Cobbleventure] 수상 이동에는 파도타기 플래그가 필요합니다."
        );
    }

    private static void landLockedAirMovement(
        ServerPlayer player,
        com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon,
        RideMotionSnapshot previous
    ) {
        Vec3 current = pokemon.position();
        double correctedY = Math.min(current.y(), previous.position().y());
        if (correctedY != current.y()) {
            pokemon.setPos(current.x(), correctedY, current.z());
        }
        Vec3 movement = pokemon.getDeltaMovement();
        pokemon.setDeltaMovement(
            movement.x() * 0.35D,
            -0.06D,
            movement.z() * 0.35D
        );
        pokemon.hurtMarked = true;
        RIDE_MOTION.put(player.getUUID(), new RideMotionSnapshot(
            pokemon.getUUID(), player.level().dimension().location(), pokemon.position()
        ));
        displayRideMessage(
            player, "[Cobbleventure] 공중 이동에는 공중날기 플래그가 필요합니다."
        );
    }

    private static void rememberRidePosition(
        UUID playerId, com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
    ) {
        RIDE_MOTION.put(playerId, new RideMotionSnapshot(
            pokemon.getUUID(), pokemon.level().dimension().location(), pokemon.position()
        ));
    }

    private record RideMotionSnapshot(
        UUID pokemonId, ResourceLocation dimension, Vec3 position
    ) {}

    private static String normalize(String move) {
        return move.toLowerCase(Locale.ROOT);
    }
}
