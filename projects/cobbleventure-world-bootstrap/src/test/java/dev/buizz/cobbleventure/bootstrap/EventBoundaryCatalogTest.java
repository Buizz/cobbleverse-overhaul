package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.adventure.event.EventBoundaryProviderRegistry;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EventBoundaryCatalogTest {
    @Test
    void returnsOnlyMatchingDimensionAndInclusiveBoxes() {
        EventBoundaryCatalog catalog = parse("""
            {"schema_version":1,
             "regions":[
               {"id":"test:region/one","dimension":"test:world","box":{
                 "min_x":0,"min_y":10,"min_z":0,"max_x":5,"max_y":20,"max_z":5}},
               {"id":"test:region/other","dimension":"test:other","box":{
                 "min_x":0,"min_y":10,"min_z":0,"max_x":5,"max_y":20,"max_z":5}}
             ],
             "anchors":[{"id":"test:anchor/step","dimension":"test:world","box":{
               "min_x":5,"min_y":20,"min_z":5,"max_x":5,"max_y":20,"max_z":5}}]}
            """);

        EventBoundaryProviderRegistry.Snapshot snapshot =
            catalog.snapshot("test:world", 5, 20, 5);

        assertEquals(java.util.Set.of("test:region/one"), snapshot.regions());
        assertEquals(java.util.Set.of("test:anchor/step"), snapshot.anchors());
        assertEquals(java.util.Set.of("test:world"), snapshot.dimensions());
        assertEquals(
            java.util.Set.of(), catalog.snapshot("test:world", 6, 20, 5).anchors()
        );
    }

    @Test
    void rejectsDuplicateIdsFractionalCoordinatesAndInvertedBoxes() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schema_version":1,"regions":[
              {"id":"test:same","dimension":"test:world","box":{"min_x":0,"min_y":0,"min_z":0,"max_x":1,"max_y":1,"max_z":1}},
              {"id":"test:same","dimension":"test:world","box":{"min_x":0,"min_y":0,"min_z":0,"max_x":1,"max_y":1,"max_z":1}}
            ],"anchors":[]}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schema_version":1,"regions":[],"anchors":[
              {"id":"test:fraction","dimension":"test:world","box":{"min_x":0.5,"min_y":0,"min_z":0,"max_x":1,"max_y":1,"max_z":1}}
            ]}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schema_version":1,"regions":[
              {"id":"test:inverse","dimension":"test:world","box":{"min_x":2,"min_y":0,"min_z":0,"max_x":1,"max_y":1,"max_z":1}}
            ],"anchors":[]}
            """));
    }

    @Test
    void resolvesGlobalAnchorToDeterministicBoxCenter() {
        EventBoundaryCatalog catalog = parse("""
            {"schema_version":1,"regions":[],"anchors":[
              {"id":"test:anchor/arrival","dimension":"test:world","box":{
                "min_x":10,"min_y":70,"min_z":-5,"max_x":12,"max_y":72,"max_z":-3}}
            ]}
            """);

        EventLocationResolverRegistry.Resolution result =
            EventBoundaryCatalog.resolveAnchor(catalog, anchor("test:anchor/arrival", null));

        assertEquals(
            new EventLocationRef.Position("test:world", 11.5D, 71D, -3.5D, null, null),
            result.location().toPosition()
        );
    }

    @Test
    void globalAnchorFailuresAreStableAndDoNotGuess() {
        EventBoundaryCatalog catalog = parse("""
            {"schema_version":1,"regions":[],"anchors":[]}
            """);

        assertEquals(
            "world_not_ready",
            EventBoundaryCatalog.resolveAnchor(null, anchor("test:anchor/arrival", null))
                .failureReason()
        );
        assertEquals(
            "destination_not_found",
            EventBoundaryCatalog.resolveAnchor(catalog, anchor("test:anchor/missing", null))
                .failureReason()
        );
        assertEquals(
            "anchor_not_found",
            EventBoundaryCatalog.resolveAnchor(catalog, anchor("test:anchor/missing", "nested"))
                .failureReason()
        );
    }

    private static EventBoundaryCatalog parse(String source) {
        return EventBoundaryCatalog.parse(JsonParser.parseString(source).getAsJsonObject());
    }

    private static EventLocationRef.Resource anchor(String id, String nested) {
        return new EventLocationRef.Resource(EventLocationRef.Resource.Kind.ANCHOR, id, nested);
    }
}
