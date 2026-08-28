package dev.buizz.cobbleventure.bootstrap;

import dev.buizz.cobbleventure.bootstrap.client.DungeonGuideScreen;
import dev.buizz.cobbleventure.bootstrap.client.DungeonQueueScreen;
import dev.buizz.cobbleventure.bootstrap.client.DungeonRewardScreen;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Synchronizes dungeon guide and matchmaking screens with server-owned entry state. */
public final class DungeonGuideNetwork {
    private static final String VERSION = "6";
    private static final int MAX_REWARD_ENTRIES = 128;

    private DungeonGuideNetwork() {}

    static void register(IEventBus modBus) {
        modBus.addListener(DungeonGuideNetwork::registerPayloads);
    }

    static void open(ServerPlayer player, GuideData data) {
        PacketDistributor.sendToPlayer(player, new OpenGuidePayload(data));
    }

    static void openQueue(ServerPlayer player, QueueData data) {
        PacketDistributor.sendToPlayer(player, new OpenQueuePayload(data));
    }

    static void preparingQueue(ServerPlayer player, String entranceId) {
        PacketDistributor.sendToPlayer(
            player, new QueueStatePayload(entranceId, "preparing")
        );
    }

    static void closeQueue(ServerPlayer player, String entranceId) {
        PacketDistributor.sendToPlayer(
            player, new QueueStatePayload(entranceId, "closed")
        );
    }

    static void openRewards(
        ServerPlayer player,
        String dungeonName,
        boolean firstClear,
        int clearCount,
        List<ItemStack> rewards
    ) {
        PacketDistributor.sendToPlayer(
            player,
            new OpenRewardsPayload(new RewardData(
                dungeonName, firstClear, clearCount, summarizeRewards(rewards)
            ))
        );
    }

    static List<RewardEntry> summarizeRewards(List<ItemStack> rewards) {
        List<RewardEntry> entries = new ArrayList<>();
        for (ItemStack reward : rewards) {
            if (reward.isEmpty()) continue;
            int matchingIndex = -1;
            for (int index = 0; index < entries.size(); index++) {
                if (ItemStack.isSameItemSameComponents(
                    entries.get(index).stack(), reward
                )) {
                    matchingIndex = index;
                    break;
                }
            }
            if (matchingIndex < 0) {
                ItemStack icon = reward.copy();
                icon.setCount(1);
                entries.add(new RewardEntry(icon, reward.getCount()));
            } else {
                RewardEntry entry = entries.get(matchingIndex);
                entries.set(matchingIndex, new RewardEntry(
                    entry.stack(), entry.count() + reward.getCount()
                ));
            }
        }
        return List.copyOf(entries);
    }

    public static void respond(String entranceId, boolean accepted) {
        PacketDistributor.sendToServer(new GuideResponsePayload(entranceId, accepted));
    }

    public static void cancelQueue(String entranceId) {
        PacketDistributor.sendToServer(new QueueCancelPayload(entranceId));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(
            OpenGuidePayload.TYPE,
            OpenGuidePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleOpen
        );
        registrar.playToServer(
            GuideResponsePayload.TYPE,
            GuideResponsePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleResponse
        );
        registrar.playToClient(
            OpenQueuePayload.TYPE,
            OpenQueuePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleOpenQueue
        );
        registrar.playToClient(
            QueueStatePayload.TYPE,
            QueueStatePayload.STREAM_CODEC,
            DungeonGuideNetwork::handleQueueState
        );
        registrar.playToServer(
            QueueCancelPayload.TYPE,
            QueueCancelPayload.STREAM_CODEC,
            DungeonGuideNetwork::handleQueueCancel
        );
        registrar.playToClient(
            OpenRewardsPayload.TYPE,
            OpenRewardsPayload.STREAM_CODEC,
            DungeonGuideNetwork::handleOpenRewards
        );
    }

    private static void handleOpen(OpenGuidePayload payload, IPayloadContext context) {
        DungeonGuideScreen.open(payload.data());
    }

