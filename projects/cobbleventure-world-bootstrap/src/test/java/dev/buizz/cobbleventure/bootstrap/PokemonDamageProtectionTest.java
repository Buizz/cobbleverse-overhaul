package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

final class PokemonDamageProtectionTest {
    private static final String POKEMON_ENTITY =
        "com/cobblemon/mod/common/entity/pokemon/PokemonEntity";

    @Test void directPlayerAttackIsCancelledBeforeItHitsPokemon() throws IOException {
        MethodNode handler = method("onAttackPokemon");
        assertTrue(hasInstanceOf(handler, POKEMON_ENTITY));
        assertTrue(calls(handler, "net/neoforged/neoforge/event/entity/player/AttackEntityEvent", "setCanceled"));
    }

    @Test void playerAttributedDamageIsCancelledForPokemon() throws IOException {
        MethodNode handler = method("onPokemonIncomingDamage");
        assertTrue(hasInstanceOf(handler, POKEMON_ENTITY));
        assertTrue(hasInstanceOf(handler, "net/minecraft/world/entity/player/Player"));
        assertTrue(calls(handler, "net/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent", "setCanceled"));
    }

    private static MethodNode method(String name) throws IOException {
        try (var stream = PokemonDamageProtectionTest.class.getClassLoader().getResourceAsStream(
            "dev/buizz/cobbleventure/bootstrap/CobbleventureBootstrap.class"
        )) {
            assertNotNull(stream);
            ClassNode type = new ClassNode();
            new ClassReader(stream).accept(type, 0);
            return type.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        }
    }

    private static boolean hasInstanceOf(MethodNode method, String type) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .anyMatch(instruction -> instruction.getOpcode() == Opcodes.INSTANCEOF
                && instruction instanceof TypeInsnNode check && check.desc.equals(type));
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name));
    }
}
