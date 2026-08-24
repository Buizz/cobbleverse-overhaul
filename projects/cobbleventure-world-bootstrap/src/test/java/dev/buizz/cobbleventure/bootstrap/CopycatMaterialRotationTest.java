package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

class CopycatMaterialRotationTest {
    @Test
    void rotatesDirectionalMultiStateMaterialKeysWithTheStructure() {
        assertEquals(
            "top_northeast",
            StructurePlacementFixes.transformCopycatPartKey(
                "top_northwest", Mirror.NONE, Rotation.CLOCKWISE_90
            )
        );
        assertEquals(
            "top_southeast",
            StructurePlacementFixes.transformCopycatPartKey(
                "top_northwest", Mirror.NONE, Rotation.CLOCKWISE_180
            )
        );
        assertEquals(
            "top_southwest",
            StructurePlacementFixes.transformCopycatPartKey(
                "top_northwest", Mirror.NONE, Rotation.COUNTERCLOCKWISE_90
            )
        );
    }

    @Test
    void mirrorsDirectionalMultiStateMaterialKeysWithTheStructure() {
        assertEquals(
            "bottom_southwest",
            StructurePlacementFixes.transformCopycatPartKey(
                "bottom_northwest", Mirror.LEFT_RIGHT, Rotation.NONE
            )
        );
        assertEquals(
            "bottom_northeast",
            StructurePlacementFixes.transformCopycatPartKey(
                "bottom_northwest", Mirror.FRONT_BACK, Rotation.NONE
            )
        );
    }

    @Test
    void leavesRelativeAndNonDirectionalPartNamesUntouched() {
        assertEquals(
            "top_left",
            StructurePlacementFixes.transformCopycatPartKey(
                "top_left", Mirror.FRONT_BACK, Rotation.CLOCKWISE_90
            )
        );
        assertEquals(
            "bottom",
            StructurePlacementFixes.transformCopycatPartKey(
                "bottom", Mirror.LEFT_RIGHT, Rotation.CLOCKWISE_180
            )
        );
    }
}