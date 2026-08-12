package dev.buizz.cobbleventure.bootstrap;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.buizz.cobbleventure.bootstrap.client.BattleIntroOverlay;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Delays a trainer battle while the initiating client displays a versus cut-in. */
final class BattleIntro {
    static final int DURATION_TICKS = 56;
    private static final String NETWORK_VERSION = "1";
    private static final Map<UUID, PendingBattle> PENDING = new HashMap<>();

    private BattleIntro() {}

    static void register(IEventBus modBus) {
        modBus.addListener(BattleIntro::registerPayloads);
        NeoForge.EVENT_BUS.addListener(BattleIntro::registerCommands);
        NeoForge.EVENT_BUS.addListener(BattleIntro::onServerTick);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC, BattleIntro::handleOpen);
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

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_battle_intro")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("opponent", EntityArgument.entity())
                        .then(Commands.argument("battle_command", StringArgumentType.greedyString())
                            .executes(context -> start(
                                context.getSource(),
                                EntityArgument.getPlayer(context, "player"),
                                EntityArgument.getEntity(context, "opponent"),
                                StringArgumentType.getString(context, "battle_command")
                            )))))
        );
    }

    private static int start(
        CommandSourceStack source, ServerPlayer player, Entity opponent, String battleCommand
    ) {
        String normalized = battleCommand.startsWith("/")
            ? battleCommand.substring(1)
            : battleCommand;
        if (!normalized.startsWith("tbcs battle ")) return 0;

        long executeAt = source.getServer().overworld().getGameTime() + DURATION_TICKS;
        PENDING.put(player.getUUID(), new PendingBattle(source, normalized, executeAt));
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
        Iterator<Map.Entry<UUID, PendingBattle>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingBattle> entry = iterator.next();
            PendingBattle pending = entry.getValue();
            if (pending.executeAt > gameTime) continue;
            iterator.remove();
            if (event.getServer().getPlayerList().getPlayer(entry.getKey()) == null) continue;
            event.getServer().getCommands().performPrefixedCommand(
                pending.source, pending.battleCommand
            );
        }
    }

    private record PendingBattle(
        CommandSourceStack source, String battleCommand, long executeAt
    ) {}

    record OpenPayload(
        int playerEntityId,
        int opponentEntityId,
        String playerName,
        String opponentName,
        int durationTicks
    ) implements CustomPacketPayload {
        private static final Type<OpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleventureBootstrap.MOD_ID, "battle_intro_open"
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
}
