package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

final class PlayingCardsTableOwnerProcessorTest {
    @Test
    void suppliesOwnerDataWhenStructureNbtHasNone() {
        CompoundTag source = new CompoundTag();
        source.putString("custom", "preserved");

        CompoundTag result = PlayingCardsTableOwnerData.withOwner(source);

        assertNotSame(source, result);
        assertTrue(result.hasUUID("OwnerID"));
        assertEquals("Cobbleventure Casino", result.getString("OwnerName"));
        assertTrue(result.getCompound("NeoForgeData").getBoolean(
            PlayingCardsTableOwnerData.CASINO_TABLE_MARKER
        ));
        assertEquals("preserved", result.getString("custom"));
        assertTrue(!source.hasUUID("OwnerID"));
    }

    @Test
    void preservesAuthoredOwnerData() {
        UUID owner = UUID.fromString("c832b8c8-9573-4479-a813-2e54e614e912");
        CompoundTag source = new CompoundTag();
        source.putUUID("OwnerID", owner);
        source.putString("OwnerName", "DBingsu");

        CompoundTag result = PlayingCardsTableOwnerData.withOwner(source);

        assertNotSame(source, result);
        assertEquals(owner, result.getUUID("OwnerID"));
        assertTrue(result.getCompound("NeoForgeData").getBoolean(
            PlayingCardsTableOwnerData.CASINO_TABLE_MARKER
        ));
        assertEquals("DBingsu", result.getString("OwnerName"));
    }
}
