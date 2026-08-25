package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DungeonCooperativeBattleCommandTest {
    @Test
    void buildsFourActorBattleWithVisiblePrimaryOpponent() {
        assertEquals(
            "tbcs battle GEN_9_MULTI Red Leaf vs @s as rctmod:grunt rctmod:grunt",
            DungeonCooperativeBattleCommand.build(
                "Red", "Leaf", List.of("rctmod:grunt", "rctmod:grunt"), true
            )
        );
    }

    @Test
    void disablesItemsThroughTbcsRules() {
        assertEquals(
            "tbcs battle GEN_9_MULTI Red Leaf vs @s as rctmod:grunt rctmod:officer"
                + " rules {maxItemUses:0}",
            DungeonCooperativeBattleCommand.build(
                "Red", "Leaf", List.of("rctmod:grunt", "rctmod:officer"), false
            )
        );
    }
}
