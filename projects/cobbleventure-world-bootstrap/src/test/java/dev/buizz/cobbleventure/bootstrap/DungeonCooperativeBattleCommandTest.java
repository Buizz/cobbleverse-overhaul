package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DungeonCooperativeBattleCommandTest {
    private static final UUID GRUNT = UUID.fromString(
        "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID PARTNER = UUID.fromString(
        "00000000-0000-0000-0000-000000000002"
    );

    @Test
    void buildsFourActorBattleWithTwoDistinctOpponentEntities() {
        assertEquals(
            "tbcs battle GEN_9_MULTI Red Leaf vs " + GRUNT
                + " as rctmod:grunt " + PARTNER + " as rctmod:grunt",
            DungeonCooperativeBattleCommand.build(
                "Red", "Leaf", List.of(GRUNT, PARTNER),
                List.of("rctmod:grunt", "rctmod:grunt"), true
            )
        );
    }

    @Test
    void disablesItemsThroughTbcsRules() {
        assertEquals(
            "tbcs battle GEN_9_MULTI Red Leaf vs " + GRUNT
                + " as rctmod:grunt " + PARTNER
                + " as rctmod:officer rules {maxItemUses:0}",
            DungeonCooperativeBattleCommand.build(
                "Red", "Leaf", List.of(GRUNT, PARTNER),
                List.of("rctmod:grunt", "rctmod:officer"), false
            )
        );
    }

    @Test
    void rejectsReusingOneEntityForBothOpponentActors() {
        assertThrows(IllegalArgumentException.class, () ->
            DungeonCooperativeBattleCommand.build(
                "Red", "Leaf", List.of(GRUNT, GRUNT),
                List.of("rctmod:grunt", "rctmod:officer"), true
            )
        );
    }
}
