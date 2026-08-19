package dev.buizz.cobbleventure.bootstrap;

import com.google.gson.JsonParser;
import dev.buizz.cobbleventure.adventure.event.EventLocationRef;
import dev.buizz.cobbleventure.adventure.event.EventLocationResolverRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionEventLocationResolverTest {
    @Test
    void resolvesOnlyAuthoredAnchorAndCentersBlockCoordinates() {
        DimensionEventLocationResolver.Catalog catalog = parse("""
            {"schema_version":1,"dimensions":[{
              "id":"cobbleventure:generation_1",
              "anchors":{"forest/south":{"x":10,"y":70,"z":-4,"yaw":180,"pitch":0}}
            }]}
            """);

        EventLocationResolverRegistry.Resolution result =
            DimensionEventLocationResolver.resolve(catalog, destination("forest/south"));

        assertTrue(result.isResolved());
        assertEquals(
            new EventLocationRef.Position(
                "cobbleventure:generation_1", 10.5D, 70D, -3.5D, 180F, 0F
            ),
            result.location().toPosition()
        );
    }

    @Test
    void unavailableCatalogReportsWorldNotReady() {
        EventLocationResolverRegistry.Resolution result =
            DimensionEventLocationResolver.resolve(null, destination("forest/south"));

        assertFalse(result.isResolved());
        assertEquals("world_not_ready", result.failureReason());
    }

    @Test
    void missingDimensionAndAnchorHaveStableFailures() {
        DimensionEventLocationResolver.Catalog catalog = parse("""
            {"schema_version":1,"dimensions":[{
              "id":"cobbleventure:generation_1","anchors":{}
            }]}
            """);

        assertEquals(
            "anchor_not_found",
            DimensionEventLocationResolver.resolve(catalog, destination("missing")).failureReason()
        );
        EventLocationRef.Resource missingDimension = new EventLocationRef.Resource(
            EventLocationRef.Resource.Kind.DIMENSION, "cobbleventure:missing", "spawn"
        );
        assertEquals(
            "destination_not_found",
            DimensionEventLocationResolver.resolve(catalog, missingDimension).failureReason()
        );
    }

    @Test
    void anchorIsRequiredInsteadOfGuessingDimensionOrigin() {
        DimensionEventLocationResolver.Catalog catalog = parse("""
            {"schema_version":1,"dimensions":[{
              "id":"cobbleventure:generation_1","anchors":{"spawn":{"x":0,"y":80,"z":0}}
            }]}
            """);
        EventLocationRef.Resource withoutAnchor = new EventLocationRef.Resource(
            EventLocationRef.Resource.Kind.DIMENSION, "cobbleventure:generation_1", null
        );

        assertEquals(
            "anchor_required",
            DimensionEventLocationResolver.resolve(catalog, withoutAnchor).failureReason()
        );
    }

    @Test
    void duplicateDimensionAndInvalidAnglesAreRejectedAtLoadTime() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schema_version":1,"dimensions":[
              {"id":"cobbleventure:test","anchors":{}},
              {"id":"cobbleventure:test","anchors":{}}
            ]}
            """));
        assertThrows(IllegalArgumentException.class, () -> parse("""
            {"schema_version":1,"dimensions":[{
              "id":"cobbleventure:test","anchors":{"spawn":{"x":0,"y":64,"z":0,"pitch":91}}
            }]}
            """));
    }

    private static DimensionEventLocationResolver.Catalog parse(String source) {
        return DimensionEventLocationResolver.parse(
            JsonParser.parseString(source).getAsJsonObject()
        );
    }

    private static EventLocationRef.Resource destination(String anchor) {
        return new EventLocationRef.Resource(
            EventLocationRef.Resource.Kind.DIMENSION,
            "cobbleventure:generation_1",
            anchor
        );
    }
}
