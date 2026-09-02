package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonParser;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.mojang.logging.LogUtils;
import dev.buizz.cobbleventure.adventure.quest.QuestDefinition;
import dev.buizz.cobbleventure.adventure.quest.QuestHookJournal;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.StreamSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/** Queues quest transitions behind existing CVES work and uses the normal await adapters. */
public final class QuestEventHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String JOURNAL = "cobbleventureQuestEventHooks";
    private QuestEventHooks() {}

    public static void enqueue(ServerPlayer player, String key, QuestDefinition.EventHook hook) {
        if (hook == null) return;
        QuestHookJournal journal = read(player);
        if (journal.enqueue(key, hook)) write(player, journal);
    }

    public static void tick(ServerPlayer player) {
        QuestHookJournal journal = read(player);
        if (journal.entries().isEmpty() || !player.isAlive()) return;
        EventSessionStore store = SavedEventSessionStore.get(player.getServer());
        // Observe/continue only this player's running hook. Never restart a terminal session.
        for (QuestHookJournal.Entry entry : journal.entries()) {
            if (entry.status() != QuestHookJournal.Status.RUNNING) continue;
            EventSession session = store.find(key(player, entry, UUID.fromString(entry.npcUuid()))).orElse(null);
            if (session != null && expireOverdue(session, System.currentTimeMillis())) store.save(session);
            if (session == null) {
                update(player, entry, QuestHookJournal.Status.FAILED, "session_missing");
            } else if (session.status() == EventSession.Status.RUNNING || session.status() == EventSession.Status.READY) {
                run(player, entry, session, store);
            } else if (!active(session)) {
                update(player, entry, session.status() == EventSession.Status.COMPLETED
                    ? QuestHookJournal.Status.COMPLETED : QuestHookJournal.Status.FAILED, session.status().name());
            }
            return;
        }
        QuestHookJournal.Entry entry = journal.entries().stream()
            .filter(value -> value.status() == QuestHookJournal.Status.PENDING).findFirst().orElse(null);
        if (entry == null) return;
        // Recover a persisted session before the busy check: never replay even a terminal one.
        EventSession existing = findExisting(store, player.getUUID(), entry);
        if (existing != null) {
            QuestHookJournal.Entry recovered = new QuestHookJournal.Entry(entry.key(), entry.hook(),
                QuestHookJournal.Status.RUNNING, existing.key().npcId().toString(), "");
            update(player, recovered, QuestHookJournal.Status.RUNNING, "recovered_session");
            return;
        }
        if (EventAwaitInputLockService.isLocked(player.getUUID()) || hasActiveSession(store, player.getUUID())
            || BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null) return;
        EventScript script = EventScriptRepository.instance().find(entry.hook().scriptId()).orElse(null);
        if (script == null) { update(player, entry, QuestHookJournal.Status.FAILED, "script_missing"); return; }
        var events = script.events().stream().filter(event -> event.trigger().name().equals("quest")).toList();
        if (events.size() != 1) { update(player, entry, QuestHookJournal.Status.FAILED, "requires_one_quest_event"); return; }
        String npcTag = "cobbleventure_npc/" + entry.hook().npcId().replace(':', '/');
        Entity npc = StreamSupport.stream(player.serverLevel().getAllEntities().spliterator(), false)
            .filter(entity -> entity.isAlive() && entity.getTags().contains(npcTag))
            .min(Comparator.comparingDouble((Entity entity) -> entity.distanceToSqr(player))
                .thenComparing(entity -> entity.getUUID().toString())).orElse(null);
        // FIFO remains pending until the authored NPC is loaded in the player's dimension.
        if (npc == null) return;
        EventStateExpressionEnvironment environment = new EventStateExpressionEnvironment(new ServerPlayerEventState(player));
        java.util.Optional<EventSession> session;
        try {
            session = EventInterpreter.startSession(script, events.getFirst().index(), key(player, entry, npc.getUUID()), environment, store);
        } catch (RuntimeException error) {
            update(player, entry, QuestHookJournal.Status.FAILED, "start_failed");
            LOGGER.error("Quest hook could not start: player={}, hook={}", player.getUUID(), entry.key(), error);
            return;
        }
        if (session.isEmpty()) { update(player, entry, QuestHookJournal.Status.SKIPPED, "no_matching_page"); return; }
        QuestHookJournal.Entry running = new QuestHookJournal.Entry(entry.key(), entry.hook(),
            QuestHookJournal.Status.RUNNING, npc.getUUID().toString(), "");
        update(player, running, QuestHookJournal.Status.RUNNING, "");
        run(player, running, session.orElseThrow(), store);
    }

    private static boolean active(EventSession session) {
        return session.status() == EventSession.Status.READY || session.status() == EventSession.Status.RUNNING
            || session.status() == EventSession.Status.WAITING;
    }
    static boolean hasActiveSession(EventSessionStore store, UUID playerId) {
        return store.sessions().stream().anyMatch(session -> session.key().playerId().equals(playerId) && active(session));
    }
    static boolean expireOverdue(EventSession session, long now) {
        EventSession.AwaitState awaiting = session.awaiting();
        if (session.status() != EventSession.Status.WAITING || awaiting == null
            || awaiting.expiresAtEpochMilli() <= 0 || now < awaiting.expiresAtEpochMilli()) return false;
        // A lost callback must not keep every later quest hook blocked indefinitely.
        session.terminateAwait(awaiting.token(), EventSession.CompletionKind.FAILED);
        return true;
    }
    static EventSession findExisting(EventSessionStore store, UUID playerId, QuestHookJournal.Entry entry) {
        return store.sessions().stream().filter(session -> session.key().playerId().equals(playerId)
            && session.key().scriptId().equals(entry.hook().scriptId())
            && session.key().triggerInstance().equals("quest_hook:" + entry.key())).findFirst().orElse(null);
    }
    private static EventSessionKey key(ServerPlayer player, QuestHookJournal.Entry entry, UUID npc) {
        return new EventSessionKey(player.getUUID(), npc, entry.hook().scriptId(), "quest_hook:" + entry.key());
    }
    private static void run(ServerPlayer player, QuestHookJournal.Entry entry, EventSession session, EventSessionStore store) {
        try {
            EventScript script = EventScriptRepository.instance().find(entry.hook().scriptId()).orElseThrow();
            if (session.status() == EventSession.Status.READY) session.start();
            EventInterpreter.run(script, session, new EventStateExpressionEnvironment(new ServerPlayerEventState(player)),
                EventDialogueNetwork.serverAdapter(player), store, 10_000);
        } catch (RuntimeException error) {
            session.terminate(EventSession.CompletionKind.FAILED); store.save(session);
            update(player, entry, QuestHookJournal.Status.FAILED, "execution_failed");
            LOGGER.error("Quest hook failed: player={}, hook={}", player.getUUID(), entry.key(), error);
        }
    }
    private static void update(ServerPlayer player, QuestHookJournal.Entry entry, QuestHookJournal.Status status, String detail) {
        // Re-read: a quest command executed above may have appended another hook.
        QuestHookJournal journal = read(player);
        journal.update(entry.key(), status, entry.npcUuid(), detail);
        write(player, journal);
        if (status == QuestHookJournal.Status.FAILED)
            LOGGER.warn("Quest hook stopped: player={}, hook={}, reason={}", player.getUUID(), entry.key(), detail);
    }
    private static QuestHookJournal read(ServerPlayer player) {
        String source = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getString(JOURNAL);
        return source.isEmpty() ? new QuestHookJournal() : QuestHookJournal.fromJson(JsonParser.parseString(source).getAsJsonObject());
    }
    private static void write(ServerPlayer player, QuestHookJournal journal) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putString(JOURNAL, journal.toJson().toString());
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }
}
