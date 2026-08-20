package dev.buizz.cobbleventure.adventure;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.RidePokemonEvent;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.api.riding.behaviour.RidingBehaviourSettings;
import com.cobblemon.mod.common.battles.BattleRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Gates Cobblemon's liquid and air mounts behind the matching field-move flags. */
public final class FieldMoveRidingAccess {
    private static final String FLAG_PREFIX = "cobbleventureFieldMove.";
    private static final String ACTIVE_PREFIX = "cobbleventureFieldMoveActive.";
    private static final String MESSAGE_COOLDOWN = "cobbleventureRideFieldMoveMessageCooldown";
    private static final ResourceLocation FOREST_DIMENSION =
        ResourceLocation.fromNamespaceAndPath("cobbleventure", "forests");
    private static final long FORCED_DISMOUNT_GRACE_TICKS = 5L;
    private static final Map<UUID, ActiveSurfRide> ACTIVE_SURF_RIDES = new HashMap<>();
    private static final Map<UUID, SafeShore> SAFE_SHORES = new HashMap<>();
    private static final Map<UUID, PendingDismount> PENDING_DISMOUNTS = new HashMap<>();
    private static final Map<UUID, OceanBattleSafety> OCEAN_BATTLE_SAFETY = new HashMap<>();
    private static boolean registered;

    private FieldMoveRidingAccess() {}

    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(FieldMoveRidingAccess::onServerTick);
        NeoForge.EVENT_BUS.addListener(FieldMoveRidingAccess::onEntityMount);
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

    /** Prevents the bare-swimmer current from moving a player whose ocean battle recalled the mount. */
    public static boolean isProtectedOceanBattle(ServerPlayer player) {
        return OCEAN_BATTLE_SAFETY.containsKey(player.getUUID());
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

    private static void onEntityMount(EntityMountEvent event) {
        if (!event.isDismounting() || event.getLevel().isClientSide()
            || !(event.getEntityMounting() instanceof ServerPlayer player)
            || !(event.getEntityBeingMounted()
                instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)
            || !isEnabled(player, "surf") || activeRidingStyle(pokemon) != RidingStyle.LIQUID
            || !(player.level() instanceof ServerLevel level)
            || !isDeepWaterColumn(level, pokemon.blockPosition())
            || hasNearbyDryDismount(level, pokemon.blockPosition(), 2)) {
            return;
        }

        if (player.isShiftKeyDown()) {
            event.setCanceled(true);
            displayRideMessage(
                player,
                "[Cobbleventure] 깊은 물에서는 내릴 수 없습니다. 얕은 물이나 육지로 이동하세요."
            );
            return;
        }

        PENDING_DISMOUNTS.put(player.getUUID(), new PendingDismount(
            level.dimension(), waterSurfacePosition(level, pokemon.blockPosition()),
            level.getGameTime() + FORCED_DISMOUNT_GRACE_TICKS
        ));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            rememberSafeShore(player);
            detectLostSurfRide(player);
            handleDismountSafety(player);
            if (!(player.getVehicle()
                instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon)) {
                continue;
            }
            Map<RidingStyle, RidingBehaviourSettings> behaviours =
                pokemon.getRideProp().getBehaviours();
            var controller = pokemon.getRidingController();
            var context = controller == null ? null : controller.getContext();
            RidingStyle style = context == null ? null : context.getStyle();
            if (style == RidingStyle.LIQUID && isEnabled(player, "surf")) {
                ACTIVE_SURF_RIDES.put(playerId, new ActiveSurfRide(
                    player.level().dimension(), waterSurfacePosition(
                        (ServerLevel) player.level(), pokemon.blockPosition()
                    )
                ));
                PENDING_DISMOUNTS.remove(playerId);
                OCEAN_BATTLE_SAFETY.remove(playerId);
            }
            if (style == null || behaviours == null) {
                continue;
            }

            if (style == RidingStyle.AIR
                && player.level().dimension().location().equals(FOREST_DIMENSION)) {
                player.stopRiding();
                displayRideMessage(
                    player,
                    "[Cobbleventure] 숲에서는 공중날기를 사용할 수 없습니다."
                );
                continue;
            }
            if (style == RidingStyle.LIQUID && !isEnabled(player, "surf")) {
                dismountLockedRidingStyle(player, "surf");
                continue;
            }
            if (style == RidingStyle.AIR && !isEnabled(player, "fly")) {
                dismountLockedRidingStyle(player, "fly");
                continue;
            }
        }
    }

