package dev.buizz.cobbleventure.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

final class PlayingCardsTableOwnerData {
    private static final UUID SYSTEM_OWNER = UUID.nameUUIDFromBytes(
        "cobbleventure:playingcards_table_owner".getBytes(StandardCharsets.UTF_8)
    );
    private static final String SYSTEM_OWNER_NAME = "Cobbleventure Casino";

    private PlayingCardsTableOwnerData() {
    }

    static CompoundTag withOwner(CompoundTag source) {
        if (source != null && source.contains("OwnerID", Tag.TAG_INT_ARRAY)) {
            return source;
        }
        CompoundTag result = source == null ? new CompoundTag() : source.copy();
        result.putUUID("OwnerID", SYSTEM_OWNER);
        if (!result.contains("OwnerName", Tag.TAG_STRING)
            || result.getString("OwnerName").isBlank()) {
            result.putString("OwnerName", SYSTEM_OWNER_NAME);
        }
        return result;
    }
}
