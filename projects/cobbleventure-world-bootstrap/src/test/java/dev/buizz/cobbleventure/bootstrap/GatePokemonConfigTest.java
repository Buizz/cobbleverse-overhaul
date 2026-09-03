package dev.buizz.cobbleventure.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

final class GatePokemonConfigTest {
    private JsonObject properties() {
        return JsonParser.parseString("""
            {"center_placement":"pokemon","passage_width":3,"pokemon":{
              "species":"cobblemon:snorlax","level":30,"pose":"sleep","scale":1,
              "collision":{"width":3,"height":2,"depth":4},
              "activation_conditions":[{"type":"flag","key":"cobbleventure:flag/story/flute_received"}]
            }}
            """).getAsJsonObject();
    }

    @Test void parsesSleepingActorWithoutBuildingOrNpc() {
        JsonObject object = JsonParser.parseString("""
            {"id":"sleeping_snorlax","type":"gate","anchor":{"q":0,"r":0}}
            """).getAsJsonObject();
        object.add("properties", properties());
        var objects = new com.google.gson.JsonArray();
        objects.add(object);
        var gate = WorldGateSystem.parse(objects).getFirst();
        assertFalse(gate.buildingEnabled());
        assertNull(gate.structure());
        assertNull(gate.npc());
        assertEquals("sleep", gate.pokemon().pose());
        assertEquals("cobbleventure:flag/gate/sleeping_snorlax_cleared", gate.pokemon().completionFlag());
        assertEquals(1, gate.pokemon().activationConditions().size());
    }

    @Test void rotatesAndScalesTheSameCollisionBody() {
        JsonObject value = properties();
        value.getAsJsonObject("pokemon").addProperty("scale", 1.5);
        var config = GatePokemonConfig.parse(value, "snorlax");
        Vec3 feet = new Vec3(10.5, 64, 20.5);
        for (String direction : java.util.List.of("north", "south", "east", "west")) {
            AABB bounds = config.bounds(feet, direction);
            boolean eastWest = direction.equals("east") || direction.equals("west");
            assertEquals(eastWest ? 6 : 4.5, bounds.getXsize());
            assertEquals(eastWest ? 4.5 : 6, bounds.getZsize());
            assertEquals(3, bounds.getYsize());
            assertEquals(64, bounds.minY);
            assertEquals(feet.x, bounds.getCenter().x);
            assertEquals(feet.z, bounds.getCenter().z);
        }
    }

    @Test void activationItemRequiresUsingTheActualTool() {
        JsonObject value = properties();
        assertTrue(GatePokemonConfig.parse(value, "snorlax").acceptsActivationItem("minecraft:air"));
        value.getAsJsonObject("pokemon").addProperty("activation_item", "cobbleventure_bootstrap:poke_flute");
        var config = GatePokemonConfig.parse(value, "snorlax");
        assertTrue(config.acceptsActivationItem("cobbleventure_bootstrap:poke_flute"));
        assertFalse(config.acceptsActivationItem("minecraft:air"));
        assertFalse(config.acceptsActivationItem("minecraft:stick"));
        value.getAsJsonObject("pokemon").addProperty("activation_item", "invalid item!");
        assertThrows(IllegalArgumentException.class, () -> GatePokemonConfig.parse(value, "snorlax"));
    }

    @Test void rejectsMissingFractionalOrOutOfRangeSettings() {
        for (String json : java.util.List.of("0", "101", "30.5", "\"30\"", "null")) {
            JsonObject value = properties();
            value.getAsJsonObject("pokemon").add("level", JsonParser.parseString(json));
            assertThrows(IllegalArgumentException.class, () -> GatePokemonConfig.parse(value, "snorlax"));
        }
        JsonObject missing = properties();
        missing.getAsJsonObject("pokemon").getAsJsonObject("collision").remove("height");
        assertThrows(IllegalArgumentException.class, () -> GatePokemonConfig.parse(missing, "snorlax"));
        JsonObject gap = properties();
        gap.addProperty("passage_width", 7);
        assertThrows(IllegalArgumentException.class, () -> GatePokemonConfig.parse(gap, "snorlax"));
    }

    @Test void completedViewDoesNotBlockAnotherPlayersView() {
        UUID actorId = UUID.randomUUID();
        AABB bounds = GatePokemonConfig.parse(properties(), "snorlax").bounds(new Vec3(0, 64, 0), "north");
        var blocked = new GatePokemonNetwork.View(actorId, bounds, false, "sleep");
        var completed = new GatePokemonNetwork.View(actorId, bounds, true, "sleep");
        AABB crossing = new AABB(-0.3, 64, -3, 0.3, 65.8, 1);
        assertTrue(blocked.blocks(crossing));
        assertFalse(completed.blocks(crossing));
        assertTrue(blocked.blocks(crossing));
        assertFalse(blocked.blocks(crossing.move(20, 0, 0)));
    }

    @Test void mixinMovementTargetExistsInInstalledMinecraft() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("net/minecraft/world/entity/Entity.class")) {
            assertNotNull(stream);
            ClassNode entity = new ClassNode();
            new ClassReader(stream).accept(entity, 0);
            long targets = entity.methods.stream().filter(method -> method.name.equals("collide"))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/Level")
                    && call.name.equals("getEntityCollisions")
                    && call.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
                .count();
            assertEquals(1, targets, "Vanilla movement must produce one entity-collision list");
        }
    }
}