    private static void handleResponse(
        GuideResponsePayload payload, IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer player) {
            DungeonSystem.respond(player, payload.entranceId(), payload.accepted());
        }
    }

    private static void handleOpenQueue(OpenQueuePayload payload, IPayloadContext context) {
        DungeonQueueScreen.open(payload.data());
    }

    private static void handleQueueState(QueueStatePayload payload, IPayloadContext context) {
        if (payload.state().equals("preparing")) {
            DungeonQueueScreen.preparing(payload.entranceId());
        } else {
            DungeonQueueScreen.close(payload.entranceId());
        }
    }

    private static void handleQueueCancel(
        QueueCancelPayload payload, IPayloadContext context
    ) {
        if (context.player() instanceof ServerPlayer player) {
            DungeonSystem.cancelWaiting(player, payload.entranceId());
        }
    }

    private static void handleOpenRewards(
        OpenRewardsPayload payload, IPayloadContext context
    ) {
        DungeonRewardScreen.open(payload.data());
    }

    public record GuideData(
        String entranceId,
        String title,
        String description,
        String backgroundTexture,
        int recommendedMin,
        int recommendedMax,
        int internalMin,
        int internalMax,
        String infoMode,
        String wipeReturn,
        boolean healOnWipe,
        boolean repeatable,
        boolean allowFlee,
        boolean allowCapture,
        boolean allowItems,
        String levelMeasure,
        int currentPartyLevel,
        String multiplayerMode,
        int requiredPlayers,
        int tetherWarnDistance,
        int tetherMaxDistance
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(entranceId);
            buffer.writeUtf(title);
            buffer.writeUtf(description);
            buffer.writeUtf(backgroundTexture);
            buffer.writeVarInt(recommendedMin);
            buffer.writeVarInt(recommendedMax);
            buffer.writeVarInt(internalMin);
            buffer.writeVarInt(internalMax);
            buffer.writeUtf(infoMode);
            buffer.writeUtf(wipeReturn);
            buffer.writeBoolean(healOnWipe);
            buffer.writeBoolean(repeatable);
            buffer.writeBoolean(allowFlee);
            buffer.writeBoolean(allowCapture);
            buffer.writeBoolean(allowItems);
            buffer.writeUtf(levelMeasure);
            buffer.writeVarInt(currentPartyLevel);
            buffer.writeUtf(multiplayerMode);
            buffer.writeVarInt(requiredPlayers);
            buffer.writeVarInt(tetherWarnDistance);
            buffer.writeVarInt(tetherMaxDistance);
        }

        private static GuideData read(RegistryFriendlyByteBuf buffer) {
            return new GuideData(
                buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(),
                buffer.readUtf(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readUtf(), buffer.readVarInt(), buffer.readUtf(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt()
            );
        }
    }

    public record QueueData(
        String entranceId,
        String title,
        int currentPlayers,
        int requiredPlayers,
        int timeoutSeconds
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(entranceId);
            buffer.writeUtf(title);
            buffer.writeVarInt(currentPlayers);
            buffer.writeVarInt(requiredPlayers);
            buffer.writeVarInt(timeoutSeconds);
        }

        private static QueueData read(RegistryFriendlyByteBuf buffer) {
            return new QueueData(
                buffer.readUtf(), buffer.readUtf(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt()
            );
        }
    }

    public record RewardEntry(ItemStack stack, int count) {
        private void write(RegistryFriendlyByteBuf buffer) {
            ItemStack.STREAM_CODEC.encode(buffer, stack);
            buffer.writeVarInt(count);
        }

        private static RewardEntry read(RegistryFriendlyByteBuf buffer) {
            ItemStack stack = ItemStack.STREAM_CODEC.decode(buffer);
            int count = buffer.readVarInt();
            if (count < 1) {
                throw new IllegalArgumentException("Dungeon reward count must be positive");
            }
            return new RewardEntry(stack, count);
        }
    }

    public record RewardData(
        String dungeonName,
        boolean firstClear,
        int clearCount,
        List<RewardEntry> rewards
    ) {
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(dungeonName);
            buffer.writeBoolean(firstClear);
            buffer.writeVarInt(clearCount);
            buffer.writeVarInt(rewards.size());
            rewards.forEach(reward -> reward.write(buffer));
        }

        private static RewardData read(RegistryFriendlyByteBuf buffer) {
            String dungeonName = buffer.readUtf();
            boolean firstClear = buffer.readBoolean();
            int clearCount = buffer.readVarInt();
            int rewardCount = buffer.readVarInt();
            if (rewardCount < 0 || rewardCount > MAX_REWARD_ENTRIES) {
                throw new IllegalArgumentException(
                    "Invalid dungeon reward entry count: " + rewardCount
                );
            }
            List<RewardEntry> rewards = new ArrayList<>(rewardCount);
            for (int index = 0; index < rewardCount; index++) {
                rewards.add(RewardEntry.read(buffer));
            }
            return new RewardData(
                dungeonName, firstClear, clearCount, List.copyOf(rewards)
            );
        }
    }

    private record OpenGuidePayload(GuideData data) implements CustomPacketPayload {
        private static final Type<OpenGuidePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "open_dungeon_guide"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenGuidePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> payload.data.write(buffer),
                buffer -> new OpenGuidePayload(GuideData.read(buffer))
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record GuideResponsePayload(
        String entranceId, boolean accepted
    ) implements CustomPacketPayload {
        private static final Type<GuideResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "dungeon_guide_response"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, GuideResponsePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUtf(payload.entranceId);
                    buffer.writeBoolean(payload.accepted);
                },
                buffer -> new GuideResponsePayload(
                    buffer.readUtf(), buffer.readBoolean()
                )
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record OpenQueuePayload(QueueData data) implements CustomPacketPayload {
        private static final Type<OpenQueuePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "open_dungeon_queue"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenQueuePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> payload.data.write(buffer),
                buffer -> new OpenQueuePayload(QueueData.read(buffer))
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record QueueStatePayload(
        String entranceId, String state
    ) implements CustomPacketPayload {
        private static final Type<QueueStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "dungeon_queue_state"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, QueueStatePayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeUtf(payload.entranceId);
                    buffer.writeUtf(payload.state);
                },
                buffer -> new QueueStatePayload(buffer.readUtf(), buffer.readUtf())
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record QueueCancelPayload(String entranceId) implements CustomPacketPayload {
        private static final Type<QueueCancelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "dungeon_queue_cancel"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, QueueCancelPayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> buffer.writeUtf(payload.entranceId),
                buffer -> new QueueCancelPayload(buffer.readUtf())
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record OpenRewardsPayload(RewardData data) implements CustomPacketPayload {
        private static final Type<OpenRewardsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                CobbleventureBootstrap.MOD_ID, "open_dungeon_rewards"
            )
        );
        private static final StreamCodec<RegistryFriendlyByteBuf, OpenRewardsPayload> STREAM_CODEC =
            StreamCodec.of(
                (buffer, payload) -> payload.data.write(buffer),
                buffer -> new OpenRewardsPayload(RewardData.read(buffer))
            );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
