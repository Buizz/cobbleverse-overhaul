package dev.buizz.cobbleventure.playermenu;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class BattleEvPersistenceTest {
    @Test
    void modRegistersBattleCloneEvPersistence() throws IOException {
        String bytecode = bytecode(CobbleventurePlayerMenu.class);
        assertTrue(bytecode.contains("BattleLevelCap"));
        assertTrue(bytecode.contains("register"));
    }

    @Test
    void awardedBattleCloneEvsAreAddedToTheOriginalPokemon() throws IOException {
        String bytecode = bytecode(BattleLevelCap.class);
        assertTrue(bytecode.contains("persistBattleCloneEvGain"));
        assertTrue(bytecode.contains("BattleEvSource"));
        assertTrue(bytecode.contains("getBattle"));
        assertTrue(bytecode.contains("com/cobblemon/mod/common/pokemon/EVs"));
        assertTrue(bytecode.contains("add"));
    }

    @Test
    void preexistingBattleCopiesAlsoReceiveBattleEndPersistence() throws IOException {
        String bytecode = bytecode(BattleLevelCap.class);
        assertTrue(bytecode.contains("adjustPokemon"));
        assertTrue(bytecode.contains("getPostBattlePokemonOperations"));
    }

    private static String bytecode(Class<?> owner) throws IOException {
        String resource = owner.getName().replace('.', '/') + ".class";
        try (var stream = owner.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
