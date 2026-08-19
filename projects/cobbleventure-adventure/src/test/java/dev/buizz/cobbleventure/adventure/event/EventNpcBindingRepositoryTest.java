package dev.buizz.cobbleventure.adventure.event;

import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventNpcBindingRepositoryTest {
    @Test
    void derivesRepresentationNeutralEntityTagFromDatapackPath() {
        EventNpcBindingRepository repository = new EventNpcBindingRepository();
        repository.replace(Map.of(
            ResourceLocation.parse("cobbleventure:story/professor_oak"),
            JsonParser.parseString("""
                {"schema_version":1,
                 "script_id":"cobbleventure:event_script/story/professor_oak"}
                """)
        ));

        EventNpcBinding binding = repository.findByEntityTags(Set.of(
            "cobbleventure_regional_npc",
            "cves_binding/cobbleventure/story/professor_oak"
        )).orElseThrow();
        assertEquals("cobbleventure:story/professor_oak", binding.bindingId());
        assertEquals(
            "cobbleventure:event_script/story/professor_oak", binding.scriptId()
        );
        assertTrue(repository.findByEntityTags(Set.of("unrelated")).isEmpty());
    }

    @Test
    void rejectsUnknownFieldsInvalidIdsAndMultipleEntityBindings() {
        EventNpcBindingRepository repository = new EventNpcBindingRepository();
        assertThrows(EventScriptFormatException.class, () -> repository.replace(Map.of(
            ResourceLocation.parse("test:unknown"),
            JsonParser.parseString("""
                {"schema_version":1,"script_id":"test:event_script/a","easy_npc":{}}
                """)
        )));
        assertThrows(EventScriptFormatException.class, () -> repository.replace(Map.of(
            ResourceLocation.parse("test:bad_id"),
            JsonParser.parseString("""
                {"schema_version":1,"script_id":"Not An Id"}
                """)
        )));

        repository.replace(Map.of(
            ResourceLocation.parse("test:first"), document("test:event_script/first"),
            ResourceLocation.parse("test:second"), document("test:event_script/second")
        ));
        assertThrows(EventRuntimeException.class, () -> repository.findByEntityTags(Set.of(
            "cves_binding/test/first", "cves_binding/test/second"
        )));
    }

    private static com.google.gson.JsonElement document(String scriptId) {
        return JsonParser.parseString(
            "{\"schema_version\":1,\"script_id\":\"" + scriptId + "\"}"
        );
    }
}
