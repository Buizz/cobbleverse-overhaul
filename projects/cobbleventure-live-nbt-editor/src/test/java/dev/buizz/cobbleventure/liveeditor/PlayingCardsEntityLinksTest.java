package dev.buizz.cobbleventure.liveeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

final class PlayingCardsEntityLinksTest {
    @Test
    void repairsMissingDeckUuidUsingNearestCardGroup() {
        UUID leftLink = UUID.randomUUID();
        UUID rightLink = UUID.randomUUID();
        CompoundTag structure = new CompoundTag();
        ListTag entities = new ListTag();
        entities.add(entity(PlayingCardsEntityLinks.DECK_ID, 1.0, 0.0, 1.0, null));
        entities.add(entity(PlayingCardsEntityLinks.DECK_ID, 20.0, 0.0, 1.0, null));
        entities.add(entity(PlayingCardsEntityLinks.CARD_ID, 2.0, 0.0, 1.0, leftLink));
        entities.add(entity(PlayingCardsEntityLinks.CARD_ID, 19.0, 0.0, 1.0, rightLink));
        structure.put("entities", entities);

        PlayingCardsEntityLinks.repairStructure(structure);

        UUID leftDeck = nbt(entities, 0).getUUID("UUID");
        UUID rightDeck = nbt(entities, 1).getUUID("UUID");
        assertEquals(leftDeck, nbt(entities, 2).getUUID("DeckID"));
        assertEquals(rightDeck, nbt(entities, 3).getUUID("DeckID"));
        assertNotEquals(leftDeck, rightDeck);
    }

    @Test
    void remapsDeckAndCardLinkTogetherForEachPlacement() {
        UUID sourceId = UUID.randomUUID();
        CompoundTag deck = new CompoundTag();
        deck.putString("id", PlayingCardsEntityLinks.DECK_ID);
        deck.putUUID("UUID", sourceId);
        CompoundTag card = new CompoundTag();
        card.putString("id", PlayingCardsEntityLinks.CARD_ID);
        card.putUUID("DeckID", sourceId);

        CompoundTag placedDeck = PlayingCardsEntityLinks.relocate(deck, BlockPos.ZERO);
        CompoundTag placedCard = PlayingCardsEntityLinks.relocate(card, BlockPos.ZERO);
        CompoundTag otherDeck = PlayingCardsEntityLinks.relocate(deck, new BlockPos(320, 65, 0));

        assertEquals(placedDeck.getUUID("UUID"), placedCard.getUUID("DeckID"));
        assertNotEquals(placedDeck.getUUID("UUID"), otherDeck.getUUID("UUID"));
    }

    @Test
    void leavesUnrelatedEntityUntouched() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:item");
        assertSame(entity, PlayingCardsEntityLinks.relocate(entity, BlockPos.ZERO));
    }

    private static CompoundTag entity(
        String id, double x, double y, double z, UUID deckLink
    ) {
        CompoundTag wrapper = new CompoundTag();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(x));
        position.add(DoubleTag.valueOf(y));
        position.add(DoubleTag.valueOf(z));
        wrapper.put("pos", position);
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        if (deckLink != null) nbt.putUUID("DeckID", deckLink);
        wrapper.put("nbt", nbt);
        return wrapper;
    }

    private static CompoundTag nbt(ListTag entities, int index) {
        return entities.getCompound(index).getCompound("nbt");
    }
}
