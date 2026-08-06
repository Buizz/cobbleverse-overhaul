import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "build_spawn_index.py"
SPEC = importlib.util.spec_from_file_location("build_spawn_index", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SpawnIndexTest(unittest.TestCase):
    def test_preserves_raw_rule_and_normalizes_form_expression(self):
        document = {
            "enabled": True,
            "spawns": [{
                "id": "vulpix-alola",
                "pokemon": "vulpix alolan",
                "type": "pokemon",
                "spawnablePositionType": "grounded",
                "bucket": "rare",
                "level": "10-20",
                "weight": 3.5,
                "condition": {"biomes": ["#cobblemon:is_snowy"], "timeRange": "night"},
                "weightMultiplier": {"multiplier": 2.0, "condition": {"isRaining": True}},
            }],
        }

        index = MODULE.build_index([("data/cobblemon/spawn_pool_world/vulpix.json", document)], "1.7.3")
        rule = index["rules"][0]

        self.assertEqual("cobblemon:vulpix", rule["species_id"])
        self.assertEqual("vulpix alolan", rule["pokemon_expression"])
        self.assertEqual(document["spawns"][0], rule["raw"])
        self.assertEqual(2.0, rule["weight_multiplier"]["multiplier"])

    def test_reads_source_tree_and_reconciles_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            spawn_root = root / "data" / "cobblemon" / "spawn_pool_world"
            spawn_root.mkdir(parents=True)
            (spawn_root / "bulbasaur.json").write_text(json.dumps({
                "enabled": True,
                "spawns": [{
                    "id": "bulbasaur-1", "pokemon": "bulbasaur", "type": "pokemon",
                    "spawnablePositionType": "grounded", "bucket": "ultra-rare",
                    "level": "5-32", "weight": 6.0,
                }],
            }), encoding="utf-8")
            habitats = root / "habitats.json"
            habitats.write_text(json.dumps({"pokemon": [
                {"id": "cobblemon:bulbasaur", "implemented": True},
                {"id": "cobblemon:missingno", "implemented": True},
            ]}), encoding="utf-8")

            index = MODULE.build_index(MODULE.iter_spawn_documents(root), "1.7.3")
            report = MODULE.reconcile(index, habitats)

            self.assertEqual(1, index["summary"]["rules"])
            self.assertEqual(["cobblemon:missingno"], report["implemented_without_spawn_rules"])


if __name__ == "__main__":
    unittest.main()
