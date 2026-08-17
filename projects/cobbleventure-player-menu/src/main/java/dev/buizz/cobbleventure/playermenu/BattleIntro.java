package dev.buizz.cobbleventure.playermenu;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.playermenu.client.BattleIntroOverlay;
import dev.buizz.cobbleventure.playermenu.client.BattleWarningOverlay;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/** Delays a trainer battle while the initiating client displays a versus cut-in. */
public final class BattleIntro {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int DURATION_TICKS = 56;
    static final double PROXIMITY_BATTLE_RANGE = 6.0D;
    private static final double PROXIMITY_WARNING_RANGE = 8.0D;
    private static final String NETWORK_VERSION = "1";
    private static final Map<UUID, PendingBattle> PENDING = new HashMap<>();
    private static final Map<ProximityKey, PendingProximityBattle> PROXIMITY_PENDING =
        new HashMap<>();
    private static final Map<ProximityKey, PendingEncounterDialogue> DIALOGUE_PENDING =
        new HashMap<>();

    private BattleIntro() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BattleIntro::registerPayloads);
        NeoForge.EVENT_BUS.addListener(BattleIntro::registerCommands);
        NeoForge.EVENT_BUS.addListener(BattleIntro::onServerTick);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, BattleIntro::handleOpen);
        registrar.playToClient(
            WarningPayload.TYPE, WarningPayload.STREAM_CODEC, BattleIntro::handleWarning
        );
    }

    private static void handleOpen(OpenPayload payload, IPayloadContext context) {
        BattleIntroOverlay.start(
            payload.playerEntityId(),
            payload.opponentEntityId(),
            payload.playerName(),
            payload.opponentName(),
            payload.durationTicks()
        );
    }

    private static void handleWarning(WarningPayload payload, IPayloadContext context) {
        if (payload.visible()) {
            BattleWarningOverlay.start(payload.opponentName());
        } else {
            BattleWarningOverlay.dismiss();
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_battle_intro")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("opponent", EntityArgument.entity())
                        .then(Commands.argument("battle_id", ResourceLocationArgument.id())
                            .then(Commands.argument("battle_command", StringArgumentType.greedyString())
                                .executes(context -> start(
                                    context.getSource(),
                                    EntityArgument.getPlayer(context, "player"),
                                    EntityArgument.getEntity(context, "opponent"),
                                    ResourceLocationArgument.getId(context, "battle_id").toString(),
                                    StringArgumentType.getString(context, "battle_command")
                                ))))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_battle_warning")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("opponent", EntityArgument.entity())
                        .executes(context -> warn(
                            EntityArgument.getPlayer(context, "player"),
                            EntityArgument.getEntity(context, "opponent")
                        ))))
        );
        event.getDispatcher().register(
            Commands.literal("cobbleventure_proximity_battle")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("opponent", EntityArgument.entity())
                        .then(Commands.argument("encounter_track", StringArgumentType.word())
                            .then(Commands.argument("dialogue_label", StringArgumentType.word())
                                .then(Commands.argument("battle_command", StringArgumentType.greedyString())
                                    .executes(context -> watchProximity(
                                        EntityArgument.getPlayer(context, "player"),
                                        EntityArgument.getEntity(context, "opponent"),
                                        StringArgumentType.getString(context, "encounter_track"),
                                        StringArgumentType.getString(context, "dialogue_label"),
                                        StringArgumentType.getString(context, "battle_command")
                                    )))))))
        );
    }

    private static int warn(ServerPlayer player, Entity opponent) {
        PacketDistributor.sendToPlayer(player, new WarningPayload(
            opponent.getDisplayName().getString(), true
        ));
        return 1;
    }

    private static void dismissWarning(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new WarningPayload("", false));
    }

    private static int watchProximity(
        ServerPlayer player, Entity opponent, String encounterTrack,
        String dialogueLabel, String battleCommand
    ) {
        String normalized = battleCommand.startsWith("/")
            ? battleCommand.substring(1)
            : battleCommand;
        if (!normalized.startsWith("cobbleventure_battle_intro ")
            && !normalized.startsWith("cobbleventure_scaled_trainer_battle ")) {
            LOGGER.warn(
                "Rejected proximity encounter command for npc {}: {}",
                opponent.getUUID(), normalized
            );
            return 0;
        }
        if (prepareTrainerState(player, opponent)) {
            PROXIMITY_PENDING.remove(new ProximityKey(
                player.getUUID(), opponent.getUUID()
            ));
            clearProximityFeedbackIfIdle(player);
            return 1;
        }
        normalized = normalized
            .replace("@initiator", player.getGameProfile().getName());
        normalized = replaceFirstSelector(
            normalized, "@s", opponent.getUUID().toString()
        );
        PROXIMITY_PENDING.put(
            new ProximityKey(player.getUUID(), opponent.getUUID()),
            new PendingProximityBattle(opponent, dialogueLabel, normalized)
        );
        LOGGER.debug(
            "Registered proximity trainer encounter: npc={}, player={}",
            opponent.getUUID(), player.getGameProfile().getName()
        );
        MusicPlayback.prepareEncounter(player, encounterTrack);
        return warn(player, opponent);
    }

    private static boolean prepareTrainerState(ServerPlayer player, Entity opponent) {
        int completed = 0;
        try {
            completed = player.getServer().getCommands().getDispatcher().execute(
                "cobbleventure_trainer_state prepare " + opponent.getUUID()
                    + " " + player.getGameProfile().getName(),
                player.getServer().createCommandSourceStack()
                    .withLevel(player.serverLevel())
                    .withPermission(4)
                    .withSuppressedOutput()
            );
        } catch (CommandSyntaxException error) {
            // A state lookup failure must not silently disable the encounter.
            // Continue as an undefeated trainer and leave a useful diagnosis in
            // the server log. The victory callback can still record completion.
            LOGGER.warn(
                "Unable to read proximity trainer state: npc={}, player={}",
                opponent.getUUID(), player.getGameProfile().getName(), error
            );
        }
        return completed > 0;
    }

    private static int start(
        CommandSourceStack source, ServerPlayer player, Entity opponent,
        String battleId, String battleCommand
    ) {
        String normalized = battleCommand.startsWith("/")
            ? battleCommand.substring(1)
            : battleCommand;
        if (!normalized.startsWith("tbcs battle ")) return 0;

        // Resolve the player macro now, but preserve the nested TBCS @s. TBCS
        // must resolve that selector from the living NPC command source; a UUID
        // literal is not accepted as its trainer participant.
        normalized = normalized
            .replace("@initiator", player.getGameProfile().getName());
        // The proximity iterator owns pending-entry removal. Mutating its map
        // recursively from this command can fail when several trainers notice
        // the same player in one tick.
        dismissWarning(player);

        long executeAt = source.getServer().overworld().getGameTime() + DURATION_TICKS;
        Vec3 lockedPosition = player.position();
        PendingBattle pending = new PendingBattle(
            opponent.createCommandSourceStack()
                .withPermission(4)
                .withSuppressedOutput(),
            normalized, executeAt, opponent, lockedPosition,
            player.getYRot(), player.getXRot()
        );
        PENDING.put(player.getUUID(), pending);
        DIALOGUE_PENDING.remove(new ProximityKey(
            player.getUUID(), opponent.getUUID()
        ));
        freezeForIntro(player, pending);
        MusicPlayback.prepareBattle(player, battleId);
        PacketDistributor.sendToPlayer(player, new OpenPayload(
            player.getId(),
            opponent.getId(),
            player.getDisplayName().getString(),
            opponent.getDisplayName().getString(),
            DURATION_TICKS
        ));
        return 1;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        Set<UUID> triggeredPlayers = new HashSet<>();
        Iterator<Map.Entry<ProximityKey, PendingProximityBattle>> proximityIterator =
            PROXIMITY_PENDING.entrySet().iterator();
        while (proximityIterator.hasNext()) {
            Map.Entry<ProximityKey, PendingProximityBattle> entry = proximityIterator.next();
            UUID playerId = entry.getKey().playerId;
            if (triggeredPlayers.contains(playerId)) {
                entry.getValue().armed = false;
                continue;
            }
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            PendingProximityBattle pending = entry.getValue();
            Entity opponent = pending.opponent;
            if (player == null) {
                proximityIterator.remove();
                continue;
            }
            if (PENDING.containsKey(playerId)
                || DIALOGUE_PENDING.keySet().stream().anyMatch(
                    key -> key.playerId.equals(playerId)
                )
                || BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null) {
                pending.armed = false;
                continue;
            }
            if (!opponent.isAlive() || player.level() != opponent.level()) {
                proximityIterator.remove();
                clearProximityFeedbackIfIdle(player);
                continue;
            }
            double dx = player.getX() - opponent.getX();
            double dz = player.getZ() - opponent.getZ();
            double horizontalDistanceSquared = dx * dx + dz * dz;
            if (horizontalDistanceSquared > PROXIMITY_WARNING_RANGE * PROXIMITY_WARNING_RANGE) {
                proximityIterator.remove();
                clearProximityFeedbackIfIdle(player);
                continue;
            }
            if (!pending.armed) {
                if (horizontalDistanceSquared
                    > PROXIMITY_BATTLE_RANGE * PROXIMITY_BATTLE_RANGE) {
                    pending.armed = true;
                }
                continue;
            }
            if (horizontalDistanceSquared > PROXIMITY_BATTLE_RANGE * PROXIMITY_BATTLE_RANGE) {
                continue;
            }
            // cv_npc_defeated is an EasyNPC adapter value shared by one player.
            // Refresh it for the exact NPC immediately before opening its dialog,
            // because another trainer may have changed the value since registration.
            if (prepareTrainerState(player, opponent)) {
                proximityIterator.remove();
                clearProximityFeedbackIfIdle(player);
                continue;
            }
            proximityIterator.remove();
            triggeredPlayers.add(playerId);
            String openDialogue = "easy_npc dialog open " + opponent.getUUID()
                + " " + player.getUUID() + " " + pending.dialogueLabel;
            event.getServer().getCommands().performPrefixedCommand(
                event.getServer().createCommandSourceStack()
                    .withLevel(player.serverLevel())
                    .withPosition(opponent.position())
                    .withPermission(4)
                    .withSuppressedOutput(),
                openDialogue
            );
            DIALOGUE_PENDING.put(
                entry.getKey(),
                new PendingEncounterDialogue(
                    opponent, pending.battleCommand, player.position()
                )
            );
        }
        if (!triggeredPlayers.isEmpty()) {
            PROXIMITY_PENDING.forEach((key, pending) -> {
                if (triggeredPlayers.contains(key.playerId)) pending.armed = false;
            });
        }

        Iterator<Map.Entry<ProximityKey, PendingEncounterDialogue>> dialogueIterator =
            DIALOGUE_PENDING.entrySet().iterator();
        while (dialogueIterator.hasNext()) {
            Map.Entry<ProximityKey, PendingEncounterDialogue> entry = dialogueIterator.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(
                entry.getKey().playerId
            );
            PendingEncounterDialogue pending = entry.getValue();
            if (player == null || !pending.opponent.isAlive()
                || player.level() != pending.opponent.level()) {
                dialogueIterator.remove();
                if (player != null) clearProximityFeedbackIfIdle(player);
                continue;
            }
            freezeForDialogue(player, pending);
            if (player.containerMenu != player.inventoryMenu) continue;
            dialogueIterator.remove();
            event.getServer().getCommands().performPrefixedCommand(
                event.getServer().createCommandSourceStack()
                    .withLevel(player.serverLevel())
                    .withPosition(pending.opponent.position())
                    .withPermission(4)
                    .withSuppressedOutput(),
                pending.battleCommand
            );
        }

        Iterator<Map.Entry<UUID, PendingBattle>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingBattle> entry = iterator.next();
            PendingBattle pending = entry.getValue();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !pending.opponent.isAlive()
                || player.level() != pending.opponent.level()) {
                iterator.remove();
                continue;
            }
            freezeForIntro(player, pending);
            if (pending.executeAt > gameTime) continue;
            iterator.remove();
            event.getServer().getCommands().performPrefixedCommand(
                pending.source, pending.battleCommand
            );
        }
    }

    private static void freezeForIntro(ServerPlayer player, PendingBattle pending) {
        double dx = pending.opponent.getX() - pending.lockedPosition.x;
        double dz = pending.opponent.getZ() - pending.lockedPosition.z;
        double dy = pending.opponent.getEyeY() - player.getEyeY();
        double horizontal = Math.max(0.0001D, Math.sqrt(dx * dx + dz * dz));
        float targetYaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float)-Math.toDegrees(Math.atan2(dy, horizontal));
        pending.cameraYaw = smoothRotation(pending.cameraYaw, targetYaw, 0.18F, 9.0F);
        pending.cameraPitch = smoothRotation(pending.cameraPitch, targetPitch, 0.16F, 5.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.teleportTo(
            player.serverLevel(),
            pending.lockedPosition.x, pending.lockedPosition.y, pending.lockedPosition.z,
            pending.cameraYaw, pending.cameraPitch
        );
    }

    private static void freezeForDialogue(
        ServerPlayer player, PendingEncounterDialogue pending
    ) {
        player.setDeltaMovement(Vec3.ZERO);
        player.teleportTo(
            player.serverLevel(),
            pending.lockedPosition.x, pending.lockedPosition.y, pending.lockedPosition.z,
            player.getYRot(), player.getXRot()
        );
    }

    private static float smoothRotation(
        float current, float target, float easing, float maximumStep
    ) {
        float difference = Mth.wrapDegrees(target - current);
        if (Math.abs(difference) < 0.15F) return target;
        float step = Mth.clamp(difference * easing, -maximumStep, maximumStep);
        return current + step;
    }

    private static boolean hasProximityPending(UUID playerId) {
        return PROXIMITY_PENDING.keySet().stream().anyMatch(
            key -> key.playerId.equals(playerId)
        ) || DIALOGUE_PENDING.keySet().stream().anyMatch(
            key -> key.playerId.equals(playerId)
        );
    }

    private static void clearProximityFeedbackIfIdle(ServerPlayer player) {
        if (hasProximityPending(player.getUUID())) return;
        dismissWarning(player);
        MusicPlayback.cancelEncounter(player);
    }

    private static String replaceFirstSelector(
        String command, String selector, String replacement
    ) {
        int index = command.indexOf(selector);
        if (index < 0) return command;
        return command.substring(0, index) + replacement
            + command.substring(index + selector.length());
    }

    private static final class PendingBattle {
        private final CommandSourceStack source;
        private final String battleCommand;
        private final long executeAt;
        private final Entity opponent;
        private final Vec3 lockedPosition;
        private float cameraYaw;
        private float cameraPitch;

        private PendingBattle(
            CommandSourceStack source, String battleCommand, long executeAt,
            Entity opponent, Vec3 lockedPosition, float cameraYaw, float cameraPitch
        ) {
            this.source = source;
            this.battleCommand = battleCommand;
            this.executeAt = executeAt;
            this.opponent = opponent;
            this.lockedPosition = lockedPosition;
            this.cameraYaw = cameraYaw;
            this.cameraPitch = cameraPitch;
        }
    }

    private static final class PendingProximityBattle {
        private final Entity opponent;
        private final String dialogueLabel;
        private final String battleCommand;
        private boolean armed = true;

        private PendingProximityBattle(
            Entity opponent, String dialogueLabel, String battleCommand
        ) {
            this.opponent = opponent;
            this.dialogueLabel = dialogueLabel;
            this.battleCommand = battleCommand;
        }
    }

    private record PendingEncounterDialogue(
        Entity opponent, String battleCommand, Vec3 lockedPosition
    ) {}

    private record ProximityKey(UUID playerId, UUID opponentId) {}

    record OpenPayload(
        int playerEntityId,
        int opponentEntityId,
        String playerName,
        String opponentName,
        int durationTicks
    ) implements CustomPacketPayload {
        private static final Type<OpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleventurePlayerMenu.MOD_ID, "battle_intro_open"
        ));
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(OpenPayload::write, OpenPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(playerEntityId);
            buffer.writeVarInt(opponentEntityId);
            buffer.writeUtf(playerName, 128);
            buffer.writeUtf(opponentName, 128);
            buffer.writeVarInt(durationTicks);
        }

        private static OpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new OpenPayload(
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readUtf(128),
                buffer.readUtf(128),
                Math.max(20, Math.min(100, buffer.readVarInt()))
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    record WarningPayload(
        String opponentName,
        boolean visible
    ) implements CustomPacketPayload {
        private static final Type<WarningPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventurePlayerMenu.MOD_ID, "battle_warning_open"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, WarningPayload> STREAM_CODEC =
            StreamCodec.ofMember(WarningPayload::write, WarningPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(opponentName, 128);
            buffer.writeBoolean(visible);
        }

        private static WarningPayload read(RegistryFriendlyByteBuf buffer) {
            return new WarningPayload(
                buffer.readUtf(128),
                buffer.readBoolean()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
