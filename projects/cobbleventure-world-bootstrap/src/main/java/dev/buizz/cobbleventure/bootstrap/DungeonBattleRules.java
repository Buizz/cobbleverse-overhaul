package dev.buizz.cobbleventure.bootstrap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server-authoritative permission checks for actions taken during dungeon battles. */
public final class DungeonBattleRules {
    private DungeonBattleRules() {}

    public static boolean allowsFlee(ServerPlayer player) {
        DungeonDefinition.BattleRules rules = DungeonSystem.activeBattleRules(player);
        if (rules == null || rules.allowFlee()) return true;
        player.sendSystemMessage(Component.literal(
            "이 던전의 전투에서는 도망칠 수 없습니다."
        ));
        return false;
    }

    public static boolean allowsCapture(ServerPlayer player) {
        DungeonDefinition.BattleRules rules = DungeonSystem.activeBattleRules(player);
        if (rules == null || rules.allowCapture()) return true;
        player.sendSystemMessage(Component.literal(
            "이 던전에서는 포켓몬을 포획할 수 없습니다."
        ));
        return false;
    }

    public static boolean allowsBattleItems(ServerPlayer player) {
        DungeonDefinition.BattleRules rules = DungeonSystem.activeBattleRules(player);
        if (rules == null || rules.allowItems()) return true;
        player.sendSystemMessage(Component.literal(
            "이 던전의 전투에서는 가방 아이템을 사용할 수 없습니다."
        ));
        return false;
    }
}
