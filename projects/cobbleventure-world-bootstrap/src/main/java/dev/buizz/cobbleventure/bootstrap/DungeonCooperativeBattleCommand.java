package dev.buizz.cobbleventure.bootstrap;

import java.util.List;

/** Builds the fixed four-actor TBCS command used by cooperative dungeon encounters. */
final class DungeonCooperativeBattleCommand {
    private DungeonCooperativeBattleCommand() {}

    static String build(
        String firstPlayer,
        String secondPlayer,
        List<String> opponentTrainerIds,
        boolean allowItems
    ) {
        if (firstPlayer == null || firstPlayer.isBlank()
            || secondPlayer == null || secondPlayer.isBlank()) {
            throw new IllegalArgumentException("Cooperative battle requires two players");
        }
        if (opponentTrainerIds == null || opponentTrainerIds.size() != 2
            || opponentTrainerIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Cooperative battle requires two trainers");
        }
        String command = "tbcs battle GEN_9_MULTI " + firstPlayer + " " + secondPlayer
            + " vs @s as " + opponentTrainerIds.get(0) + " "
            + opponentTrainerIds.get(1);
        return allowItems ? command : command + " rules {maxItemUses:0}";
    }
}
