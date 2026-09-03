package dev.buizz.cobbleventure.adventure.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.client.battle.ActiveClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

final class BattleTargetEmptySlotMixinTest {
    @Test
    void emptySlotHasNoUuidAndCannotBeSelectedWhileOccupiedSlotKeepsItsUuid() {
        var actor = new ClientBattleActor("p1", Component.literal("Player"), UUID.randomUUID(), ActorType.PLAYER);
        var empty = new ActiveClientBattlePokemon(actor, null);
        var mixin = new Fixture(empty);

        assertNull(mixin.cobbleventure$nullableTargetUuid(null));
        var emptySelectable = new CallbackInfoReturnable<Boolean>("getSelectable", true);
        mixin.cobbleventure$disableEmptyTarget(emptySelectable);
        assertTrue(emptySelectable.isCancelled());
        assertFalse(emptySelectable.getReturnValue());

        UUID pokemonId = UUID.randomUUID();
        // Allocate the DTO without bootstrapping Minecraft's renderer/MoLang globals, which are
        // unavailable in a plain JVM unit test; getUuid only reads this final value.
        var pokemon = allocatePokemon(pokemonId);
        var occupiedMixin = new Fixture(new ActiveClientBattlePokemon(actor, pokemon));
        assertEquals(pokemonId, occupiedMixin.cobbleventure$nullableTargetUuid(pokemon));
        var occupiedSelectable = new CallbackInfoReturnable<Boolean>("getSelectable", true);
        occupiedMixin.cobbleventure$disableEmptyTarget(occupiedSelectable);
        assertFalse(occupiedSelectable.isCancelled(),
            "Occupied targets must still use Cobblemon's normal move target rules");
    }

    @Test
    void redirectsMatchTheExactCobblemon173ConstructorAssumption() throws Exception {
        ClassNode target = classNode(
            "com/cobblemon/mod/common/client/gui/battle/subscreen/BattleTargetSelection$TargetTile.class"
        );
        var constructor = target.methods.stream().filter(method -> method.name.equals("<init>"))
            .findFirst().orElseThrow();
        int nullAssertions = 0;
        int uuidCalls = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                && call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (call.owner.equals("kotlin/jvm/internal/Intrinsics")
                && call.name.equals("checkNotNull") && call.desc.equals("(Ljava/lang/Object;)V")) {
                nullAssertions++;
            }
            if (call.owner.equals("com/cobblemon/mod/common/client/battle/ClientBattlePokemon")
                && call.name.equals("getUuid") && call.desc.equals("()Ljava/util/UUID;")) {
                uuidCalls++;
            }
        }
        assertEquals(1, nullAssertions, "The patch intentionally relaxes exactly one !! assertion");
        assertEquals(2, uuidCalls, "Ordinal 1 must remain the target UUID call");

        ClassNode mixin = classNode(
            "dev/buizz/cobbleventure/adventure/mixin/BattleTargetEmptySlotMixin.class"
        );
        var uuidRedirect = mixin.methods.stream()
            .filter(method -> method.name.equals("cobbleventure$nullableTargetUuid"))
            .findFirst().orElseThrow();
        AnnotationNode redirect = uuidRedirect.visibleAnnotations.stream()
            .filter(annotation -> annotation.desc.equals("L" + Redirect.class.getName().replace('.', '/') + ";"))
            .findFirst().orElseThrow();
        assertEquals(1, annotationValue(redirect, "require"));
        assertEquals(1, annotationValue(redirect, "allow"));
        assertEquals(1, annotationValue((AnnotationNode) annotationValue(redirect, "at"), "ordinal"));
    }

    @Test
    void emptySlotCannotInterceptSpreadMoveClicksAndSlotChangesAreObserved() {
        var actor = new ClientBattleActor("p1", Component.literal("Player"), UUID.randomUUID(), ActorType.PLAYER);
        var pokemon = allocatePokemon(UUID.randomUUID());
        var slot = new ActiveClientBattlePokemon(actor, pokemon);
        var mixin = new Fixture(slot);
        var occupiedHover = new CallbackInfoReturnable<Boolean>("isHovered", true);
        mixin.cobbleventure$ignoreEmptyTargetHitbox(occupiedHover);
        assertFalse(occupiedHover.isCancelled());

        slot.setBattlePokemon(null);
        var emptyHover = new CallbackInfoReturnable<Boolean>("isHovered", true);
        mixin.cobbleventure$ignoreEmptyTargetHitbox(emptyHover);
        assertTrue(emptyHover.isCancelled());
        assertFalse(emptyHover.getReturnValue());
        var emptySelectable = new CallbackInfoReturnable<Boolean>("getSelectable", true);
        mixin.cobbleventure$disableEmptyTarget(emptySelectable);
        assertFalse(emptySelectable.getReturnValue());

        slot.setBattlePokemon(pokemon);
        var restoredHover = new CallbackInfoReturnable<Boolean>("isHovered", true);
        mixin.cobbleventure$ignoreEmptyTargetHitbox(restoredHover);
        assertFalse(restoredHover.isCancelled());
        assertSame(pokemon, slot.getBattlePokemon());
    }

    @Test
    void clientMixinIsRegistered() throws Exception {
        try (var stream = getClass().getResourceAsStream("/cobbleventure_adventure.mixins.json")) {
            assertNotNull(stream);
            var root = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
            assertTrue(root.getAsJsonArray("client").asList().stream().anyMatch(value ->
                value.getAsString().equals("BattleTargetEmptySlotMixin")));
        }
    }

    private static ClassNode classNode(String resource) throws Exception {
        try (var stream = BattleTargetEmptySlotMixinTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        int index = annotation.values.indexOf(key);
        assertTrue(index >= 0, () -> "Missing annotation value: " + key);
        return annotation.values.get(index + 1);
    }

    private static ClientBattlePokemon allocatePokemon(UUID uuid) {
        try {
            Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var unsafe = (Unsafe) singleton.get(null);
            var pokemon = (ClientBattlePokemon) unsafe.allocateInstance(ClientBattlePokemon.class);
            Field uuidField = ClientBattlePokemon.class.getDeclaredField("uuid");
            unsafe.putObject(pokemon, unsafe.objectFieldOffset(uuidField), uuid);
            return pokemon;
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class Fixture extends BattleTargetEmptySlotMixin {
        private final ActiveClientBattlePokemon target;

        private Fixture(ActiveClientBattlePokemon target) {
            this.target = target;
        }

        @Override public ActiveClientBattlePokemon getTarget() {
            return target;
        }
    }
}
