package dev.buizz.cobbleventure.region;

import dev.buizz.cobbleventure.api.BoundaryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionDefinitionLoaderTest {
    private final RegionDefinitionLoader loader = new RegionDefinitionLoader();
    private final RegionDefinitionValidator validator = new RegionDefinitionValidator();

    @Test
    void loadsValidRegionWithoutMinecraftTypes() throws Exception {
        var region = loader.load("""
            {
              "schema_version": 1,
              "id": "cobbleventure:region_01",
              "dimension": "cobbleventure:generation_1",
              "bounds": { "min_x": 0, "min_z": 0, "max_x": 1023, "max_z": 1023 },
              "biome_pool": ["minecraft:plains"],
              "boundary": {
                "type": "COMBINED",
                "template": "cobbleventure:region_wall",
                "protection_profile": "cobbleventure:adventure_field"
              },
              "connections": [],
              "anchors": { "spawn": { "x": 32, "y": 80, "z": 32 } },
              "spawn_profile": "cobbleventure:generation_1_region_01"
            }
            """);

        assertEquals(BoundaryType.COMBINED, region.boundary().type());
        assertTrue(validator.validate(region).isEmpty());
    }

    @Test
    void rejectsWallWithoutTemplateAndAnchorOutsideBounds() throws Exception {
        var region = loader.load("""
            {
              "schema_version": 1,
              "id": "cobbleventure:region_01",
              "dimension": "cobbleventure:generation_1",
              "bounds": { "min_x": 0, "min_z": 0, "max_x": 10, "max_z": 10 },
              "biome_pool": ["minecraft:plains"],
              "boundary": {
                "type": "STRUCTURE_WALL",
                "protection_profile": "cobbleventure:adventure_field"
              },
              "connections": [],
              "anchors": { "spawn": { "x": 11, "y": 80, "z": 5 } }
            }
            """);

        var issues = validator.validate(region);
        assertTrue(issues.stream().anyMatch(issue -> issue.path().equals("boundary.template")));
        assertTrue(issues.stream().anyMatch(issue -> issue.path().equals("anchors.spawn")));
    }
}
