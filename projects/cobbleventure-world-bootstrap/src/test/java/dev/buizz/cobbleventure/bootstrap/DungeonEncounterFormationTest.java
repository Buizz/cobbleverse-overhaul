package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class DungeonEncounterFormationTest {
    @Test
    void centersTwoOpponentsAndAlignsPlayersFiveBlocksAhead() {
        DungeonEncounterFormation.Formation formation =
            DungeonEncounterFormation.create(new BlockPos(10, 4, 20), 0.0F, 2);

        assertEquals(
            java.util.List.of(new BlockPos(11, 4, 20), new BlockPos(9, 4, 20)),
            formation.opponents()
        );
        assertEquals(
            java.util.List.of(new BlockPos(11, 4, 25), new BlockPos(9, 4, 25)),
            formation.players()
        );
        assertEquals(180.0F, formation.playerYaw());
    }

    @Test
    void rotatesTheWholeFormationWithTheEncounterYaw() {
        DungeonEncounterFormation.Formation formation =
            DungeonEncounterFormation.create(new BlockPos(10, 4, 20), 90.0F, 2);

        assertEquals(
            java.util.List.of(new BlockPos(10, 4, 21), new BlockPos(10, 4, 19)),
            formation.opponents()
        );
        assertEquals(
            java.util.List.of(new BlockPos(5, 4, 21), new BlockPos(5, 4, 19)),
            formation.players()
        );
        assertEquals(270.0F, formation.playerYaw());
    }
}
