package dev.buizz.cobbleventure.playermenu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Synchronizes the server-owned quest summary and full quest log to player menu screens. */
public final class QuestSummaryNetwork {
    private static final String VERSION = "2";
    private static volatile QuestSummary clientSummary = QuestSummary.empty();
    private static volatile List<QuestEntry> clientEntries = List.of();
    private static volatile long clientRevision;

    private QuestSummaryNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(QuestSummaryNetwork::registerPayloads);
    }

    public static QuestSummary clientSummary() {
        return clientSummary;
    }

    public static List<QuestEntry> clientEntries() {
        return clientEntries;
    }

    public static long clientRevision() {
        return clientRevision;
    }

    public static void requestSnapshot() {
        PacketDistributor.sendToServer(new RequestPayload());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.STREAM_CODEC,
            QuestSummaryNetwork::handleRequest);
        registrar.playToClient(SnapshotPayload.TYPE, SnapshotPayload.STREAM_CODEC,
            QuestSummaryNetwork::handleSnapshot);
    }

    private static void handleRequest(RequestPayload payload, IPayloadContext context) {
        context.reply(resolveSnapshot((ServerPlayer) context.player()));
    }

    private static void handleSnapshot(SnapshotPayload payload, IPayloadContext context) {
        clientSummary = payload.summary();
        clientEntries = List.copyOf(payload.entries());
        clientRevision++;
    }

    /**
     * Keeps the menu module independent of the adventure and world modules at compile time.
     * Both are runtime integrations and expose read-only records for this purpose.
     */
    private static SnapshotPayload resolveSnapshot(ServerPlayer player) {
        QuestSummary summary = resolveSummary(player);
        List<QuestEntry> entries = new ArrayList<>(authoredQuestLog(player));
        if ("gym".equals(summary.kind())) {
            entries.addFirst(new QuestEntry(
                "default:gym/" + summary.target(), "main", summary.title(), "",
                summary.state(), "automatic", summary.target(),
                List.of(new QuestObjective("gym_challenge", summary.objective(), false))
            ));
        }
        return new SnapshotPayload(summary, List.copyOf(entries));
    }

    private static QuestSummary resolveSummary(ServerPlayer player) {
        QuestSummary authored = authoredQuest(player);
        if (authored.available()) return authored;
        return gymObjective(player);
    }

    private static List<QuestEntry> authoredQuestLog(ServerPlayer player) {
        try {
            Class<?> service = Class.forName(
                "dev.buizz.cobbleventure.adventure.quest.QuestService"
            );
            Method questLog = service.getMethod("questLog", ServerPlayer.class);
            List<?> values = (List<?>) questLog.invoke(null, player);
            List<QuestEntry> result = new ArrayList<>(values.size());
            for (Object value : values) {
                List<?> objectiveValues = list(value, "objectives");
                List<QuestObjective> objectives = new ArrayList<>(objectiveValues.size());
                for (Object objective : objectiveValues) {
                    objectives.add(new QuestObjective(
                        string(objective, "id"),
                        string(objective, "text"),
                        bool(objective, "completed")
                    ));
                }
                result.add(new QuestEntry(
                    string(value, "questId"),
                    string(value, "category"),
                    string(value, "displayName"),
                    string(value, "summary"),
                    string(value, "state"),
                    string(value, "completionMode"),
                    string(value, "target"),
                    objectives
                ));
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return List.of();
        }
    }

    private static QuestSummary authoredQuest(ServerPlayer player) {
        try {
            Class<?> service = Class.forName(
                "dev.buizz.cobbleventure.adventure.quest.QuestService"
            );
            Method primary = service.getMethod("primaryMainQuest", ServerPlayer.class);
            Optional<?> value = (Optional<?>) primary.invoke(null, player);
            if (value.isEmpty()) return QuestSummary.empty();
            Object quest = value.get();
            return new QuestSummary(
                "main",
                string(quest, "displayName"),
                string(quest, "summary"),
                string(quest, "objectiveText"),
                string(quest, "state"),
                string(quest, "npcId")
            );
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return QuestSummary.empty();
        }
    }

    private static QuestSummary gymObjective(ServerPlayer player) {
        try {
            Class<?> catalog = Class.forName(
                "dev.buizz.cobbleventure.bootstrap.RadarLocationCatalog"
            );
            Method objectives = catalog.getMethod("objectiveLocations", ServerPlayer.class);
            List<?> values = (List<?>) objectives.invoke(null, player);
            if (values.isEmpty()) return QuestSummary.empty();
            Object objective = values.getFirst();
            String label = string(objective, "label");
            return new QuestSummary(
                "gym", label, "", label, "active", string(objective, "areaId")
            );
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return QuestSummary.empty();
        }
    }

    private static String string(Object value, String accessor) throws ReflectiveOperationException {
        Object result = value.getClass().getMethod(accessor).invoke(value);
        return result == null ? "" : result.toString();
    }

    private static boolean bool(Object value, String accessor) throws ReflectiveOperationException {
        return (boolean) value.getClass().getMethod(accessor).invoke(value);
    }

    private static List<?> list(Object value, String accessor) throws ReflectiveOperationException {
        return (List<?>) value.getClass().getMethod(accessor).invoke(value);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobbleventurePlayerMenu.MOD_ID, path);
    }

    public record QuestSummary(
        String kind,
        String title,
        String summary,
        String objective,
        String state,
        String target
    ) {
        public QuestSummary {
            kind = sanitize(kind, 24);
            title = sanitize(title, 256);
            summary = sanitize(summary, 1024);
            objective = sanitize(objective, 512);
            state = sanitize(state, 24);
            target = sanitize(target, 256);
        }

        public boolean available() {
            return !title.isBlank();
        }

        static QuestSummary empty() {
            return new QuestSummary("none", "", "", "", "", "");
        }

    }

    public record QuestEntry(
        String id,
        String category,
        String title,
        String summary,
        String state,
        String completionMode,
        String target,
        List<QuestObjective> objectives
    ) {
        public QuestEntry {
            id = sanitize(id, 256);
            category = sanitize(category, 24);
            title = sanitize(title, 256);
            summary = sanitize(summary, 1024);
            state = sanitize(state, 24);
            completionMode = sanitize(completionMode, 32);
            target = sanitize(target, 256);
            objectives = List.copyOf(objectives).stream().limit(64).toList();
        }
    }

    public record QuestObjective(String id, String text, boolean completed) {
        public QuestObjective {
            id = sanitize(id, 128);
            text = sanitize(text, 512);
        }
    }

    private static String sanitize(String value, int maximumLength) {
        if (value == null) return "";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public record RequestPayload() implements CustomPacketPayload {
        static final Type<RequestPayload> TYPE = new Type<>(id("quest_summary_request"));
        static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestPayload());

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SnapshotPayload(
        QuestSummary summary, List<QuestEntry> entries
    ) implements CustomPacketPayload {
        public SnapshotPayload {
            entries = List.copyOf(entries).stream().limit(256).toList();
        }
        static final Type<SnapshotPayload> TYPE = new Type<>(id("quest_summary_snapshot"));
        static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(SnapshotPayload::write, SnapshotPayload::read);

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUtf(summary.kind(), 24);
            buffer.writeUtf(summary.title(), 256);
            buffer.writeUtf(summary.summary(), 1024);
            buffer.writeUtf(summary.objective(), 512);
            buffer.writeUtf(summary.state(), 24);
            buffer.writeUtf(summary.target(), 256);
            buffer.writeVarInt(entries.size());
            for (QuestEntry entry : entries) writeEntry(buffer, entry);
        }

        private static SnapshotPayload read(RegistryFriendlyByteBuf buffer) {
            QuestSummary summary = new QuestSummary(
                buffer.readUtf(24),
                buffer.readUtf(256),
                buffer.readUtf(1024),
                buffer.readUtf(512),
                buffer.readUtf(24),
                buffer.readUtf(256)
            );
            int size = Math.max(0, Math.min(256, buffer.readVarInt()));
            List<QuestEntry> entries = new ArrayList<>(size);
            for (int index = 0; index < size; index++) entries.add(readEntry(buffer));
            return new SnapshotPayload(summary, entries);
        }

        private static void writeEntry(RegistryFriendlyByteBuf buffer, QuestEntry entry) {
            buffer.writeUtf(entry.id(), 256);
            buffer.writeUtf(entry.category(), 24);
            buffer.writeUtf(entry.title(), 256);
            buffer.writeUtf(entry.summary(), 1024);
            buffer.writeUtf(entry.state(), 24);
            buffer.writeUtf(entry.completionMode(), 32);
            buffer.writeUtf(entry.target(), 256);
            buffer.writeVarInt(entry.objectives().size());
            for (QuestObjective objective : entry.objectives()) {
                buffer.writeUtf(objective.id(), 128);
                buffer.writeUtf(objective.text(), 512);
                buffer.writeBoolean(objective.completed());
            }
        }

        private static QuestEntry readEntry(RegistryFriendlyByteBuf buffer) {
            String id = buffer.readUtf(256);
            String category = buffer.readUtf(24);
            String title = buffer.readUtf(256);
            String summary = buffer.readUtf(1024);
            String state = buffer.readUtf(24);
            String completionMode = buffer.readUtf(32);
            String target = buffer.readUtf(256);
            int size = Math.max(0, Math.min(64, buffer.readVarInt()));
            List<QuestObjective> objectives = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                objectives.add(new QuestObjective(
                    buffer.readUtf(128), buffer.readUtf(512), buffer.readBoolean()
                ));
            }
            return new QuestEntry(
                id, category, title, summary, state, completionMode, target, objectives
            );
        }

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
