package dev.buizz.cobbleventure.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSetValidatorTest {
    private final RegionDefinitionLoader loader = new RegionDefinitionLoader();
    private final RegionSetValidator validator = new RegionSetValidator();

    @Test
    void reportsUnknownTargetAndOverlappingBounds() throws Exception {
        var first = loader.load(regionJson("cobbleventure:first", 0, 100, "cobbleventure:missing"));
        var second = loader.load(regionJson("cobbleventure:second", 100, 200, null));

        var issues = validator.validate(List.of(first, second));

        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("대상 지역")));
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("겹칩니다")));
    }

    @Test
    void acceptsConnectedNonOverlappingRegions() throws Exception {
        var first = loader.load(regionJson("cobbleventure:first", 0, 99, "cobbleventure:second"));
        var second = loader.load(regionJson("cobbleventure:second", 100, 199, "cobbleventure:first"));

        assertTrue(validator.validate(List.of(first, second)).isEmpty());
    }

    private String regionJson(String id, int minX, int maxX, String target) {
        String connections = target == null
            ? "[]"
            : "[{\"target\":\"" + target + "\",\"gate_id\":\"cobbleventure:east_gate\"}]";
        return """
            {
              "schema_version": 1,
              "id": "%s",
              "dimension": "cobbleventure:generation_1",
              "bounds": { "min_x": %d, "min_z": 0, "max_x": %d, "max_z": 100 },
              "biome_pool": ["minecraft:plains"],
              "boundary": {
                "type": "INVISIBLE",
                "protection_profile": "cobbleventure:adventure_field"
              },
              "connections": %s,
              "anchors": {}
            }
            """.formatted(id, minX, maxX, connections);
    }
}
