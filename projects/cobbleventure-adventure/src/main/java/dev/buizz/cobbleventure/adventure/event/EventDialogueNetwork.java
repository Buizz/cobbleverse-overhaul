package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.CobbleventureAdventure;
import dev.buizz.cobbleventure.adventure.event.client.EventDialogueClient;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/** NeoForge transport for CVES dialogue and structured choice screens. */
public final class EventDialogueNetwork {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String VERSION = "5";
    private static final int MAX_JSON_LENGTH = 65_535;
    private static final long DIALOGUE_TIMEOUT_MILLIS = 5L * 60L * 1000L;

    private EventDialogueNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(EventDialogueNetwork::registerPayloads);
    }

    public static EventDialogueGateway gateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException("대화 요청의 player와 gateway player가 다릅니다.");
            }
            String token = UUID.randomUUID().toString();
            JsonObject locals = locals(player, request.locals());
            PacketDistributor.sendToPlayer(player, new OpenPayload(
                token,
                request.sessionKey().npcId(),
                request.sessionKey().scriptId(),
                request.sessionKey().triggerInstance(),
                request.kind() == EventDialogueGateway.Kind.NARRATE,
                request.kind() == EventDialogueGateway.Kind.NARRATE
                    ? "system" : request.speaker(),
                speakerName(player, request),
                request.text().toString(),
                locals.toString(),
                EventDialogueThemeRepository.snapshot()
            ));
            EventDialogueLifecycle.opened(player, request.sessionKey());
            return new EventDialogueGateway.OpenResult(
                token, System.currentTimeMillis() + DIALOGUE_TIMEOUT_MILLIS
            );
        };
    }

    public static EventChoiceGateway choiceGateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException("선택지 요청의 player와 gateway player가 다릅니다.");
            }
            String token = UUID.randomUUID().toString();
            JsonArray options = new JsonArray();
            request.options().forEach(options::add);
            PacketDistributor.sendToPlayer(player, new ChoiceOpenPayload(
                token,
                request.sessionKey().npcId(),
                request.sessionKey().scriptId(),
                request.sessionKey().triggerInstance(),
                request.prompt().toString(),
                options.toString(),
                locals(player, request.locals()).toString(),
                EventDialogueThemeRepository.snapshot()
            ));
            return new EventChoiceGateway.OpenResult(
                token, System.currentTimeMillis() + DIALOGUE_TIMEOUT_MILLIS
            );
        };
    }

    public static EventNumberInputGateway numberInputGateway(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return request -> {
            if (!request.sessionKey().playerId().equals(player.getUUID())) {
                throw new EventRuntimeException("number input player mismatch");
            }
            String token = UUID.randomUUID().toString();
            PacketDistributor.sendToPlayer(player, new NumberInputOpenPayload(
                token, request.sessionKey().npcId(), request.sessionKey().scriptId(),
                request.sessionKey().triggerInstance(), request.minimum(), request.maximum()
            ));
            return new EventNumberInputGateway.OpenResult(
                token, System.currentTimeMillis() + DIALOGUE_TIMEOUT_MILLIS
            );
        };
    }

    static void setMovementInputLocked(ServerPlayer player, boolean locked) {
        setAwaitInputLocked(player, "movement", locked);
    }

    static void setAwaitInputLocked(ServerPlayer player, String kind, boolean locked) {
        PacketDistributor.sendToPlayer(player, new MovementLockPayload(kind, locked));
    }

    static void setFade(
        ServerPlayer player, EventPresentationGateway.FadeColor color, boolean visible
    ) {
        PacketDistributor.sendToPlayer(player, new FadePayload(color, visible));
    }

    static EventCommandAdapter serverAdapter(ServerPlayer player) {
        ServerPlayerEventState state = new ServerPlayerEventState(player);
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(state);
        EventCommandAdapter unsupported = context -> {
            String command = context.instruction().command();
            throw new EventRuntimeException(
                "아직 서버에 연결되지 않은 CVES 명령입니다: "
                    + (command == null ? context.instruction().operation() : command)
            );
        };
        return new EventAwaitInputLockAdapter(player, new NumberInputEventCommandAdapter(
            numberInputGateway(player),
            environment,
            new DialogueEventCommandAdapter(
            gateway(player),
            new ChoiceEventCommandAdapter(
                choiceGateway(player),
                new TeleportEventCommandAdapter(
                    EventMovementBridge.gateway(player),
                    environment,
                    new EncounterWarningEventCommandAdapter(
                        player,
                        environment,
                        new FaceEventCommandAdapter(
                            EventFacingBridge.gateway(player),
                            new PresentationEventCommandAdapter(
                                EventPresentationBridge.gateway(player),
                                environment,
                                new BattleEventCommandAdapter(
                                    EventBattleBridge.gateway(player),
                                    environment,
                                    new StarterRouletteEventCommandAdapter(
                                        EventStarterRouletteBridge.gateway(player),
                                        new MapSelectionEventCommandAdapter(
                                            EventMapSelectionBridge.gateway(player),
                                            new GiveItemEventCommandAdapter(
                                                EventItemGrantBridge.gateway(player),
                                                environment,
                                                new HealPartyEventCommandAdapter(
                                                    EventHealingBridge.gateway(player),
                                                    new GiveLootEventCommandAdapter(
                                                        EventLootGrantBridge.gateway(player),
                                                        environment,
                                                        new StateEventCommandAdapter(
                                                            environment,
                                                            new ServerCommandEventCommandAdapter(
                                                                player, environment, unsupported
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.STREAM_CODEC,
            EventDialogueNetwork::handleOpen);
        registrar.playToServer(CompletePayload.TYPE, CompletePayload.STREAM_CODEC,
            EventDialogueNetwork::handleComplete);
        registrar.playToClient(ChoiceOpenPayload.TYPE, ChoiceOpenPayload.STREAM_CODEC,
            EventDialogueNetwork::handleChoiceOpen);
        registrar.playToServer(ChoiceCompletePayload.TYPE, ChoiceCompletePayload.STREAM_CODEC,
            EventDialogueNetwork::handleChoiceComplete);
        registrar.playToClient(NumberInputOpenPayload.TYPE, NumberInputOpenPayload.STREAM_CODEC,
            EventDialogueNetwork::handleNumberInputOpen);
        registrar.playToServer(NumberInputCompletePayload.TYPE, NumberInputCompletePayload.STREAM_CODEC,
            EventDialogueNetwork::handleNumberInputComplete);
        registrar.playToClient(MovementLockPayload.TYPE, MovementLockPayload.STREAM_CODEC,
            EventDialogueNetwork::handleMovementLock);
        registrar.playToClient(FadePayload.TYPE, FadePayload.STREAM_CODEC,
            EventDialogueNetwork::handleFade);
    }

    private static void handleOpen(OpenPayload payload, IPayloadContext context) {
        EventDialogueClient.open(payload);
    }

    private static void handleChoiceOpen(ChoiceOpenPayload payload, IPayloadContext context) {
        EventDialogueClient.openChoice(payload);
    }

    private static void handleNumberInputOpen(
        NumberInputOpenPayload payload, IPayloadContext context
    ) {
        EventDialogueClient.openNumberInput(payload);
    }

    private static void handleNumberInputComplete(
        NumberInputCompletePayload payload, IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        EventSessionKey key = new EventSessionKey(
            player.getUUID(), payload.npcId(), payload.scriptId(), payload.triggerInstance()
        );
        EventScript script = EventScriptRepository.instance().find(payload.scriptId()).orElse(null);
        if (script == null) return;
        EventSessionStore store = SavedEventSessionStore.get(player.getServer());
        EventSession session = store.find(key).orElse(null);
        if (session == null || session.awaiting() == null
            || !"number_input".equals(session.awaiting().kind())
            || !payload.token().equals(session.awaiting().token())) {
            return;
        }
        EventSession.AwaitCompletion completion;
        if (payload.cancelled()) {
            completion = new EventSession.AwaitCompletion(
                EventSession.CompletionKind.CANCELLED, new JsonPrimitive("client_cancelled")
            );
        } else {
            EventScript.Instruction instruction = script.events().get(session.eventIndex())
                .instruction(session.programCounter());
            EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
                new ServerPlayerEventState(player)
            );
            NumberInputEventCommandAdapter.Bounds bounds = NumberInputEventCommandAdapter.bounds(
                instruction, environment, session.locals()
            );
            if (!bounds.contains(payload.value())) {
                LOGGER.warn("Rejected CVES number input outside server bounds: player={}, value={}",
                    player.getGameProfile().getName(), payload.value());
                return;
            }
            completion = new EventSession.AwaitCompletion(
                EventSession.CompletionKind.COMPLETED, new JsonPrimitive(payload.value())
            );
        }
        EventAwaitCompletionService.completeAndRun(
            player.getUUID(), key, payload.token(), completion, script,
            new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
            serverAdapter(player), store, 10_000
        );
    }

    private static void handleMovementLock(
        MovementLockPayload payload, IPayloadContext context
    ) {
        EventDialogueClient.setAwaitInputLocked(payload.kind(), payload.locked());
    }

    private static void handleFade(FadePayload payload, IPayloadContext context) {
        EventDialogueClient.setFade(payload.color(), payload.visible());
    }

    private static void handleComplete(CompletePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        EventSessionKey key = new EventSessionKey(
            player.getUUID(), payload.npcId(), payload.scriptId(), payload.triggerInstance()
        );
        EventSessionStore sessionStore = SavedEventSessionStore.get(player.getServer());
        boolean activeDialogue = EventAwaitSessionLocator.find(
            sessionStore, player.getUUID(), payload.token()
        ).filter(key::equals).isPresent();
        if (activeDialogue) {
            EventDialogueLifecycle.closed(player, key);
        }
        EventScript script = EventScriptRepository.instance().find(payload.scriptId())
            .orElse(null);
        if (script == null) {
            LOGGER.warn("CVES dialogue callback script is unavailable: {}", payload.scriptId());
            return;
        }
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(
            new ServerPlayerEventState(player)
        );
        try {
            EventAwaitCompletionService.Outcome outcome =
                EventAwaitCompletionService.completeAndRun(
                    player.getUUID(),
                    key,
                    payload.token(),
                    new EventSession.AwaitCompletion(
                        payload.cancelled()
                            ? EventSession.CompletionKind.CANCELLED
                            : EventSession.CompletionKind.COMPLETED,
                        payload.cancelled()
                            ? new JsonPrimitive("client_cancelled")
                            : null
                    ),
                    script,
                    environment,
                    serverAdapter(player),
                    sessionStore,
                    10_000
                );
            if (outcome.status() != EventAwaitCompletionService.Status.RESUMED
                && outcome.status() != EventAwaitCompletionService.Status.DUPLICATE) {
                LOGGER.warn(
                    "CVES dialogue callback was not resumed: player={}, script={}, status={}",
                    player.getGameProfile().getName(), payload.scriptId(), outcome.status()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES dialogue callback failed: player={}, script={}",
                player.getGameProfile().getName(), payload.scriptId(), error
            );
        }
    }

    private static void handleChoiceComplete(
        ChoiceCompletePayload payload, IPayloadContext context
    ) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        EventSessionKey key = new EventSessionKey(
            player.getUUID(), payload.npcId(), payload.scriptId(), payload.triggerInstance()
        );
        EventScript script = EventScriptRepository.instance().find(payload.scriptId())
            .orElse(null);
        if (script == null) {
            LOGGER.warn("CVES choice callback script is unavailable: {}", payload.scriptId());
            return;
        }
        try {
            EventAwaitCompletionService.Outcome outcome =
                EventAwaitCompletionService.completeAndRun(
                    player.getUUID(),
                    key,
                    payload.token(),
                    new EventSession.AwaitCompletion(
                        payload.cancelled()
                            ? EventSession.CompletionKind.CANCELLED
                            : EventSession.CompletionKind.COMPLETED,
                        payload.cancelled()
                            ? new JsonPrimitive("client_cancelled")
                            : null,
                        payload.cancelled() ? null : payload.selectedIndex()
                    ),
                    script,
                    new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
                    serverAdapter(player),
                    SavedEventSessionStore.get(player.getServer()),
                    10_000
                );
            if (outcome.status() != EventAwaitCompletionService.Status.RESUMED
                && outcome.status() != EventAwaitCompletionService.Status.DUPLICATE) {
                LOGGER.warn(
                    "CVES choice callback was not resumed: player={}, script={}, status={}",
                    player.getGameProfile().getName(), payload.scriptId(), outcome.status()
                );
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                "CVES choice callback failed: player={}, script={}, selectedIndex={}",
                player.getGameProfile().getName(), payload.scriptId(),
                payload.selectedIndex(), error
            );
        }
    }

    private static JsonObject locals(
        ServerPlayer player, java.util.Map<String, com.google.gson.JsonElement> values
    ) {
        JsonObject locals = new JsonObject();
        values.forEach((name, value) -> locals.add(name, value));
        if (!locals.has("player")) {
            JsonObject playerValue = new JsonObject();
            playerValue.addProperty("name", player.getGameProfile().getName());
            locals.add("player", playerValue);
        }
        return locals;
    }

    private static String speakerName(
        ServerPlayer player, EventDialogueGateway.DialogueRequest request
    ) {
        if (request.kind() == EventDialogueGateway.Kind.NARRATE) return "";
        if ("player".equals(request.speaker())) return player.getGameProfile().getName();
        if (!"npc".equals(request.speaker())) return request.speaker();
        for (ServerLevel level : player.getServer().getAllLevels()) {
            Entity npc = level.getEntity(request.sessionKey().npcId());
            if (npc != null) return npc.getDisplayName().getString();
        }
        return "NPC";
    }

    public record OpenPayload(
        String token,
        UUID npcId,
        String scriptId,
        String triggerInstance,
        boolean narration,
        String speakerKind,
        String speaker,
        String textJson,
        String localsJson,
        String themeJson
    ) implements CustomPacketPayload {
        public static final Type<OpenPayload> TYPE = new Type<>(id("event_dialogue_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(OpenPayload::write, OpenPayload::read);

        public OpenPayload {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token이 필요합니다.");
            Objects.requireNonNull(npcId, "npcId");
            if (scriptId == null || scriptId.isBlank()) throw new IllegalArgumentException("scriptId가 필요합니다.");
            if (triggerInstance == null || triggerInstance.isBlank()) throw new IllegalArgumentException("triggerInstance가 필요합니다.");
            if (speakerKind == null || speakerKind.isBlank()) throw new IllegalArgumentException("speakerKind가 필요합니다.");
            speaker = speaker == null ? "" : speaker;
            Objects.requireNonNull(textJson, "textJson");
            Objects.requireNonNull(localsJson, "localsJson");
            Objects.requireNonNull(themeJson, "themeJson");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token);
            buffer.writeUUID(npcId);
            buffer.writeUtf(scriptId);
            buffer.writeUtf(triggerInstance);
            buffer.writeBoolean(narration);
            buffer.writeUtf(speakerKind);
            buffer.writeUtf(speaker);
            buffer.writeUtf(textJson, MAX_JSON_LENGTH);
            buffer.writeUtf(localsJson, MAX_JSON_LENGTH);
            buffer.writeUtf(themeJson, MAX_JSON_LENGTH);
        }

        private static OpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new OpenPayload(
                buffer.readUtf(),
                buffer.readUUID(),
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readBoolean(),
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readUtf(MAX_JSON_LENGTH),
                buffer.readUtf(MAX_JSON_LENGTH),
                buffer.readUtf(MAX_JSON_LENGTH)
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CompletePayload(
        String token,
        UUID npcId,
        String scriptId,
        String triggerInstance,
        boolean cancelled
    ) implements CustomPacketPayload {
        public static final Type<CompletePayload> TYPE = new Type<>(id("event_dialogue_complete"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CompletePayload> STREAM_CODEC =
            StreamCodec.ofMember(CompletePayload::write, CompletePayload::read);

        public CompletePayload {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token이 필요합니다.");
            Objects.requireNonNull(npcId, "npcId");
            if (scriptId == null || scriptId.isBlank()) throw new IllegalArgumentException("scriptId가 필요합니다.");
            if (triggerInstance == null || triggerInstance.isBlank()) throw new IllegalArgumentException("triggerInstance가 필요합니다.");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token);
            buffer.writeUUID(npcId);
            buffer.writeUtf(scriptId);
            buffer.writeUtf(triggerInstance);
            buffer.writeBoolean(cancelled);
        }

        private static CompletePayload read(RegistryFriendlyByteBuf buffer) {
            return new CompletePayload(
                buffer.readUtf(), buffer.readUUID(), buffer.readUtf(),
                buffer.readUtf(), buffer.readBoolean()
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChoiceOpenPayload(
        String token,
        UUID npcId,
        String scriptId,
        String triggerInstance,
        String promptJson,
        String optionsJson,
        String localsJson,
        String themeJson
    ) implements CustomPacketPayload {
        public static final Type<ChoiceOpenPayload> TYPE = new Type<>(id("event_choice_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChoiceOpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(ChoiceOpenPayload::write, ChoiceOpenPayload::read);

        public ChoiceOpenPayload {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token이 필요합니다.");
            Objects.requireNonNull(npcId, "npcId");
            if (scriptId == null || scriptId.isBlank()) throw new IllegalArgumentException("scriptId가 필요합니다.");
            if (triggerInstance == null || triggerInstance.isBlank()) throw new IllegalArgumentException("triggerInstance가 필요합니다.");
            Objects.requireNonNull(promptJson, "promptJson");
            Objects.requireNonNull(optionsJson, "optionsJson");
            Objects.requireNonNull(localsJson, "localsJson");
            Objects.requireNonNull(themeJson, "themeJson");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token);
            buffer.writeUUID(npcId);
            buffer.writeUtf(scriptId);
            buffer.writeUtf(triggerInstance);
            buffer.writeUtf(promptJson, MAX_JSON_LENGTH);
            buffer.writeUtf(optionsJson, MAX_JSON_LENGTH);
            buffer.writeUtf(localsJson, MAX_JSON_LENGTH);
            buffer.writeUtf(themeJson, MAX_JSON_LENGTH);
        }

        private static ChoiceOpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new ChoiceOpenPayload(
                buffer.readUtf(), buffer.readUUID(), buffer.readUtf(), buffer.readUtf(),
                buffer.readUtf(MAX_JSON_LENGTH), buffer.readUtf(MAX_JSON_LENGTH),
                buffer.readUtf(MAX_JSON_LENGTH),
                buffer.readUtf(MAX_JSON_LENGTH)
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChoiceCompletePayload(
        String token,
        UUID npcId,
        String scriptId,
        String triggerInstance,
        int selectedIndex,
        boolean cancelled
    ) implements CustomPacketPayload {
        public static final Type<ChoiceCompletePayload> TYPE = new Type<>(
            id("event_choice_complete")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, ChoiceCompletePayload> STREAM_CODEC =
            StreamCodec.ofMember(ChoiceCompletePayload::write, ChoiceCompletePayload::read);

        public ChoiceCompletePayload {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token이 필요합니다.");
            Objects.requireNonNull(npcId, "npcId");
            if (scriptId == null || scriptId.isBlank()) throw new IllegalArgumentException("scriptId가 필요합니다.");
            if (triggerInstance == null || triggerInstance.isBlank()) throw new IllegalArgumentException("triggerInstance가 필요합니다.");
            if (!cancelled && selectedIndex < 0) {
                throw new IllegalArgumentException("selectedIndex는 0 이상이어야 합니다.");
            }
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token);
            buffer.writeUUID(npcId);
            buffer.writeUtf(scriptId);
            buffer.writeUtf(triggerInstance);
            buffer.writeVarInt(selectedIndex);
            buffer.writeBoolean(cancelled);
        }

        private static ChoiceCompletePayload read(RegistryFriendlyByteBuf buffer) {
            return new ChoiceCompletePayload(
                buffer.readUtf(), buffer.readUUID(), buffer.readUtf(), buffer.readUtf(),
                buffer.readVarInt(), buffer.readBoolean()
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record NumberInputOpenPayload(
        String token, UUID npcId, String scriptId, String triggerInstance,
        int minimum, int maximum
    ) implements CustomPacketPayload {
        public static final Type<NumberInputOpenPayload> TYPE = new Type<>(id("event_number_input_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NumberInputOpenPayload> STREAM_CODEC =
            StreamCodec.ofMember(NumberInputOpenPayload::write, NumberInputOpenPayload::read);
        public NumberInputOpenPayload {
            if (token == null || token.isBlank() || scriptId == null || scriptId.isBlank()
                || triggerInstance == null || triggerInstance.isBlank() || minimum > maximum) {
                throw new IllegalArgumentException("invalid number input payload");
            }
            Objects.requireNonNull(npcId, "npcId");
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token); buffer.writeUUID(npcId); buffer.writeUtf(scriptId);
            buffer.writeUtf(triggerInstance); buffer.writeInt(minimum); buffer.writeInt(maximum);
        }
        private static NumberInputOpenPayload read(RegistryFriendlyByteBuf buffer) {
            return new NumberInputOpenPayload(
                buffer.readUtf(), buffer.readUUID(), buffer.readUtf(), buffer.readUtf(),
                buffer.readInt(), buffer.readInt()
            );
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record NumberInputCompletePayload(
        String token, UUID npcId, String scriptId, String triggerInstance,
        int value, boolean cancelled
    ) implements CustomPacketPayload {
        public static final Type<NumberInputCompletePayload> TYPE = new Type<>(id("event_number_input_complete"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NumberInputCompletePayload> STREAM_CODEC =
            StreamCodec.ofMember(NumberInputCompletePayload::write, NumberInputCompletePayload::read);
        public NumberInputCompletePayload {
            if (token == null || token.isBlank() || scriptId == null || scriptId.isBlank()
                || triggerInstance == null || triggerInstance.isBlank()) {
                throw new IllegalArgumentException("invalid number input completion");
            }
            Objects.requireNonNull(npcId, "npcId");
        }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(token); buffer.writeUUID(npcId); buffer.writeUtf(scriptId);
            buffer.writeUtf(triggerInstance); buffer.writeInt(value); buffer.writeBoolean(cancelled);
        }
        private static NumberInputCompletePayload read(RegistryFriendlyByteBuf buffer) {
            return new NumberInputCompletePayload(
                buffer.readUtf(), buffer.readUUID(), buffer.readUtf(), buffer.readUtf(),
                buffer.readInt(), buffer.readBoolean()
            );
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MovementLockPayload(
        String kind, boolean locked
    ) implements CustomPacketPayload {
        public static final Type<MovementLockPayload> TYPE = new Type<>(
            id("event_movement_lock")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, MovementLockPayload> STREAM_CODEC =
            StreamCodec.ofMember(MovementLockPayload::write, MovementLockPayload::read);

        public MovementLockPayload {
            kind = kind == null ? "" : kind;
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(kind);
            buffer.writeBoolean(locked);
        }

        private static MovementLockPayload read(RegistryFriendlyByteBuf buffer) {
            return new MovementLockPayload(buffer.readUtf(), buffer.readBoolean());
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record FadePayload(
        EventPresentationGateway.FadeColor color, boolean visible
    ) implements CustomPacketPayload {
        public static final Type<FadePayload> TYPE = new Type<>(id("event_fade"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FadePayload> STREAM_CODEC =
            StreamCodec.ofMember(FadePayload::write, FadePayload::read);

        public FadePayload {
            Objects.requireNonNull(color, "color");
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(color == EventPresentationGateway.FadeColor.WHITE);
            buffer.writeBoolean(visible);
        }

        private static FadePayload read(RegistryFriendlyByteBuf buffer) {
            return new FadePayload(
                buffer.readBoolean()
                    ? EventPresentationGateway.FadeColor.WHITE
                    : EventPresentationGateway.FadeColor.BLACK,
                buffer.readBoolean()
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventureAdventure.MOD_ID, path);
    }
}
