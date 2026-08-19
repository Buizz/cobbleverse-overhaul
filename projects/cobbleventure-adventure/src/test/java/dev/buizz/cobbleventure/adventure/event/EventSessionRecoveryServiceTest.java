package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventSessionRecoveryServiceTest {
    private static final String NEW_DIGEST = "b".repeat(64);

    @Test
    void auditDistinguishesSafeUpgradeBlockedAndTerminalSessions() {
        EventScript oldScript = EventScriptLoader.parse(EventScriptRuntimeTest.IR);
        EventScript changed = withDigest(oldScript, NEW_DIGEST);

        EventSession current = running(oldScript, 1);
        EventSession legacyCurrent = legacy(running(oldScript, 2));
        EventSession relocatable = running(oldScript, 3);
        EventSession legacyBlocked = legacy(running(oldScript, 4));
        EventSession terminal = running(oldScript, 5);
        terminal.finish();
        EventSession missingScript = running(oldScript, 6);

        assertEquals(
            EventSessionRecoveryService.Status.CURRENT,
            EventSessionRecoveryService.diagnose(current, oldScript).status()
        );
        assertEquals(
            EventSessionRecoveryService.Status.LEGACY_UPGRADABLE,
            EventSessionRecoveryService.diagnose(legacyCurrent, oldScript).status()
        );
        assertEquals(
            EventSessionRecoveryService.Status.RELOCATABLE,
            EventSessionRecoveryService.diagnose(relocatable, changed).status()
        );
        EventSessionRecoveryService.Diagnosis blocked =
            EventSessionRecoveryService.diagnose(legacyBlocked, changed);
        assertEquals(
            EventSessionRecoveryService.Status.LEGACY_DIGEST_MISMATCH, blocked.status()
        );
        assertTrue(blocked.requiresOperatorAction());
        assertEquals(
            EventSessionRecoveryService.Status.TERMINAL_RESTARTABLE,
            EventSessionRecoveryService.diagnose(terminal, changed).status()
        );
        assertEquals(
            EventSessionRecoveryService.Status.SCRIPT_MISSING,
            EventSessionRecoveryService.diagnose(missingScript, null).status()
        );
    }

    @Test
    void incompatibleStableIdProbeDoesNotMutateTheSession() {
        EventScript oldScript = EventScriptLoader.parse(EventScriptRuntimeTest.IR);
        EventSession session = running(oldScript, 1);
        EventScript incompatible = withoutCurrentInstructionId(oldScript);

        EventSessionRecoveryService.Diagnosis diagnosis =
            EventSessionRecoveryService.diagnose(session, incompatible);

        assertEquals(EventSessionRecoveryService.Status.INCOMPATIBLE, diagnosis.status());
        assertEquals(oldScript.sourceDigest(), session.sourceDigest());
        assertEquals(0, session.programCounter());
        assertEquals(EventSession.Status.RUNNING, session.status());
    }

    @Test
    void safeUpgradeChangesOnlyUnambiguousSessionsAndDiscardRequiresExactKey() {
        EventScript oldScript = EventScriptLoader.parse(EventScriptRuntimeTest.IR);
        EventScript changed = withDigest(oldScript, NEW_DIGEST);
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        EventSession legacyCurrent = legacy(running(oldScript, 1));
        EventSession relocatable = running(oldScript, 2);
        EventSession blocked = legacy(running(oldScript, 3));
        store.putIfAbsent(legacyCurrent);
        store.putIfAbsent(relocatable);
        store.putIfAbsent(blocked);

        EventSessionRecoveryService.UpgradeResult result =
            EventSessionRecoveryService.upgradeSafe(
                store, Map.of(oldScript.scriptId(), changed)
            );

        assertEquals(1, result.upgraded());
        assertEquals(0, result.unchanged());
        assertEquals(2, result.blocked());
        assertEquals(NEW_DIGEST, relocatable.sourceDigest());
        assertEquals(oldScript.sourceDigest(), legacyCurrent.sourceDigest());
        assertFalse(legacyCurrent.hasCompleteInstructionAnchors());
        assertFalse(EventSessionRecoveryService.discard(
            store,
            new EventSessionKey(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                blocked.key().npcId(), blocked.key().scriptId(), blocked.key().triggerInstance()
            )
        ));
        assertTrue(EventSessionRecoveryService.discard(store, blocked.key()));
        assertTrue(store.find(blocked.key()).isEmpty());
    }

    @Test
    void sameDigestLegacySessionIsPersistedWithVersionTwoAnchors() {
        EventScript script = EventScriptLoader.parse(EventScriptRuntimeTest.IR);
        InMemoryEventSessionStore store = new InMemoryEventSessionStore();
        EventSession legacy = legacy(running(script, 1));
        store.putIfAbsent(legacy);

        EventSessionRecoveryService.UpgradeResult result =
            EventSessionRecoveryService.upgradeSafe(
                store, Map.of(script.scriptId(), script)
            );

        assertEquals(1, result.upgraded());
        assertTrue(legacy.hasCompleteInstructionAnchors());
        assertEquals(2, legacy.toJson().get("schema_version").getAsInt());
        assertEquals(
            "reward/give_item",
            legacy.toJson().get("program_counter_instruction_id").getAsString()
        );
    }

    private static EventSession running(EventScript script, int playerSuffix) {
        EventSession session = EventSession.create(
            new EventSessionKey(
                UUID.fromString(String.format(
                    "00000000-0000-0000-0000-%012d", playerSuffix
                )),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                script.scriptId(), "interact"
            ),
            script, 0, 0
        );
        session.start();
        return session;
    }

    private static EventSession legacy(EventSession session) {
        JsonObject value = session.toJson();
        value.addProperty("schema_version", 1);
        value.remove("event_trigger_name");
        value.remove("program_counter_instruction_id");
        value.remove("call_stack_instruction_ids");
        return EventSession.fromJson(value);
    }

    private static EventScript withDigest(EventScript script, String digest) {
        return new EventScript(
            script.schemaVersion(), script.scriptId(), digest, script.events()
        );
    }

    private static EventScript withoutCurrentInstructionId(EventScript script) {
        EventScript.Event original = script.events().getFirst();
        List<EventScript.Instruction> instructions = new ArrayList<>(original.instructions());
        EventScript.Instruction first = instructions.getFirst();
        instructions.set(0, new EventScript.Instruction(
            first.address(), "renamed/instruction", first.operation(), first.rawPayload()
        ));
        EventScript.Event changedEvent = new EventScript.Event(
            original.index(), original.trigger(), original.pages(), instructions
        );
        return new EventScript(
            script.schemaVersion(), script.scriptId(), NEW_DIGEST, List.of(changedEvent)
        );
    }
}
