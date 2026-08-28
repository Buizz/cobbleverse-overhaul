package dev.buizz.cobbleventure.bootstrap;

import java.util.List;
import java.util.UUID;

/** Builds the fixed four-actor TBCS command used by cooperative dungeon encounters. */
final class DungeonCooperativeBattleCommand {
    private DungeonCooperativeBattleCommand() {}

    static String build(
        String firstPlayer,
        String secondPlayer,
        List<UUID> opponentEntityIds,
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
        if (opponentEntityIds == null || opponentEntityIds.size() != 2
            || opponentEntityIds.stream().anyMatch(java.util.Objects::isNull)
            || opponentEntityIds.get(0).equals(opponentEntityIds.get(1))) {
            throw new IllegalArgumentException(
                "Cooperative battle requires two distinct trainer entities"
            );
        }
        String command = "tbcs battle GEN_9_MULTI " + firstPlayer + " " + secondPlayer
            + " vs " + opponentEntityIds.get(0) + " as "
            + opponentTrainerIds.get(0) + " " + opponentEntityIds.get(1)
            + " as " + opponentTrainerIds.get(1);
        return allowItems ? command : command + " rules {maxItemUses:0}";
    }
}
