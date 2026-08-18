import json
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "content_manager.py"
SPEC = importlib.util.spec_from_file_location("content_manager_route_encounters", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
content_manager = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = content_manager
SPEC.loader.exec_module(content_manager)
validate_route_file = content_manager.validate_route_file


def route_document() -> dict:
    return {
        "$schema": "../../schemas/route.schema.json",
        "schema_version": 1,
        "id": "cobbleventure:route/test_encounters",
        "display_name": {"ko_kr": "조우 테스트 길"},
        "enabled": True,
        "route_type": "road",
        "corridor": {"width_blocks": 12, "edge_noise": 0},
        "level_scaling": {"mode": "world", "offset": 0},
        "pokemon_spawns": {
            "inherit_biome": True,
            "excluded_species": [],
            "additions": [],
            "level_overrides": [],
            "encounter_pools": {
                "old_rod": {
                    "enabled": True,
                    "inherit_biome": False,
                    "excluded_species": [],
                    "additions": [{
                        "species": "cobblemon:magikarp",
                        "min_level": 5,
                        "max_level": 10,
                        "weight": 70,
                    }],
                    "level_overrides": [],
                    "trigger_chance": 0.65,
                },
                "headbutt": {
                    "enabled": True,
                    "inherit_biome": False,
                    "excluded_species": [],
                    "additions": [{
                        "species": "cobblemon:caterpie",
                        "min_level": 3,
                        "max_level": 7,
                        "weight": 1,
                    }],
                    "level_overrides": [],
                    "trigger_chance": 0.3,
                },
            },
        },
        "npc_placements": [],
    }


class RouteEncounterPoolValidationTests(unittest.TestCase):
    def validate(self, document: dict):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "route.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            return validate_route_file(path)[1]

    def test_accepts_fishing_and_headbutt_pools(self):
        issues = self.validate(route_document())
        self.assertEqual([], [issue for issue in issues if issue.level == "error"])

    def test_rejects_invalid_trigger_chance_and_weight(self):
        document = route_document()
        pool = document["pokemon_spawns"]["encounter_pools"]["old_rod"]
        pool["trigger_chance"] = 1.5
        pool["additions"][0]["weight"] = 0
        messages = [issue.message for issue in self.validate(document)]
        self.assertTrue(any("발동 확률" in message for message in messages))
        self.assertTrue(any("가중치" in message for message in messages))


if __name__ == "__main__":
    unittest.main()
