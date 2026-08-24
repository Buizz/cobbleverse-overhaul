package dev.buizz.cobbleventure.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

final class PlayingCardsTableOwnerData {
    static final String CASINO_TABLE_MARKER = "cobbleventureBlackjackFacade";
    private static final UUID SYSTEM_OWNER = UUID.nameUUIDFromBytes(
        "cobbleventure:playingcards_table_owner".getBytes(StandardCharsets.UTF_8)
    );
    private static final String SYSTEM_OWNER_NAME = "Cobbleventure Casino";

    private PlayingCardsTableOwnerData() {
    }

    static CompoundTag withOwner(CompoundTag source) {
        CompoundTag result = source == null ? new CompoundTag() : source.copy();
        if (!result.contains("OwnerID", Tag.TAG_INT_ARRAY)) {
            result.putUUID("OwnerID", SYSTEM_OWNER);
        }
        if (!result.contains("OwnerName", Tag.TAG_STRING)
            || result.getString("OwnerName").isBlank()) {
            result.putString("OwnerName", SYSTEM_OWNER_NAME);
        }
        CompoundTag neoForgeData = result.getCompound("NeoForgeData").copy();
        neoForgeData.putBoolean(CASINO_TABLE_MARKER, true);
        result.put("NeoForgeData", neoForgeData);
        return result;
    }
}
