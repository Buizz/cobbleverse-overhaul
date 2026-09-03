package dev.buizz.cobbleventure.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

final class GatePokemonCollisionMixinTest {
    @Test void appendsToStoredListWithoutCompetingWithLithiumRedirects() throws Exception {
        String resource = "dev/buizz/cobbleventure/bootstrap/mixin/GatePokemonCollisionMixin.class";
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            ClassNode mixin = new ClassNode();
            new ClassReader(stream).accept(mixin, 0);
            var handler = mixin.methods.stream()
                .filter(method -> method.name.equals("cobbleventure$gateShapes"))
                .findFirst().orElseThrow();
            assertTrue(handler.visibleAnnotations.stream().noneMatch(annotation ->
                annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")));
            var injection = handler.visibleAnnotations.stream().filter(annotation ->
                annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"))
                .findFirst().orElseThrow();
            assertEquals(0, value(injection, "ordinal"));
            assertEquals(1, value(injection, "require"), "Missing collision injection must still fail loudly");
            assertEquals("STORE", value((AnnotationNode) value(injection, "at"), "value"));
            assertEquals("(Ljava/util/List;Lnet/minecraft/world/phys/Vec3;)Ljava/util/List;", handler.desc);
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        int index = annotation.values.indexOf(key);
        assertTrue(index >= 0, () -> "Missing annotation value: " + key);
        return annotation.values.get(index + 1);
    }
}
