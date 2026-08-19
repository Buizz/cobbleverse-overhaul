package dev.buizz.cobbleventure.adventure.event;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Permission-gated diagnostics and explicit recovery operations for persisted CVES sessions. */
public final class EventSessionAdminCommands {
    private static final int DETAIL_LIMIT = 20;

    private EventSessionAdminCommands() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventSessionAdminCommands::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("cobbleventure_event")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("session")
                    .then(Commands.literal("audit").executes(context -> audit(
                        context.getSource()
                    )))
                    .then(Commands.literal("upgrade_safe").executes(context -> upgradeSafe(
                        context.getSource()
                    )))
                    .then(discardCommand()))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> discardCommand() {
        var confirm = Commands.literal("confirm").executes(context -> discard(
            context.getSource(),
            StringArgumentType.getString(context, "player_uuid"),
            StringArgumentType.getString(context, "npc_uuid"),
            StringArgumentType.getString(context, "script_id"),
            StringArgumentType.getString(context, "trigger_instance")
        ));
        var trigger = Commands.argument(
            "trigger_instance", StringArgumentType.word()
        ).then(confirm);
        var script = Commands.argument("script_id", StringArgumentType.word()).then(trigger);
        var npc = Commands.argument("npc_uuid", StringArgumentType.word()).then(script);
        var player = Commands.argument("player_uuid", StringArgumentType.word()).then(npc);
        return Commands.literal("discard").then(player);
    }

    private static int audit(CommandSourceStack source) {
        List<EventSessionRecoveryService.Diagnosis> diagnoses =
            EventSessionRecoveryService.audit(
                SavedEventSessionStore.get(source.getServer()),
                EventScriptRepository.instance().scripts()
            );
        Map<EventSessionRecoveryService.Status, Integer> counts = new EnumMap<>(
            EventSessionRecoveryService.Status.class
        );
        diagnoses.forEach(value -> counts.merge(value.status(), 1, Integer::sum));
        source.sendSuccess(() -> Component.literal(
            "CVES 세션 " + diagnoses.size() + "개: " + counts
        ), false);
        diagnoses.stream().filter(
            EventSessionRecoveryService.Diagnosis::requiresOperatorAction
        ).limit(DETAIL_LIMIT).forEach(value -> source.sendFailure(Component.literal(
            format(value)
        )));
        long blocked = diagnoses.stream().filter(
            EventSessionRecoveryService.Diagnosis::requiresOperatorAction
        ).count();
        if (blocked > DETAIL_LIMIT) {
            source.sendFailure(Component.literal(
                "추가 조치 필요 세션 " + (blocked - DETAIL_LIMIT) + "개는 출력에서 생략했습니다."
            ));
        }
        return diagnoses.size();
    }

    private static int upgradeSafe(CommandSourceStack source) {
        EventSessionRecoveryService.UpgradeResult result =
            EventSessionRecoveryService.upgradeSafe(
                SavedEventSessionStore.get(source.getServer()),
                EventScriptRepository.instance().scripts()
            );
        source.sendSuccess(() -> Component.literal(
            "CVES 세션 안전 승격: 변경=" + result.upgraded()
                + ", 유지=" + result.unchanged() + ", 차단=" + result.blocked()
        ), true);
        return result.upgraded();
    }

    private static int discard(
        CommandSourceStack source,
        String playerId,
        String npcId,
        String scriptId,
        String triggerInstance
    ) {
        EventSessionKey key;
        try {
            key = new EventSessionKey(
                UUID.fromString(playerId), UUID.fromString(npcId), scriptId, triggerInstance
            );
        } catch (IllegalArgumentException error) {
            source.sendFailure(Component.literal("세션 키가 올바르지 않습니다: " + error.getMessage()));
            return 0;
        }
        boolean removed = EventSessionRecoveryService.discard(
            SavedEventSessionStore.get(source.getServer()), key
        );
        if (!removed) {
            source.sendFailure(Component.literal("일치하는 CVES 세션이 없습니다."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "CVES 세션을 폐기했습니다. 다음 상호작용에서 현재 script로 새로 시작합니다: "
                + format(key)
        ), true);
        return 1;
    }

    private static String format(EventSessionRecoveryService.Diagnosis value) {
        return value.status() + " " + format(value.key()) + " — " + value.detail();
    }

    private static String format(EventSessionKey key) {
        return key.playerId() + " " + key.npcId() + " "
            + key.scriptId() + " " + key.triggerInstance();
    }
}
