package dev.buizz.cobbleventure.liveeditor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Keeps Playing Cards card entities linked to their deck across structure placement. */
final class PlayingCardsEntityLinks {
    static final String CARD_ID = "playingcards:card";
    static final String DECK_ID = "playingcards:card_deck";
    private static final String UUID_KEY = "UUID";
    private static final String DECK_LINK_KEY = "DeckID";

    private PlayingCardsEntityLinks() {}

    /**
     * Old structure exports omit deck UUIDs but retain each card's DeckID. Rebuild those
     * relationships by pairing each group of cards with its nearest deck.
     */
    static void repairStructure(CompoundTag structure) {
        ListTag entities = structure.getList("entities", Tag.TAG_COMPOUND);
        List<Deck> decks = new ArrayList<>();
        Map<UUID, List<Card>> cardGroups = new LinkedHashMap<>();
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag wrapper = entities.getCompound(index);
            CompoundTag entity = wrapper.getCompound("nbt");
            Vec3 position = position(wrapper);
            if (DECK_ID.equals(entity.getString("id"))) {
                UUID id = hasUuid(entity, UUID_KEY)
                    ? entity.getUUID(UUID_KEY)
                    : syntheticDeckId(position, index);
                entity.putUUID(UUID_KEY, id);
                decks.add(new Deck(id, position));
            } else if (CARD_ID.equals(entity.getString("id"))
                && hasUuid(entity, DECK_LINK_KEY)) {
                UUID oldDeckId = entity.getUUID(DECK_LINK_KEY);
                cardGroups.computeIfAbsent(oldDeckId, ignored -> new ArrayList<>())
                    .add(new Card(entity, position));
            }
        }