    private static void rememberSafeShore(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || player.isPassenger()
            || !player.onGround() || player.isInWater()
            || !level.getFluidState(player.blockPosition()).isEmpty()) {
            return;
        }
        SAFE_SHORES.put(player.getUUID(), new SafeShore(level.dimension(), player.position()));
    }

    private static void detectLostSurfRide(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (player.getVehicle()
            instanceof com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
            && isEnabled(player, "surf") && activeRidingStyle(pokemon) == RidingStyle.LIQUID) {
            return;
        }
        ActiveSurfRide previous = ACTIVE_SURF_RIDES.remove(playerId);
        if (previous == null || PENDING_DISMOUNTS.containsKey(playerId)
            || !player.level().dimension().equals(previous.dimension())
            || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos origin = BlockPos.containing(previous.surface());
        if (!isDeepWaterColumn(level, origin) || hasNearbyDryDismount(level, origin, 2)) {
            return;
        }
        PENDING_DISMOUNTS.put(playerId, new PendingDismount(
            previous.dimension(), previous.surface(),
            level.getGameTime() + FORCED_DISMOUNT_GRACE_TICKS
        ));
    }

    private static void handleDismountSafety(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (isValidSurfRide(player)) {
            PENDING_DISMOUNTS.remove(playerId);
            OCEAN_BATTLE_SAFETY.remove(playerId);
            return;
        }

        OceanBattleSafety battleSafety = OCEAN_BATTLE_SAFETY.get(playerId);
        boolean inBattle = BattleRegistry.getBattleByParticipatingPlayer(player) != null;
        if (battleSafety != null) {
            if (!player.level().dimension().equals(battleSafety.dimension())) {
                OCEAN_BATTLE_SAFETY.remove(playerId);
                return;
            }
            if (inBattle) {
                holdAtWaterSurface(player, battleSafety.surface());
                return;
            }
            OCEAN_BATTLE_SAFETY.remove(playerId);
            if (isDeepWaterColumn((ServerLevel) player.level(), player.blockPosition())) {
                rescueToSafety(player);
            }
            return;
        }

        PendingDismount pending = PENDING_DISMOUNTS.get(playerId);
        if (pending == null || player.level().getGameTime() < pending.handleAt()) {
            return;
        }
        PENDING_DISMOUNTS.remove(playerId);
        if (!player.level().dimension().equals(pending.dimension())) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!isDeepWaterColumn(level, player.blockPosition())) {
            return;
        }
        if (inBattle) {
            OCEAN_BATTLE_SAFETY.put(
                playerId, new OceanBattleSafety(pending.dimension(), pending.surface())
            );
            holdAtWaterSurface(player, pending.surface());
            return;
        }
        rescueToSafety(player);
    }

    private static void holdAtWaterSurface(ServerPlayer player, Vec3 surface) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        double horizontalDistance = player.position().multiply(1.0D, 0.0D, 1.0D)
            .distanceToSqr(surface.multiply(1.0D, 0.0D, 1.0D));
        if (horizontalDistance > 0.25D || Math.abs(player.getY() - surface.y()) > 0.35D) {
            player.teleportTo(
                level, surface.x(), surface.y(), surface.z(), player.getYRot(), player.getXRot()
            );
        }
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.setAirSupply(player.getMaxAirSupply());
        player.resetFallDistance();
        player.hurtMarked = true;
    }

    private static void rescueToSafety(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        SafeShore saved = SAFE_SHORES.get(player.getUUID());
        Vec3 destination = saved != null && saved.dimension().equals(level.dimension())
            ? saved.position() : findNearbyDryPosition(level, player.blockPosition(), 32);
        if (destination == null) {
            BlockPos checkpoint = PokemonCenterDefeatReturn.sameDimensionCheckpointExit(player, level);
            BlockPos fallback = checkpoint != null ? checkpoint : level.getSharedSpawnPos();
            destination = new Vec3(fallback.getX() + 0.5D, fallback.getY(), fallback.getZ() + 0.5D);
        }
        player.teleportTo(
            level, destination.x(), destination.y(), destination.z(),
            player.getYRot(), player.getXRot()
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.displayClientMessage(Component.literal(
            "[Cobbleventure] 파도타기 포켓몬과 강제로 분리되어 안전한 장소로 구조되었습니다."
        ), true);
    }

    private static RidingStyle activeRidingStyle(
        com.cobblemon.mod.common.entity.pokemon.PokemonEntity pokemon
    ) {
        var controller = pokemon.getRidingController();
        var context = controller == null ? null : controller.getContext();
        return context == null ? null : context.getStyle();
    }

    private static boolean isDeepWaterColumn(ServerLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos water = origin.mutable();
        if (!level.getFluidState(water).is(FluidTags.WATER)) {
            water.move(0, -1, 0);
        }
        if (!level.getFluidState(water).is(FluidTags.WATER)) {
            water.move(0, -1, 0);
        }
        if (!level.getFluidState(water).is(FluidTags.WATER)) {
            return false;
        }
        while (water.getY() < level.getMaxBuildHeight() - 1
            && level.getFluidState(water.above()).is(FluidTags.WATER)) {
            water.move(0, 1, 0);
        }
        return level.getFluidState(water.below()).is(FluidTags.WATER);
    }

    private static Vec3 waterSurfacePosition(ServerLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos water = origin.mutable();
        while (!level.getFluidState(water).is(FluidTags.WATER)
            && water.getY() > level.getMinBuildHeight()) {
            water.move(0, -1, 0);
        }
        while (water.getY() < level.getMaxBuildHeight() - 1
            && level.getFluidState(water.above()).is(FluidTags.WATER)) {
            water.move(0, 1, 0);
        }
        return new Vec3(origin.getX() + 0.5D, water.getY() + 1.02D, origin.getZ() + 0.5D);
    }

    private static boolean hasNearbyDryDismount(
        ServerLevel level, BlockPos origin, int maxRadius
    ) {
        return findNearbyDryPosition(level, origin, maxRadius) != null;
    }

    private static Vec3 findNearbyDryPosition(
        ServerLevel level, BlockPos origin, int maxRadius
    ) {
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (Math.abs(offsetX) != radius && Math.abs(offsetZ) != radius) {
                        continue;
                    }
                    int x = origin.getX() + offsetX;
                    int z = origin.getZ() + offsetZ;
                    int groundY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z
                    ) - 1;
                    BlockPos ground = new BlockPos(x, groundY, z);
                    BlockPos feet = ground.above();
                    if (level.getFluidState(ground).is(FluidTags.WATER)
                        || !level.getFluidState(feet).isEmpty()
                        || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        || !level.getBlockState(feet.above())
                            .getCollisionShape(level, feet.above()).isEmpty()) {
                        continue;
                    }
                    return new Vec3(x + 0.5D, feet.getY(), z + 0.5D);
                }
            }
        }
        return null;
    }

    private static void dismountLockedRidingStyle(ServerPlayer player, String move) {
        player.stopRiding();
        displayRideMessage(
            player,
            "[Cobbleventure] " + displayName(move)
                + "가 없어 해당 이동 방식을 사용할 수 없어 탑승이 해제되었습니다."
        );
    }

    private record SafeShore(ResourceKey<Level> dimension, Vec3 position) {}

    private record ActiveSurfRide(ResourceKey<Level> dimension, Vec3 surface) {}

    private record PendingDismount(
        ResourceKey<Level> dimension, Vec3 surface, long handleAt
    ) {}

    private record OceanBattleSafety(ResourceKey<Level> dimension, Vec3 surface) {}

    private static String normalize(String move) {
        return move.toLowerCase(Locale.ROOT);
    }
}