        List<UUID> assignedDecks = new ArrayList<>();
        List<Map.Entry<UUID, List<Card>>> unresolved = new ArrayList<>();
        for (Map.Entry<UUID, List<Card>> group : cardGroups.entrySet()) {
            if (decks.stream().anyMatch(deck -> deck.id().equals(group.getKey()))) {
                assignedDecks.add(group.getKey());
            } else {
                unresolved.add(group);
            }
        }
        unresolved.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));
        for (Map.Entry<UUID, List<Card>> group : unresolved) {
            Vec3 center = centroid(group.getValue());
            Deck nearest = decks.stream()
                .filter(deck -> !assignedDecks.contains(deck.id()))
                .min(Comparator.comparingDouble(deck -> deck.position().distanceToSqr(center)))
                .orElse(null);
            if (nearest == null) continue;
            for (Card card : group.getValue()) {
                card.nbt().putUUID(DECK_LINK_KEY, nearest.id());
            }
            assignedDecks.add(nearest.id());
        }
    }

    static CompoundTag relocate(CompoundTag source, BlockPos origin) {
        String id = source.getString("id");
        String key;
        if (DECK_ID.equals(id) && hasUuid(source, UUID_KEY)) {
            key = UUID_KEY;
        } else if (CARD_ID.equals(id) && hasUuid(source, DECK_LINK_KEY)) {
            key = DECK_LINK_KEY;
        } else {
            return source;
        }
        CompoundTag result = source.copy();
        result.putUUID(key, placementId(source.getUUID(key), origin));
        return result;
    }

    /**
     * StructureTemplate deliberately removes an entity UUID before spawning it. That is safe for
     * normal entities, but Playing Cards stores the owning deck UUID in each card's DeckID. Repair
     * the links immediately after placement, before the cards perform their first validity check.
     */
    static int relinkPlacedEntities(ServerLevel level, BlockPos origin, Vec3i size) {
        AABB bounds = new AABB(
            origin.getX() - 1.0D, origin.getY() - 1.0D, origin.getZ() - 1.0D,
            origin.getX() + size.getX() + 1.0D,
            origin.getY() + size.getY() + 1.0D,
            origin.getZ() + size.getZ() + 1.0D
        );
        List<PlacedDeck> decks = new ArrayList<>();
        Map<UUID, List<PlacedCard>> cardGroups = new LinkedHashMap<>();
        for (Entity entity : level.getEntities(null, bounds)) {
            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (DECK_ID.equals(entityId.toString())) {
                decks.add(new PlacedDeck(entity.getUUID(), entity.position()));
            } else if (CARD_ID.equals(entityId.toString())) {
                CompoundTag data = entity.saveWithoutId(new CompoundTag());
                UUID currentDeck = hasUuid(data, DECK_LINK_KEY)
                    ? data.getUUID(DECK_LINK_KEY) : entity.getUUID();
                cardGroups.computeIfAbsent(currentDeck, ignored -> new ArrayList<>())
                    .add(new PlacedCard(entity, entity.position()));
            }
        }
        if (decks.isEmpty() || cardGroups.isEmpty()) return 0;

        Set<UUID> actualDeckIds = new HashSet<>();
        for (PlacedDeck deck : decks) actualDeckIds.add(deck.id());
        Set<UUID> assignedDecks = new HashSet<>();
        List<Map.Entry<UUID, List<PlacedCard>>> unresolved = new ArrayList<>();
        for (Map.Entry<UUID, List<PlacedCard>> group : cardGroups.entrySet()) {
            if (actualDeckIds.contains(group.getKey())) {
                assignedDecks.add(group.getKey());
            } else {
                unresolved.add(group);
            }
        }
        unresolved.sort(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)));

        int repaired = 0;
        for (Map.Entry<UUID, List<PlacedCard>> group : unresolved) {
            Vec3 center = placedCentroid(group.getValue());
            PlacedDeck nearest = decks.stream()
                .filter(deck -> !assignedDecks.contains(deck.id()))
                .min(Comparator.comparingDouble(deck -> deck.position().distanceToSqr(center)))
                .orElseGet(() -> decks.stream()
                    .min(Comparator.comparingDouble(deck -> deck.position().distanceToSqr(center)))
                    .orElse(null));
            if (nearest == null) continue;
            for (PlacedCard card : group.getValue()) {
                CompoundTag data = card.entity().saveWithoutId(new CompoundTag());
                data.putUUID(DECK_LINK_KEY, nearest.id());
                card.entity().load(data);
                repaired++;
            }
            assignedDecks.add(nearest.id());
        }
        return repaired;
    }

    static boolean isDeck(String id) {
        return DECK_ID.equals(id);
    }

    private static boolean hasUuid(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_INT_ARRAY) && tag.getIntArray(key).length == 4;
    }

    private static UUID syntheticDeckId(Vec3 position, int index) {
        return UUID.nameUUIDFromBytes((DECK_ID + ':' + index + ':'
            + Double.doubleToLongBits(position.x) + ':'
            + Double.doubleToLongBits(position.y) + ':'
            + Double.doubleToLongBits(position.z)).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID placementId(UUID source, BlockPos origin) {
        return UUID.nameUUIDFromBytes(("cobbleventure:playingcards:" + origin.asLong() + ':'
            + source).getBytes(StandardCharsets.UTF_8));
    }

    private static Vec3 position(CompoundTag wrapper) {
        ListTag values = wrapper.getList("pos", Tag.TAG_DOUBLE);
        if (values.size() != 3) return Vec3.ZERO;
        return new Vec3(values.getDouble(0), values.getDouble(1), values.getDouble(2));
    }

    private static Vec3 centroid(List<Card> cards) {
        Vec3 sum = Vec3.ZERO;
        for (Card card : cards) sum = sum.add(card.position());
        return sum.scale(1.0D / cards.size());
    }

    private static Vec3 placedCentroid(List<PlacedCard> cards) {
        Vec3 sum = Vec3.ZERO;
        for (PlacedCard card : cards) sum = sum.add(card.position());
        return sum.scale(1.0D / cards.size());
    }

    private record Deck(UUID id, Vec3 position) {}
    private record Card(CompoundTag nbt, Vec3 position) {}
    private record PlacedDeck(UUID id, Vec3 position) {}
    private record PlacedCard(Entity entity, Vec3 position) {}
}
